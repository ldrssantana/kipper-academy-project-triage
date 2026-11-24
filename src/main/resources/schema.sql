-- Schema do Assistente de Manutencao (SPEC secao 8).
-- Idempotente: pode rodar a cada startup, inclusive pelos dois processos (ingestion e worker).

-- Auditoria do evento bruto recebido. event_id como PK: o mesmo evento nunca e gravado duas vezes.
CREATE TABLE IF NOT EXISTS issue_event (
    event_id      VARCHAR(100)  PRIMARY KEY,
    event_type    VARCHAR(50)   NOT NULL,
    repository    VARCHAR(200)  NOT NULL,
    issue_number  INTEGER       NOT NULL,
    title         VARCHAR(500)  NOT NULL,
    description   TEXT,
    occurred_at   TIMESTAMPTZ,
    received_at   TIMESTAMPTZ   NOT NULL
);

-- Estado atual da triagem por issue (o que o GET /api/issues devolve).
CREATE TABLE IF NOT EXISTS issue_triage (
    id                    BIGSERIAL     PRIMARY KEY,
    repository            VARCHAR(200)  NOT NULL,
    issue_number          INTEGER       NOT NULL,
    last_event_id         VARCHAR(100)  NOT NULL,
    category              VARCHAR(30),
    summary               TEXT,
    next_step             TEXT,
    specialist            VARCHAR(50),
    review_reason         TEXT,
    publication_status    VARCHAR(30)   NOT NULL,
    publication_error     TEXT,
    external_comment_id   BIGINT,
    external_comment_url  VARCHAR(500),
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uk_issue_triage_repo_number UNIQUE (repository, issue_number)
);

-- Ledger de idempotencia (Metodo distribuido 2 - consumidor idempotente).
-- A PK em event_id e o que garante "efeito exatamente uma vez" na acao externa:
-- um segundo INSERT do mesmo event_id falha no banco, mesmo com dois consumidores concorrentes.
CREATE TABLE IF NOT EXISTS processed_events (
    event_id      VARCHAR(100)  PRIMARY KEY,
    processed_at  TIMESTAMPTZ   NOT NULL
);

-- Catalogo de dominio consultado pela ferramenta controlada da IA (SPEC secao 5 e 6.3).
CREATE TABLE IF NOT EXISTS project_catalog (
    repository       VARCHAR(200)  PRIMARY KEY,
    responsible      VARCHAR(200)  NOT NULL,
    triage_guidance  TEXT          NOT NULL
);
