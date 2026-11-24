package org.example.kipper_academy.ingestion.webhook;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webhook do GitHub com payload no formato real do evento "issues" e assinatura HMAC calculada
 * com o segredo de teste. Nenhuma chamada ao GitHub; fila propria de teste; banco com rollback.
 */
@SpringBootTest(properties = {
        "app.github.webhook-secret=segredo-de-teste",
        "app.messaging.exchange=kipper.test-webhook.issue-events",
        "app.messaging.queue=kipper.test-webhook.issue-events.triage",
        "app.messaging.dead-letter-queue=kipper.test-webhook.issue-events.triage.dlq"
})
@AutoConfigureMockMvc
@ActiveProfiles("ingestion")
@Transactional
class GitHubWebhookIntegrationTest {

    private static final ParameterizedTypeReference<IssueEventMessage> MESSAGE =
            new ParameterizedTypeReference<>() { };

    @Autowired
    MockMvc mvc;

    @Autowired
    GitHubSignatureVerifier verifier;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    MessagingProperties props;

    @Autowired
    IssueEventRepository issueEventRepository;

    @BeforeEach
    void emptyQueue() {
        amqpAdmin.purgeQueue(props.queue(), false);
    }

    @Test
    void issueAbertaNoGitHubEntraNaFilaComOMapeamentoDocumentado() throws Exception {
        String delivery = UUID.randomUUID().toString();

        send("issues", delivery, issuesPayload("opened", "\"Ao enviar o formulário de login, recebo erro 500.\""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value("gh-" + delivery))
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        IssueEventMessage msg = rabbitTemplate.receiveAndConvert(props.queue(), 2000, MESSAGE);
        assertThat(msg).isNotNull();
        assertThat(msg.eventId()).isEqualTo("gh-" + delivery);
        assertThat(msg.type()).isEqualTo("issue.created");
        assertThat(msg.repository()).isEqualTo("leandro-triage-bot/projeto_teste");
        assertThat(msg.issueNumber()).isEqualTo(7);
        assertThat(msg.title()).isEqualTo("Erro ao entrar no sistema");
        assertThat(msg.description()).isEqualTo("Ao enviar o formulário de login, recebo erro 500.");
        assertThat(msg.occurredAt()).hasToString("2026-11-24T15:00:00Z");
        assertThat(issueEventRepository.findById("gh-" + delivery)).isPresent();
    }

    @Test
    void issueSemDescricaoEAceitaComDescricaoNula() throws Exception {
        String delivery = UUID.randomUUID().toString();

        send("issues", delivery, issuesPayload("opened", "null")).andExpect(status().isAccepted());

        IssueEventMessage msg = rabbitTemplate.receiveAndConvert(props.queue(), 2000, MESSAGE);
        assertThat(msg).isNotNull();
        assertThat(msg.description()).isNull();
    }

    @Test
    void redeliverDoGitHubReusaOMesmoEventId() throws Exception {
        String delivery = UUID.randomUUID().toString();
        String body = issuesPayload("opened", "\"descricao\"");

        send("issues", delivery, body).andExpect(status().isAccepted());
        send("issues", delivery, body).andExpect(status().isAccepted());

        // As duas entregas vao para a fila com o MESMO eventId: o worker processa uma so (Metodo 2).
        assertThat(rabbitTemplate.receiveAndConvert(props.queue(), 2000, MESSAGE).eventId()).isEqualTo("gh-" + delivery);
        assertThat(rabbitTemplate.receiveAndConvert(props.queue(), 2000, MESSAGE).eventId()).isEqualTo("gh-" + delivery);
    }

    @Test
    void assinaturaInvalidaOuAusenteERecusadaENadaEntraNaFila() throws Exception {
        String body = issuesPayload("opened", "\"descricao\"");

        mvc.perform(post("/api/webhooks/github").contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "issues")
                        .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                        .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
                        .content(body))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/webhooks/github").contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "issues")
                        .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                        .content(body))
                .andExpect(status().isUnauthorized());

        assertThat(rabbitTemplate.receive(props.queue(), 500)).isNull();
    }

    @Test
    void pingEOutrasAcoesNaoDisparamTriagem() throws Exception {
        send("ping", UUID.randomUUID().toString(), "{\"zen\": \"Keep it logically awesome.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PONG"));

        send("issues", UUID.randomUUID().toString(), issuesPayload("edited", "\"descricao\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IGNORED"));

        send("issue_comment", UUID.randomUUID().toString(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IGNORED"));

        assertThat(rabbitTemplate.receive(props.queue(), 500)).isNull();
    }

    private ResultActions send(String event, String delivery, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return mvc.perform(post("/api/webhooks/github").contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", event)
                .header("X-GitHub-Delivery", delivery)
                .header("X-Hub-Signature-256", "sha256=" + HexFormat.of().formatHex(verifier.hmac(bytes)))
                .content(bytes));
    }

    /** Recorte do payload real do evento "issues" do GitHub (campos extras sao ignorados). */
    private static String issuesPayload(String action, String bodyJson) {
        return """
                {
                  "action": "%s",
                  "issue": {
                    "number": 7,
                    "title": "Erro ao entrar no sistema",
                    "body": %s,
                    "created_at": "2026-11-24T15:00:00Z",
                    "user": {"login": "alguem"},
                    "state": "open"
                  },
                  "repository": {"full_name": "leandro-triage-bot/projeto_teste", "private": false},
                  "sender": {"login": "alguem"}
                }
                """.formatted(action, bodyJson);
    }
}
