package org.example.kipper_academy.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Envelope do evento recebido em {@code POST /api/events} (contrato da SPEC 4).
 * Obrigatorios: eventId, data.repository, data.issueNumber, data.title.
 * Os limites de tamanho espelham as colunas do schema.sql, para virarem 400 e nao erro de banco.
 */
public record IssueEventRequest(
        @NotBlank(message = "eventId e obrigatorio")
        @Size(max = 100, message = "eventId deve ter no maximo 100 caracteres")
        String eventId,

        @Size(max = 50, message = "type deve ter no maximo 50 caracteres")
        String type,

        Instant occurredAt,

        @NotNull(message = "data e obrigatorio")
        @Valid
        Data data) {

    /** Tipo assumido quando o evento nao informa {@code type} (o unico do contrato de exemplo). */
    public static final String DEFAULT_TYPE = "issue.created";

    public String typeOrDefault() {
        return type == null || type.isBlank() ? DEFAULT_TYPE : type;
    }

    public record Data(
            @NotBlank(message = "repository e obrigatorio")
            @Size(max = 200, message = "repository deve ter no maximo 200 caracteres")
            String repository,

            @NotNull(message = "issueNumber e obrigatorio")
            @Positive(message = "issueNumber deve ser positivo")
            Integer issueNumber,

            @NotBlank(message = "title e obrigatorio")
            @Size(max = 500, message = "title deve ter no maximo 500 caracteres")
            String title,

            /* Opcional: vazio/ausente e aceito aqui e tratado pelo worker como "conteudo insuficiente" (SPEC 6.1). */
            String description) {
    }
}
