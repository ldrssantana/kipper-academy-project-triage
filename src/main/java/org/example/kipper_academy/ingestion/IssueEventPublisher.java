package org.example.kipper_academy.ingestion;

import lombok.RequiredArgsConstructor;
import org.example.kipper_academy.messaging.IssueEventMessage;
import org.example.kipper_academy.messaging.MessagingProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Produtor da fila (SPEC 3.1). Publica e espera a confirmacao do broker (publisher confirm)
 * antes de retornar: so depois disso o evento e considerado "aceito" e a API responde 202.
 * Sem confirmacao dentro do timeout, lanca AmqpException (vira 503 na API).
 */
@Component
@Profile("ingestion")
@RequiredArgsConstructor
public class IssueEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties props;

    public void publish(IssueEventMessage message) {
        rabbitTemplate.invoke(ops -> {
            ops.convertAndSend(props.exchange(), props.routingKey(), message);
            ops.waitForConfirmsOrDie(props.confirmTimeout().toMillis());
            return null;
        });
    }
}
