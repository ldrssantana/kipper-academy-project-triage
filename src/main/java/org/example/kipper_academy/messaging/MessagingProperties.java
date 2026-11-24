package org.example.kipper_academy.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Nomes da topologia RabbitMQ (prefixo {@code app.messaging} no application.properties).
 * Compartilhado pelos processos ingestion (produtor) e worker (consumidor).
 *
 * @param exchange        exchange direct onde a ingestao publica os eventos
 * @param queue           fila duravel consumida pelo worker
 * @param routingKey      chave que liga a exchange a fila
 * @param deadLetterQueue fila para onde vao mensagens rejeitadas pelo worker (sem reentrega infinita)
 * @param confirmTimeout  quanto a ingestao espera a confirmacao do broker antes de responder 202
 */
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(
        String exchange,
        String queue,
        String routingKey,
        String deadLetterQueue,
        Duration confirmTimeout) {
}
