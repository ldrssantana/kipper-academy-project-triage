package org.example.kipper_academy.worker;

import lombok.RequiredArgsConstructor;
import org.example.kipper_academy.messaging.IssueEventMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Consumidor da fila (Metodo distribuido 1, SPEC 3.1). So existe no processo worker, que pode
 * ser parado e religado sem a ingestao perceber: enquanto ele esta fora, a fila retem o trabalho.
 *
 * <p>A mensagem so e confirmada (ack) ao broker depois que {@link TriageService#process} retorna,
 * ou seja, depois do commit. Se o worker cair antes do ack, o broker reentrega e o ledger de
 * idempotencia impede o reprocessamento (at-least-once + consumidor idempotente).
 * Falhas repetidas vao para a DLQ (retry configurado em application-worker.properties).
 */
@Component
@Profile("worker")
@RequiredArgsConstructor
public class TriageConsumer {

    private final TriageService triageService;

    @RabbitListener(queues = "${app.messaging.queue}")
    public void onIssueEvent(IssueEventMessage event) {
        triageService.process(event);
    }
}
