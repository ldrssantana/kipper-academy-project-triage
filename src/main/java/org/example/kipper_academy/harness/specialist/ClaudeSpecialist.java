package org.example.kipper_academy.harness.specialist;

import com.anthropic.models.messages.OutputConfig;
import org.example.kipper_academy.domain.TriageCategory;
import org.example.kipper_academy.harness.ai.AiResponseException;
import org.example.kipper_academy.harness.ai.ClaudeCaller;
import org.example.kipper_academy.harness.ai.IssuePrompt;
import org.example.kipper_academy.harness.tool.CatalogTool;
import org.example.kipper_academy.messaging.IssueEventMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Base dos especialistas: mesma ferramenta (catalogo restrito ao repositorio do evento), mesmo
 * formato de resposta; cada especialista so muda o prompt de sistema.
 */
abstract class ClaudeSpecialist implements SpecialistAgent {

    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "resumo", Map.of("type", "string"),
                    "proximoPasso", Map.of("type", "string")),
            "required", List.of("resumo", "proximoPasso"),
            "additionalProperties", false);

    /** Regras comuns aos dois especialistas, anexadas ao prompt de cada um. */
    static final String COMMON_RULES = """
            Antes de responder, consulte o catalogo do projeto com a ferramenta consultarCatalogoProjeto \
            (passando o repositorio da issue) e siga as orientacoes de triagem que ele trouxer.
            Responda em portugues, de forma objetiva:
            - resumo: 1 a 3 frases descrevendo o que a issue relata, sem acrescentar fatos.
            - proximoPasso: 1 a 3 frases com uma acao concreta, citando o time responsavel do catalogo.
            Nunca invente arquivo, classe, endpoint, causa-raiz, versao ou dependencia que nao esteja no \
            texto da issue ou no catalogo. Se faltar informacao, o proximo passo e pedir essa informacao.
            """ + IssuePrompt.DATA_NOT_INSTRUCTIONS;

    private final ClaudeCaller claude;
    private final CatalogTool catalogTool;
    private final JsonMapper jsonMapper;

    ClaudeSpecialist(ClaudeCaller claude, CatalogTool catalogTool, JsonMapper jsonMapper) {
        this.claude = claude;
        this.catalogTool = catalogTool;
        this.jsonMapper = jsonMapper;
    }

    abstract String systemPrompt();

    @Override
    public Proposal analyze(IssueEventMessage event, TriageCategory category) {
        String user = "Categoria definida pelo roteador: " + category + "\n\n" + IssuePrompt.of(event);
        String json = claude.run(new ClaudeCaller.Request(
                id() + " " + event.eventId(),
                systemPrompt(),
                user,
                List.of(catalogTool.definition()),
                (name, input) -> catalogTool.execute(name, input, event.repository()),
                SCHEMA, OutputConfig.Effort.MEDIUM, 8_000));
        try {
            return jsonMapper.readValue(json, Proposal.class);
        } catch (JacksonException e) {
            throw new AiResponseException("resposta do especialista fora do schema", e);
        }
    }
}
