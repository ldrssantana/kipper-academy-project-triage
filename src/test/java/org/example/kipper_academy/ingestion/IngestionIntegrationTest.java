package org.example.kipper_academy.ingestion;

import org.example.kipper_academy.messaging.IssueEventMessage;
import org.example.kipper_academy.messaging.MessagingProperties;
import org.example.kipper_academy.persistence.IssueEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Passo 3 da SPEC (criterio de aceite 1): POST /api/events valida, audita e publica na fila.
 * Roda contra Postgres + RabbitMQ do docker-compose, com uma fila PROPRIA de teste
 * (nao consome nem apaga mensagens da fila real da demo). Banco faz rollback por teste.
 */
@SpringBootTest(properties = {
        "app.messaging.exchange=kipper.test.issue-events",
        "app.messaging.queue=kipper.test.issue-events.triage",
        "app.messaging.dead-letter-queue=kipper.test.issue-events.triage.dlq"
})
@AutoConfigureMockMvc
@ActiveProfiles("ingestion")
@Transactional
class IngestionIntegrationTest {

    private static final ParameterizedTypeReference<IssueEventMessage> MESSAGE_TYPE =
            new ParameterizedTypeReference<>() { };

    @Autowired
    MockMvc mvc;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    MessagingProperties props;

    @Autowired
    IssueEventRepository issueEventRepository;

    @BeforeEach
    void emptyTestQueue() {
        amqpAdmin.purgeQueue(props.queue(), false);
    }

    @Test
    void eventoValidoRetorna202EChegaNaFila() throws Exception {
        String eventId = newEventId();

        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "eventId": "%s",
                          "type": "issue.created",
                          "occurredAt": "2026-10-22T12:00:00Z",
                          "data": {
                            "repository": "equipe/projeto-teste",
                            "issueNumber": 42,
                            "title": "Erro ao entrar no sistema",
                            "description": "Ao enviar o formulario de login, recebo erro 500."
                          }
                        }
                        """.formatted(eventId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        IssueEventMessage msg = nextMessage();
        assertThat(msg).isNotNull();
        assertThat(msg.eventId()).isEqualTo(eventId);
        assertThat(msg.repository()).isEqualTo("equipe/projeto-teste");
        assertThat(msg.issueNumber()).isEqualTo(42);
        assertThat(msg.title()).isEqualTo("Erro ao entrar no sistema");
        assertThat(msg.occurredAt()).hasToString("2026-10-22T12:00:00Z");

        assertThat(issueEventRepository.findById(eventId)).hasValueSatisfying(e -> {
            assertThat(e.getEventType()).isEqualTo("issue.created");
            assertThat(e.getReceivedAt()).isNotNull();
        });
    }

    @Test
    void camposOpcionaisAusentesSaoAceitos() throws Exception {
        // Sem type, occurredAt e description: aceito na ingestao; o worker decide a revisao humana.
        String eventId = newEventId();

        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId": "%s", "data": {"repository": "equipe/projeto-teste", "issueNumber": 7, "title": "Sem descricao"}}
                        """.formatted(eventId)))
                .andExpect(status().isAccepted());

        IssueEventMessage msg = nextMessage();
        assertThat(msg).isNotNull();
        assertThat(msg.type()).isEqualTo("issue.created");
        assertThat(msg.description()).isNull();
        assertThat(issueEventRepository.findById(eventId)).isPresent();
    }

    @Test
    void campoObrigatorioAusenteRetorna400ENaoPublica() throws Exception {
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId": "%s", "data": {"repository": "equipe/projeto-teste", "issueNumber": 42}}
                        """.formatted(newEventId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['data.title']").value("title e obrigatorio"));

        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"data": {"title": "x"}}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.eventId").exists())
                .andExpect(jsonPath("$.errors['data.repository']").exists())
                .andExpect(jsonPath("$.errors['data.issueNumber']").exists());

        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId": "evt-sem-data"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.data").exists());

        assertThat(rabbitTemplate.receive(props.queue(), 500)).isNull();
    }

    @Test
    void jsonMalformadoRetorna400() throws Exception {
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId": "x", "data": {"repository": "r", "issueNumber": "abc", "title": "t"}}
                        """))
                .andExpect(status().isBadRequest());

        assertThat(rabbitTemplate.receive(props.queue(), 500)).isNull();
    }

    @Test
    void mesmoEventIdReenviadoEAuditadoUmaVezMasPublicadoDeNovo() throws Exception {
        String eventId = newEventId();
        String body = """
                {"eventId": "%s", "data": {"repository": "equipe/projeto-teste", "issueNumber": 42,
                 "title": "Original", "description": "d"}}
                """.formatted(eventId);
        String resent = body.replace("Original", "Reenvio alterado");

        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(resent))
                .andExpect(status().isAccepted());

        // A auditoria guarda o evento original (o reenvio nao sobrescreve)...
        assertThat(issueEventRepository.findById(eventId))
                .hasValueSatisfying(e -> assertThat(e.getTitle()).isEqualTo("Original"));
        // ...mas as duas entregas vao para a fila: deduplicar e papel do consumidor (SPEC 3.2).
        assertThat(nextMessage()).extracting(IssueEventMessage::eventId).isEqualTo(eventId);
        assertThat(nextMessage()).extracting(IssueEventMessage::eventId).isEqualTo(eventId);
    }

    private IssueEventMessage nextMessage() {
        return rabbitTemplate.receiveAndConvert(props.queue(), 2000, MESSAGE_TYPE);
    }

    private static String newEventId() {
        return "evt-test-" + UUID.randomUUID();
    }
}
