package org.example.kipper_academy.worker;

import org.example.kipper_academy.domain.IssueTriage;
import org.example.kipper_academy.domain.PublicationStatus;
import org.example.kipper_academy.domain.Specialist;
import org.example.kipper_academy.domain.TriageCategory;
import org.example.kipper_academy.external.GitHubCommentPublisher;
import org.example.kipper_academy.external.GitHubCommentPublisher.PublishedComment;
import org.example.kipper_academy.external.PublicationException;
import org.example.kipper_academy.external.TriageComment;
import org.example.kipper_academy.harness.DeterministicGuards;
import org.example.kipper_academy.harness.TriageRouter;
import org.example.kipper_academy.harness.specialist.BugInvestigatorSpecialist;
import org.example.kipper_academy.harness.specialist.ImprovementQuestionSpecialist;
import org.example.kipper_academy.harness.specialist.SpecialistAgent;
import org.example.kipper_academy.messaging.IssueEventMessage;
import org.example.kipper_academy.messaging.MessagingProperties;
import org.example.kipper_academy.persistence.IssueTriageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Passo 4 da SPEC: fila -> worker -> guardas deterministicas -> banco, com consumidor idempotente.
 * Roda contra Postgres + RabbitMQ do docker-compose, com filas PROPRIAS deste teste (o listener
 * deste contexto nao disputa mensagens com outros testes nem com a fila real da demo).
 */
@SpringBootTest(properties = {
        "app.messaging.exchange=kipper.test-worker.issue-events",
        "app.messaging.queue=kipper.test-worker.issue-events.triage",
        "app.messaging.dead-letter-queue=kipper.test-worker.issue-events.triage.dlq",
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms"
})
@ActiveProfiles("worker")
class TriageWorkerIntegrationTest {

    private static final String KNOWN_REPO = "equipe/projeto-teste";

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    MessagingProperties props;

    @Autowired
    TriageService triageService;

    @Autowired
    IssueTriageRepository triageRepository;

    @Autowired
    JdbcTemplate jdbc;

    /** Numeros de issue aleatorios e altos: nao colidem com dados reais da demo. */
    private final List<Integer> usedIssueNumbers = new ArrayList<>();

    /** IA simulada: este teste valida fila, worker e idempotencia, sem gastar creditos da API. */
    @MockitoBean
    TriageRouter router;

    @MockitoBean
    BugInvestigatorSpecialist bugInvestigator;

    @MockitoBean
    ImprovementQuestionSpecialist analyst;

    /** GitHub simulado: nenhum comentario real e publicado por este teste. */
    @MockitoBean
    GitHubCommentPublisher publisher;

    @BeforeEach
    void emptyDeadLetterQueueAndStubAi() {
        amqpAdmin.purgeQueue(props.deadLetterQueue(), false);

        when(router.classify(any())).thenReturn(new TriageRouter.Decision(TriageRouter.Route.BUG, "erro 500"));
        when(bugInvestigator.id()).thenReturn(Specialist.BUG_INVESTIGATOR);
        when(bugInvestigator.handles(TriageCategory.BUG)).thenReturn(true);
        when(bugInvestigator.analyze(any(), any())).thenReturn(new SpecialistAgent.Proposal(
                "Login retorna erro 500.", "Time Billing (billing-team): pedir log completo e passos de reproducao."));
        when(analyst.id()).thenReturn(Specialist.IMPROVEMENT_QUESTION_ANALYST);
        when(publisher.publish(any(), anyInt(), any(), any()))
                .thenReturn(new PublishedComment(987L, "https://github.com/x/y/issues/1#issuecomment-987", false));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM processed_events WHERE event_id LIKE 'evt-wtest-%'");
        for (Integer n : usedIssueNumbers) {
            jdbc.update("DELETE FROM issue_triage WHERE issue_number = ?", n);
        }
    }

    @Test
    void repositorioForaDoCatalogoVaiParaRevisaoHumana() {
        IssueEventMessage event = event("equipe/nao-cadastrado", "Erro 500 no login");

        publish(event);

        IssueTriage triage = awaitTriage(event);
        assertThat(triage.getCategory()).isEqualTo(TriageCategory.REVISAO_HUMANA);
        assertThat(triage.getPublicationStatus()).isEqualTo(PublicationStatus.SKIPPED_REVIEW);
        assertThat(triage.getReviewReason()).isEqualTo(DeterministicGuards.UNKNOWN_REPOSITORY);
        assertThat(triage.getLastEventId()).isEqualTo(event.eventId());
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @Test
    void descricaoVaziaVaiParaRevisaoHumana() {
        IssueEventMessage semDescricao = event(KNOWN_REPO, null);
        IssueEventMessage emBranco = event(KNOWN_REPO, "   ");

        publish(semDescricao);
        publish(emBranco);

        assertThat(awaitTriage(semDescricao).getReviewReason()).isEqualTo(DeterministicGuards.INSUFFICIENT_CONTENT);
        assertThat(awaitTriage(emBranco).getReviewReason()).isEqualTo(DeterministicGuards.INSUFFICIENT_CONTENT);
    }

    @Test
    void eventoTriadoPelaIAEPublicadoComMarcadorDoEvento() {
        IssueEventMessage event = event(KNOWN_REPO, "Ao enviar o formulario de login, recebo erro 500.");

        publish(event);

        IssueTriage triage = awaitTriage(event);
        assertThat(triage.getCategory()).isEqualTo(TriageCategory.BUG);
        assertThat(triage.getSpecialist()).isEqualTo(Specialist.BUG_INVESTIGATOR);
        assertThat(triage.getSummary()).isEqualTo("Login retorna erro 500.");
        assertThat(triage.getNextStep()).contains("billing-team");
        assertThat(triage.getReviewReason()).isNull();
        assertThat(triage.getPublicationStatus()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(triage.getExternalCommentId()).isEqualTo(987L);
        assertThat(triage.getExternalCommentUrl()).endsWith("#issuecomment-987");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(eq(KNOWN_REPO), eq(event.issueNumber()), eq(event.eventId()), body.capture());
        assertThat(body.getValue())
                .contains("**Categoria:** Bug")
                .contains("Login retorna erro 500.")
                .contains(TriageComment.marker(event.eventId()));
    }

    @Test
    void revisaoHumanaNuncaPublicaComentario() {
        IssueEventMessage event = event("equipe/nao-cadastrado", "Erro 500 no login");

        publish(event);
        awaitTriage(event);

        verify(publisher, never()).publish(any(), anyInt(), any(), any());
    }

    @Test
    void falhaNaPublicacaoGravaFailedELiberaOEventoParaNovaTentativa() {
        IssueEventMessage event = event(KNOWN_REPO, "Ao enviar o formulario de login, recebo erro 500.");
        when(publisher.publish(any(), anyInt(), eq(event.eventId()), any()))
                .thenThrow(new PublicationException("GitHub respondeu HTTP 503: indisponivel"))
                .thenReturn(new PublishedComment(988L, "https://github.com/x/y/issues/1#issuecomment-988", false));

        publish(event);

        IssueTriage failed = awaitTriage(event, PublicationStatus.FAILED);
        assertThat(failed.getPublicationError()).contains("HTTP 503");
        assertThat(failed.getExternalCommentUrl()).isNull();
        assertThat(processedCount(event.eventId())).isZero();

        // Mesmo eventId reenviado: como nada foi publicado, e processado de novo e agora publica.
        publish(event);

        IssueTriage published = awaitTriage(event, PublicationStatus.PUBLISHED);
        assertThat(published.getExternalCommentId()).isEqualTo(988L);
        assertThat(published.getPublicationError()).isNull();
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @Test
    void mesmoEventIdPelaFilaEhProcessadoUmaUnicaVez() {
        IssueEventMessage event = event(KNOWN_REPO, "descricao");

        publish(event);
        IssueTriage first = awaitTriage(event);

        // Reentrega (ex.: reenvio do cliente ou redelivery do broker) com conteudo diferente:
        // se fosse reprocessada, o reviewReason mudaria para "repositorio desconhecido".
        IssueEventMessage redelivery = new IssueEventMessage(event.eventId(), event.type(), event.occurredAt(),
                event.repository(), event.issueNumber(), event.title(), null);
        publish(redelivery);
        awaitQueueDrained();

        IssueTriage after = triageRepository.findByRepositoryAndIssueNumber(KNOWN_REPO, event.issueNumber()).orElseThrow();
        assertThat(after.getReviewReason()).isEqualTo(first.getReviewReason());
        assertThat(after.getUpdatedAt()).isEqualTo(first.getUpdatedAt());
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @Test
    void doisConsumidoresSimultaneosComOMesmoEventIdSoUmProcessa() throws Exception {
        IssueEventMessage event = event(KNOWN_REPO, "descricao");
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<TriageService.Outcome>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<TriageService.Outcome> call = () -> {
                    start.await();
                    return triageService.process(event);
                };
                results.add(pool.submit(call));
            }
            start.countDown();

            List<TriageService.Outcome> outcomes = new ArrayList<>();
            for (Future<TriageService.Outcome> f : results) {
                outcomes.add(f.get());
            }
            assertThat(outcomes).containsOnlyOnce(TriageService.Outcome.PROCESSED);
            assertThat(outcomes).filteredOn(o -> o == TriageService.Outcome.DUPLICATE).hasSize(threads - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @Test
    void mensagemInvalidaVaiParaDlqSemTravarAFila() {
        MessageProperties mp = new MessageProperties();
        mp.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(props.exchange(), props.routingKey(),
                new Message("isto nao e json".getBytes(StandardCharsets.UTF_8), mp));

        Message dead = await(() -> rabbitTemplate.receive(props.deadLetterQueue()), "mensagem na DLQ");
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo("isto nao e json");

        // A fila continua andando depois da mensagem envenenada.
        IssueEventMessage next = event(KNOWN_REPO, "descricao");
        publish(next);
        assertThat(awaitTriage(next)).isNotNull();
    }

    // --- helpers ---

    private IssueEventMessage event(String repository, String description) {
        int issueNumber = ThreadLocalRandom.current().nextInt(1_000_000, 2_000_000_000);
        usedIssueNumbers.add(issueNumber);
        return new IssueEventMessage("evt-wtest-" + UUID.randomUUID(), "issue.created", Instant.now(),
                repository, issueNumber, "titulo", description);
    }

    private void publish(IssueEventMessage event) {
        rabbitTemplate.convertAndSend(props.exchange(), props.routingKey(), event);
    }

    private IssueTriage awaitTriage(IssueEventMessage event) {
        return awaitTriage(event, null);
    }

    private IssueTriage awaitTriage(IssueEventMessage event, PublicationStatus status) {
        return await(() -> triageRepository
                        .findByRepositoryAndIssueNumber(event.repository(), event.issueNumber())
                        .filter(t -> event.eventId().equals(t.getLastEventId()))
                        .filter(t -> status == null || status == t.getPublicationStatus())
                        .orElse(null),
                "triagem do evento " + event.eventId() + (status == null ? "" : " com status " + status));
    }

    private void awaitQueueDrained() {
        await(() -> {
            var info = amqpAdmin.getQueueInfo(props.queue());
            return info != null && info.getMessageCount() == 0 ? Boolean.TRUE : null;
        }, "fila vazia");
        // getMessageCount nao conta a mensagem em processamento (unacked): da tempo de ela terminar.
        sleep(Duration.ofMillis(500));
    }

    private Integer processedCount(String eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
    }

    private static <T> T await(Supplier<T> probe, String what) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            T value = probe.get();
            if (value != null) {
                return value;
            }
            sleep(Duration.ofMillis(100));
        }
        throw new AssertionError("Timeout esperando: " + what);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
