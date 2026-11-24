package org.example.kipper_academy.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.kipper_academy.api.IssueEventRequest;
import org.example.kipper_academy.messaging.IssueEventMessage;
import org.example.kipper_academy.persistence.IssueEventRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Recebe o evento validado: grava a auditoria do evento bruto e publica na fila.
 *
 * <p>Ordem deliberada: grava antes de publicar. Se a publicacao falhar, o cliente recebe 503
 * e reenvia; o INSERT idempotente nao duplica a auditoria e a publicacao e refeita.
 *
 * <p>Um eventId repetido e aceito e publicado de novo de proposito: a ingestao nao esconde a
 * reentrega. Quem garante o efeito exatamente-uma-vez e o consumidor idempotente (SPEC 3.2).
 */
@Slf4j
@Service
@Profile("ingestion")
@RequiredArgsConstructor
public class IngestionService {

    private final IssueEventRepository issueEventRepository;
    private final IssueEventPublisher publisher;

    public void receive(IssueEventRequest request) {
        IssueEventRequest.Data data = request.data();
        String type = request.typeOrDefault();

        boolean firstTime = issueEventRepository.insertIfAbsent(
                request.eventId(), type, data.repository(), data.issueNumber(),
                data.title(), data.description(), request.occurredAt(), Instant.now()) == 1;

        publisher.publish(new IssueEventMessage(
                request.eventId(), type, request.occurredAt(),
                data.repository(), data.issueNumber(), data.title(), data.description()));

        if (firstTime) {
            log.info("Evento {} ({}#{}) aceito e publicado na fila",
                    request.eventId(), data.repository(), data.issueNumber());
        } else {
            log.info("Evento {} reenviado: ja estava auditado, publicado de novo (o worker deduplica)",
                    request.eventId());
        }
    }
}
