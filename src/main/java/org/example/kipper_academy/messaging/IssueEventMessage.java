package org.example.kipper_academy.messaging;

import java.time.Instant;

/**
 * Mensagem trafegada na fila entre o processo ingestion e o processo worker.
 * Carrega o evento inteiro para o worker nao depender de reler o banco para comecar.
 * O {@code eventId} e a chave de idempotencia usada pelo consumidor (SPEC 3.2).
 */
public record IssueEventMessage(
        String eventId,
        String type,
        Instant occurredAt,
        String repository,
        Integer issueNumber,
        String title,
        String description) {
}
