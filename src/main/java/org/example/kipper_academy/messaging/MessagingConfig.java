package org.example.kipper_academy.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Topologia da fila (Metodo distribuido 1 - mensageria assincrona com fila duravel, SPEC 3.1).
 *
 * <p>Declarada pelos DOIS processos (ingestion e worker): assim a fila existe assim que a
 * ingestao sobe, e eventos aceitos ficam retidos mesmo que o worker nunca tenha rodado.
 * Exchange e fila sao duraveis e o RabbitTemplate publica mensagens persistentes, entao o
 * trabalho aceito sobrevive tambem a um restart do broker.
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfig {

    @Bean
    DirectExchange issueEventsExchange(MessagingProperties props) {
        return ExchangeBuilder.directExchange(props.exchange()).durable(true).build();
    }

    /**
     * Fila principal. Mensagens rejeitadas pelo worker (sem requeue) vao para a DLQ pela
     * exchange padrao (""), em vez de voltarem para a fila em loop infinito.
     */
    @Bean
    Queue issueEventsQueue(MessagingProperties props) {
        return QueueBuilder.durable(props.queue())
                .deadLetterExchange("")
                .deadLetterRoutingKey(props.deadLetterQueue())
                .build();
    }

    @Bean
    Queue issueEventsDeadLetterQueue(MessagingProperties props) {
        return QueueBuilder.durable(props.deadLetterQueue()).build();
    }

    @Bean
    Binding issueEventsBinding(Queue issueEventsQueue, DirectExchange issueEventsExchange,
                               MessagingProperties props) {
        return BindingBuilder.bind(issueEventsQueue).to(issueEventsExchange).with(props.routingKey());
    }

    /** Mensagens em JSON (legiveis na UI do RabbitMQ), usando o JsonMapper configurado pelo Spring Boot. */
    @Bean
    MessageConverter messageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper);
    }
}
