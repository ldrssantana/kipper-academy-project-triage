# SPEC — Assistente de Manutenção de Software

> Spec-Driven Development: esta é a fonte da verdade do projeto. Se algo no código
> divergir do combinado aqui, corrige-se a spec e regenera-se o plano — não o contrário.
> Fluxo: **Spec → Plano → Tarefas → Implementação → Validação**.

## 1. Objetivo

Construir um backend que recebe eventos de issues de repositórios de teste, classifica
cada issue com apoio de um provedor de IA, produz um resumo e uma sugestão de próximo
passo, e **publica um comentário real de triagem na issue de origem**. Nível de entrega:
**pleno** — exige arquitetura distribuída (componentes em processos separados) e
roteamento de IA para especialistas.

Não é exigido: corrigir código, fechar issues, abrir pull requests ou construir
interface gráfica.

## 2. Decisões já tomadas (e por quê)

| Decisão | Escolha | Motivo |
| --- | --- | --- |
| Linguagem/Framework | Java 17 + Spring Boot (já iniciado no repo) | Base existente, já conhecida |
| Mensageria | RabbitMQ (`spring-boot-starter-amqp`) | Suporte pronto no Spring, UI visual para a demo, 1 container só, sem conceitos extras (partição, consumer group) |
| Banco | PostgreSQL | Padrão com Spring Data JPA, constraint única nativa para idempotência |
| Provedor de IA | Anthropic Claude API (SDK oficial Java `com.anthropic:anthropic-java`) | Tool use simples via JSON Schema, SDK evita montar HTTP/JSON na mão |
| Destino externo (conta de teste) | Repositório de teste no GitHub + GitHub REST API (`POST /repos/{owner}/{repo}/issues/{n}/comments`) com um Personal Access Token | API simples, só precisa de um token, não precisa SDK |
| Infra local | Docker Compose com 2 serviços: `rabbitmq`, `postgres` | Sobe com `docker compose up -d`, sem infra própria pra aprender |

**Suposição a confirmar com o usuário:** o "sistema externo de teste" é o GitHub
(issues de um repositório de teste seu). Se preferir GitLab/Jira/outro, o adaptador de
publicação é a única peça que muda.

## 3. Arquitetura distribuída — como o desenho do desafio foi interpretado

O diagrama do desafio (`pleno-v3.excalidraw` / imagem anexada) mostra:

```
Receber evento ──────────────────────────────┐
                                              ├──▶ Análise em outro processo ──▶ Validar proposta especializada ──▶ Encaminhar sem duplicar
Análise temporariamente parada ·· (pontilhado) ·· Trabalho aceito continua disponível ──┘
```

Isso vira, no código, **um único Spring Boot, rodado como dois processos** via perfil:

- **Processo `ingestion`** (`--spring.profiles.active=ingestion`): expõe a API REST,
  valida o payload, grava o evento bruto e publica na fila RabbitMQ. Não registra o
  `@RabbitListener` (desligado por `@Profile`). Fica sempre no ar.
- **Processo `worker`** (`--spring.profiles.active=worker`): registra o
  `@RabbitListener` que consome a fila, chama o harness de IA, valida o resultado,
  publica o comentário externo e grava o resultado. Pode ser **parado e religado** sem
  o `ingestion` perceber — é exatamente o "Análise temporariamente parada" do desenho.

Dois processos do mesmo jar, dois terminais, zero infraestrutura de orquestração —
simplicidade deliberada, mas ainda genuinamente "componentes que podem executar em
processos separados".

### 3.1 Método distribuído 1 — Mensageria assíncrona com fila durável

- **Aula de referência:** 18/10 (EDA, filas, RabbitMQ, consumidor idempotente) e 29/09
  (Arquitetura Assíncrona: Produtor → Fila/Tópico → Consumidor).
- **Problema que resolve:** desacoplamento temporal entre recepção e análise. O
  processo `ingestion` nunca espera o `worker`; a fila retém o trabalho aceito até o
  `worker` voltar.
- **Onde aparece no código:** `IngestionController` publica em
  `RabbitTemplate.convertAndSend(...)`; `TriageConsumer` (`@RabbitListener`) no perfil
  `worker` consome.
- **Como demonstrar:** derrubar o processo `worker`; continuar enviando
  `POST /api/events` (sempre `202 Accepted`); abrir a UI do RabbitMQ
  (`localhost:15672`) e mostrar mensagens acumulando na fila; subir o `worker` de novo
  e mostrar a fila esvaziando e os resultados aparecendo no `GET /api/issues`.

### 3.2 Método distribuído 2 — Consumidor idempotente (efeito exatamente uma vez)

- **Aula de referência:** 18/10 ("at-least-once + consumidor idempotente = efeito
  exatamente uma vez", `eventId` único, tabela de eventos processados, constraint
  única) e 29/09 (Idempotency Key).
- **Problema que resolve:** brokers entregam *at-least-once* — a mesma mensagem (ou o
  mesmo evento reenviado manualmente) pode chegar mais de uma vez. Sem essa técnica, o
  comentário de triagem seria publicado duas vezes na mesma issue.
- **Onde aparece no código:** tabela `processed_events` com `event_id UNIQUE`. Antes de
  chamar a API externa, `TriageService` verifica essa tabela **na mesma transação**
  em que grava o resultado; se o `eventId` já existe, não repete a chamada externa e
  devolve a confirmação já registrada.
- **Como demonstrar:** reenviar o mesmo JSON de evento (mesmo `eventId`) depois de já
  processado; mostrar que `GET /api/issues` continua com o mesmo `externalCommentId` e
  que a issue de teste no GitHub tem **um único** comentário.

> As duas técnicas resolvem problemas diferentes (disponibilidade sob falha do
> consumidor vs. correção sob reentrega) — não são duas tecnologias para o mesmo
> problema.

## 4. Contrato do evento de entrada

Reaproveita o contrato de exemplo do próprio desafio, sem alterações:

```json
{
  "eventId": "evt-001",
  "type": "issue.created",
  "occurredAt": "2026-10-22T12:00:00Z",
  "data": {
    "repository": "equipe/projeto-teste",
    "issueNumber": 42,
    "title": "Erro ao entrar no sistema",
    "description": "Ao enviar o formulário de login, recebo erro 500."
  }
}
```

**Obrigatórios:** `eventId`, `data.repository`, `data.issueNumber`, `data.title`.
**Opcional:** `data.description` (pode vir vazia ou ausente).

## 5. Catálogo de domínio

Tabela `project_catalog`, semeada via `data.sql` no startup (dados fictícios):

| repository | responsible | triage_guidance |
| --- | --- | --- |
| `equipe/projeto-teste` | Time Billing (`billing-team`) | Erros 5xx: pedir log completo e passos de reprodução. Timeouts: verificar dependência externa citada na descrição, nunca presumir qual. |
| `equipe/projeto-mobile` | Time Mobile (`mobile-team`) | Bugs de UI: pedir versão do app e modelo do device. Sugestões de UX entram como melhoria. |
| `equipe/projeto-docs` | Time Docs (`docs-team`) | A maioria das issues aqui é dúvida ou melhoria de conteúdo; raramente bug. |

Repositório fora dessa tabela → **desconhecido** → revisão humana (regra 6.1).

## 6. Regras de negócio e guarda de segurança (harness)

O harness (`TriageHarness`) é código determinístico que decide **quando** chamar a IA,
**quais ferramentas** ela pode usar e **valida** o resultado antes de qualquer ação
externa. O texto do evento (`title`, `description`) é tratado como **dado**, nunca como
instrução.

### 6.1 Guardas determinísticas (rodam antes de qualquer chamada à IA)

1. `repository` não existe no catálogo → `categoria = REVISAO_HUMANA`,
   `publicationStatus = SKIPPED_REVIEW`, motivo = "repositório desconhecido". Nenhuma
   chamada de IA é feita.
2. `description` ausente ou em branco → `categoria = REVISAO_HUMANA`,
   `publicationStatus = SKIPPED_REVIEW`, motivo = "conteúdo insuficiente". Nenhuma
   chamada de IA é feita.

Essas duas regras cobrem literalmente o requisito "repositório desconhecido ou
conteúdo insuficiente deve seguir para revisão humana" — de forma determinística, sem
depender do modelo "decidir certo".

### 6.2 Roteamento de IA (passa pela guarda acima)

- **Router** (prompt curto, sem ferramentas): classifica em `BUG`, `MELHORIA` ou
  `DUVIDA` a partir de título + descrição.
- `BUG` → **Bug Investigator** (especialista 1): tem a ferramenta
  `consultarCatalogoProjeto(repository)`; produz resumo do problema + próximo passo
  de investigação (ex.: coletar log, passos de reprodução). Instrução explícita:
  **não inventar** arquivo, causa-raiz ou dependência que não esteja no evento ou no
  catálogo.
- `MELHORIA` ou `DUVIDA` → **Improvement & Question Analyst** (especialista 2): mesma
  ferramenta de catálogo; produz resumo + sugestão de próximo passo (ex.: encaminhar
  ao responsável do catálogo, pedir mais contexto de uso).

  *Decisão registrada:* o item 3 (obrigatório em qualquer nível) exige classificar em
  3 categorias — bug, melhoria, dúvida. Já o roteamento de especialistas (exclusivo do
  nível pleno) pede "**pelo menos dois** especialistas, **por exemplo** investigação de
  bugs e análise de melhorias" e pede mostrar na demo "**duas** rotas reais". São
  requisitos diferentes: um fixa as categorias, o outro só fixa um mínimo de 2 rotas e
  cita exatamente esse par como exemplo. Por isso `DUVIDA` é tratada pelo mesmo
  especialista de melhorias (ambas pedem entendimento de contexto do usuário, não
  causa-raiz de bug) — mantém a classificação em 3 categorias intacta sem exigir um
  terceiro especialista nem uma terceira rota na demonstração.

### 6.3 Ferramenta controlada

Única tool exposta ao modelo: `consultarCatalogoProjeto(repository: string)` →
retorna `{responsible, triageGuidance}` lendo **só** a tabela `project_catalog`. O
modelo não tem acesso a nenhuma outra tool, banco ou rede.

### 6.4 Validação do resultado (harness, depois da resposta da IA)

Antes de aceitar a proposta da IA:

- `categoria` deve ser um dos valores aceitos (enum).
- `resumo` e `proximoPasso` não podem ser vazios.
- Se a resposta não vier no schema esperado, ou o modelo não conseguir classificar com
  confiança → força `REVISAO_HUMANA` (não deixa passar um resultado inválido para a
  publicação).

### 6.5 Idempotência (reforça a seção 3.2)

Antes de publicar o comentário externo, verificar `processed_events` pelo `eventId`.
Se já processado, não republica — devolve a confirmação já salva.

## 7. API

### `POST /api/events`

Recebe o envelope do evento (seção 4). Valida obrigatórios.

- `202 Accepted` → `{ "eventId": "...", "status": "RECEIVED" }`
- `400 Bad Request` → campo obrigatório ausente (`repository`, `issueNumber` ou
  `title`)

### `GET /api/issues?repository={repo}&issueNumber={n}`

- `200 OK`:
  ```json
  {
    "repository": "equipe/projeto-teste",
    "issueNumber": 42,
    "category": "BUG",
    "summary": "...",
    "nextStep": "...",
    "specialist": "BUG_INVESTIGATOR",
    "publicationStatus": "PUBLISHED",
    "externalCommentUrl": "https://github.com/.../issues/42#issuecomment-...",
    "updatedAt": "2026-10-22T12:00:05Z"
  }
  ```
- `404 Not Found` → issue ainda não processada.

## 8. Modelo de dados (PostgreSQL / JPA)

- **`issue_event`** — auditoria do evento bruto recebido: `event_id` (PK),
  `repository`, `issue_number`, `title`, `description`, `occurred_at`, `received_at`.
- **`issue_triage`** — estado atual por issue, upsert por `(repository, issue_number)`:
  `id`, `repository`, `issue_number`, `last_event_id`, `category`, `summary`,
  `next_step`, `specialist`, `publication_status`, `external_comment_id`,
  `external_comment_url`, `created_at`, `updated_at`. `UNIQUE(repository, issue_number)`.
- **`processed_events`** — ledger de idempotência: `event_id` (PK, UNIQUE),
  `processed_at`. É essa tabela, e não a `issue_triage`, que garante efeito
  exatamente-uma-vez na ação externa.
- **`project_catalog`** — `repository` (PK), `responsible`, `triage_guidance`.

## 9. Casos de borda

| Caso | Comportamento esperado |
| --- | --- |
| `repository` fora do catálogo | `REVISAO_HUMANA`, sem chamar IA (6.1) |
| `description` vazia/ausente | `REVISAO_HUMANA`, sem chamar IA (6.1) |
| Falta `title`, `repository` ou `issueNumber` no payload | `400` na ingestão, nunca chega na fila |
| Mesmo `eventId` reenviado após já processado | Não republica; devolve estado salvo (3.2, 6.5) |
| `worker` parado | `ingestion` continua aceitando; fila retém (3.1) |
| Resposta da IA fora do schema esperado | Harness força `REVISAO_HUMANA` (6.4) |
| Chamada à API do GitHub falha (rede, token inválido) | `publicationStatus = FAILED`, erro registrado, **sem** marcar como processado em `processed_events` (permite nova tentativa sem duplicar, pois nada foi publicado) |
| Consulta a issue nunca recebida | `404` |

## 10. Critérios de aceite

1. `POST /api/events` com payload válido retorna `202` e o evento aparece na fila do
   RabbitMQ (visível na UI).
2. Com o processo `worker` **parado**, novos eventos continuam sendo aceitos (`202`) e
   se acumulam na fila, sem erro no `ingestion`.
3. Ao subir o `worker`, a fila esvazia e `GET /api/issues` passa a retornar o
   resultado de cada issue aceita durante a pausa.
4. Evento de exemplo do desafio (login, erro 500) é classificado como `BUG`, com
   resumo e próximo passo coerentes, **sem** citar arquivo/causa não evidenciados.
5. Um comentário real aparece na issue de teste no GitHub, e `GET /api/issues` mostra
   `PUBLISHED` com a URL do comentário.
6. Reenviar o **mesmo** `eventId` não cria um segundo comentário na issue; a issue de
   teste continua com um único comentário.
7. Evento com repositório fora do catálogo **ou** descrição vazia resulta em
   `REVISAO_HUMANA`, sem chamada à IA e sem comentário publicado.
8. README documenta os 2 métodos de arquitetura distribuída (aula, problema, onde no
   código, como demonstrar) e inclui o desenho da arquitetura.

## 11. Fora de escopo

Interface gráfica; correção de código na issue; fechar issues; abrir pull requests;
suporte a mais de um provedor de IA simultâneo; autenticação/autorização na API
(fica para trás do escopo do desafio, mas documentar como limitação conhecida).

## 12. Stack técnico e dependências a adicionar

Gradle (Groovy DSL), acrescentar ao `build.gradle` existente:

- `org.springframework.boot:spring-boot-starter-webmvc` — já existe
- `org.springframework.boot:spring-boot-starter-amqp` — RabbitMQ
- `org.springframework.boot:spring-boot-starter-data-jpa` — Postgres/JPA
- `org.springframework.boot:spring-boot-starter-validation`
- `org.postgresql:postgresql` (runtimeOnly)
- `com.anthropic:anthropic-java` (versão estável mais recente)
- `org.projectlombok:lombok` — já existe (compileOnly + annotationProcessor)
- `org.springframework.boot:spring-boot-starter-webmvc-test` — já existe (testImplementation)
- `org.springframework.amqp:spring-rabbit-test` (testImplementation)
- `org.testcontainers:postgresql`, `org.testcontainers:rabbitmq`, `org.testcontainers:junit-jupiter` (testImplementation)

## 13. Estrutura de pacotes proposta

```
org.example.kipper_academy
├── ingestion/        # Controller REST, validação de payload, publisher da fila
├── messaging/        # Topologia RabbitMQ + IssueEventMessage (contrato compartilhado ingestion↔worker)
├── worker/           # @RabbitListener, orquestra o fluxo de triagem
├── harness/          # TriageHarness: prompt, tool, limites, validação do resultado
│   ├── specialist/   # BugInvestigatorSpecialist, ImprovementAndQuestionSpecialist
│   └── tool/         # ConsultarCatalogoProjetoTool
├── domain/            # Entidades JPA: IssueEvent, IssueTriage, ProcessedEvent, ProjectCatalog
├── persistence/       # Repositories Spring Data JPA
├── external/           # GitHubCommentPublisher (adaptador de saída)
└── api/                # DTOs de request/response
```

## 14. Variáveis de ambiente (`.env.example`)

```
# Banco
POSTGRES_URL=jdbc:postgresql://localhost:5432/kipper_academy
POSTGRES_USER=kipper
POSTGRES_PASSWORD=changeme

# RabbitMQ
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USER=guest
RABBITMQ_PASSWORD=guest

# IA
ANTHROPIC_API_KEY=sk-ant-...

# GitHub (conta/repo de teste)
GITHUB_TEST_TOKEN=ghp_...
GITHUB_TEST_REPO=sua-conta/repo-de-teste
```

## 15. Roteiro de demonstração (mapeado aos critérios de avaliação)

1. Subir `docker compose up -d` (RabbitMQ + Postgres).
2. Subir o processo `ingestion`.
3. **Não** subir o `worker` ainda. Enviar o evento de exemplo via `POST /api/events` →
   mostrar `202` e a mensagem acumulada na UI do RabbitMQ.
4. Subir o processo `worker` → mostrar a fila esvaziando e consultar
   `GET /api/issues?repository=equipe/projeto-teste&issueNumber=42` → `BUG`, resumo,
   próximo passo, `PUBLISHED`, link do comentário real no GitHub.
5. Reenviar o **mesmo** evento (`eventId` idêntico) → mostrar que o comentário na
   issue continua único e a resposta do `GET` não muda.
6. Enviar um evento de repositório fora do catálogo → mostrar `REVISAO_HUMANA` sem
   chamada à IA (log/trace mostrando que o harness nem chamou o Claude).
7. Enviar um evento de melhoria (categoria `MELHORIA`) → mostrar a segunda rota de
   especialista real.

## 16. Plano de implementação sugerido (ordem)

1. `docker-compose.yml` (Postgres + RabbitMQ) e dependências no `build.gradle`.
2. Entidades JPA + `data.sql` do catálogo (sem IA ainda, só persistência).
3. `IngestionController` + fila (publica, sem consumidor ainda) → valida critério 1/2.
4. `TriageConsumer` com guardas determinísticas (6.1), sem IA ainda, sempre
   `REVISAO_HUMANA` → valida fluxo fila→worker e idempotência (3.2/6.5) de ponta a
   ponta antes de acrescentar complexidade de IA.
5. `TriageHarness` + Router + 2 especialistas + tool de catálogo (integração real com
   Claude).
6. `GitHubCommentPublisher` (ação externa real).
7. `GET /api/issues`.
8. README final com os 2 métodos, diagrama, exemplos de evento e `.env.example`.

## 17. Progresso (atualizado em 2026-11-24)

| Passo | Estado | Observações |
| --- | --- | --- |
| 1. Docker + build.gradle | ✅ Feito | `docker compose up -d --wait` sobe Postgres 16 + RabbitMQ 3.13 (healthy). Artefatos do Testcontainers corrigidos para os nomes 2.x (`testcontainers-postgresql`, etc.). |
| 2. Entidades JPA + catálogo | ✅ Feito | `schema.sql` (tabelas + constraints explícitas), `data.sql` (catálogo, `ON CONFLICT DO NOTHING`), `ddl-auto=validate`. Pacotes `domain/` e `persistence/`. `PersistenceIntegrationTest` (3 testes) passando. Colunas extras em `issue_triage`: `review_reason`, `publication_error`. |
| 3. IngestionController + fila | ✅ Feito | `POST /api/events` → grava `issue_event` (`INSERT … ON CONFLICT DO NOTHING`) → publica com *publisher confirm* → `202`. Broker fora → `503` (nunca `202` sem confirmação). Exchange `kipper.issue-events` (direct) → fila durável `kipper.issue-events.triage` com DLQ `…triage.dlq`. `eventId` repetido é aceito e **republicado** (dedup é do worker). `type` ausente → `issue.created`. Perfis: `application-ingestion.properties` (porta 8080) e `application-worker.properties` (sem web). `IngestionIntegrationTest` (5 testes) usa fila própria `kipper.test.*`. RabbitMQ ganhou volume + hostname fixo (mensagens sobrevivem à recriação do container) e healthcheck `check_port_connectivity`. |
| 4. TriageConsumer + guardas + idempotência | ✅ Feito | `TriageConsumer` (`@Profile("worker")`) → `TriageService.process` (transação: claim em `processed_events` → `TriageHarness` → upsert `issue_triage`). `DeterministicGuards`: repo fora do catálogo / descrição vazia → `REVISAO_HUMANA` sem IA; demais eventos também `REVISAO_HUMANA` ("análise de IA ainda não habilitada") até o passo 5. `TriageWorkerIntegrationTest` (6 testes): guardas, reentrega ignorada, 4 threads com o mesmo `eventId` → só 1 processa, mensagem inválida → DLQ. Validado ponta a ponta com os 2 processos (fila 4→0, reenvio ignorado). |
| 5. Harness de IA (Router + 2 especialistas + tool) | ✅ Feito | Modelo `claude-opus-5-5` (`app.ai.model`). `ClaudeCaller`: loop manual de ferramenta (máx. 3 rodadas), saída por *structured outputs* (JSON Schema), `effort` LOW no roteador / MEDIUM nos especialistas, fallback de recusa no servidor (`server-side-fallback-2026-07-01`). `TriageRouter` devolve BUG/MELHORIA/DUVIDA/**INDEFINIDO** (INDEFINIDO → revisão humana). `CatalogTool` (`consultarCatalogoProjeto`) só lê o catálogo **do repositório do evento**; outro repo → erro "acesso negado". Texto da issue vai em `<issue>` como dado (tags de fechamento neutralizadas). Validação 6.4 no `TriageHarness`: recusa, `max_tokens`, JSON fora do schema, campo vazio ou > 1500 chars, erro da API → `REVISAO_HUMANA`. Triagem aceita fica `PENDING` até o passo 6. Testes: `TriageHarnessTest` (7) e `CatalogToolTest` (4) com IA simulada; `ClaudeLiveTest` (2, opt-in `-Plive=true`) validado contra a API real: evento do desafio → BUG/Bug Investigator citando billing-team e pedindo log; modo escuro → MELHORIA/Analyst (~US$ 0,04). |
| 6. GitHubCommentPublisher | ✅ Feito | `RestClient` → `POST /repos/{owner}/{name}/issues/{n}/comments` com o token da conta bot. **Allowlist:** só comenta em `GITHUB_TEST_REPO` (outro repo → `FAILED` sem chamar o GitHub). **Marcador** `<!-- kipper-triage:event=<id> -->` no corpo; antes de publicar, procura o marcador nos comentários da issue e reaproveita o existente. Falha de publicação → `FAILED` + `publication_error` + claim liberado (`ProcessedEventRepository.release`) na mesma transação. Revisão humana nunca publica. Catálogo ganhou `leandro-triage-bot/projeto_teste`. Testes: `GitHubCommentPublisherTest` (4, servidor HTTP simulado) e 2 novos no worker (revisão humana não publica; FAILED → reenvio publica). **Validado de verdade:** issue #1 de `leandro-triage-bot/projeto_teste` recebeu o comentário (BUG / Bug Investigator, `issuecomment-7135127343`); reenvio do mesmo `eventId` ignorado e a issue ficou com **1** comentário. |
| 7. `GET /api/issues` | ⬜ Próximo | |
| 8. README | ⬜ Pendente | |

### Pendências / decisões em aberto

- ✅ **Conta GitHub de teste:** conta bot `leandro-triage-bot`, repo
  `leandro-triage-bot/projeto_teste` (issues #1 e #2), token fine-grained no `.env`.
  Issue **#2** reservada para a demonstração (sem comentários); #1 já foi usada no teste.
- ✅ **Repo real no catálogo:** adicionado ao `data.sql`.

### Notas para os próximos passos

- **Idempotência (feito no passo 4):** `ProcessedEventRepository.claim` = `INSERT … ON
  CONFLICT DO NOTHING` — **nunca** `repository.save()` (faz `merge` e aceitaria em
  silêncio um `eventId` repetido). O claim é o **primeiro** comando da transação de
  `TriageService.process`; se a transação falhar, o claim é desfeito junto.
- **Ambiente local:** `java` não está no PATH; para rodar o Gradle no terminal use
  `JAVA_HOME=~/.jdks/ms-17.0.21.0.2` (JDK 17 baixado pelo IntelliJ).
- **IA (feito no passo 5):** falha da API (rede, chave inválida, 429 após os 2 retries do
  SDK) vira `REVISAO_HUMANA` com motivo "falha ao chamar a IA" — o evento fica registrado
  como processado; para tentar de novo, reenviar com **outro** `eventId`. As chamadas de IA
  rodam dentro da transação do claim (ok com concorrência 1).
- **Teste real com o Claude:** `./gradlew test --tests '*ClaudeLiveTest' -Plive=true`.
- **GitHub (feito no passo 6):** três camadas contra comentário duplicado — (1) ledger
  `processed_events` (reentrega ignorada), (2) marcador oculto no comentário (fecha a janela
  "publicou mas o commit falhou"), (3) allowlist do repositório. Falha → `FAILED` e claim
  liberado: reenviar o **mesmo** `eventId` tenta de novo (diferente da falha de IA).
- **Worker:** concorrência 1, prefetch 1; retry 3x com backoff (1s, 2s) e depois DLQ
  (`application-worker.properties`). A ingestão **não** cria linha em `issue_triage` (o
  `GET` precisa dar `404` até o worker processar); `PENDING` não é mais gravado.
- **Enviar evento com acento pelo curl no Windows:** usar `--data-binary @arquivo.json`
  (arquivo em UTF-8); `-d '...'` direto no Git Bash pode chegar com encoding errado → `400`.
- **Rodar os processos:** `./gradlew bootRun --args='--spring.profiles.active=ingestion'`
  (e `=worker` em outro terminal, a partir do passo 4).
- **Retomar:** abrir o Docker Desktop → `docker compose up -d --wait` → `./gradlew test`.
  Os dados do Postgres persistem no volume `kipper-academy-postgres-data`.
