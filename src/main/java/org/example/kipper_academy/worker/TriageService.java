package org.example.kipper_academy.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.kipper_academy.domain.IssueTriage;
import org.example.kipper_academy.domain.PublicationStatus;
import org.example.kipper_academy.external.GitHubCommentPublisher;
import org.example.kipper_academy.external.GitHubCommentPublisher.PublishedComment;
import org.example.kipper_academy.external.PublicationException;
import org.example.kipper_academy.external.TriageComment;
import org.example.kipper_academy.harness.TriageHarness;
import org.example.kipper_academy.harness.TriageResult;
import org.example.kipper_academy.messaging.IssueEventMessage;
import org.example.kipper_academy.persistence.IssueTriageRepository;
import org.example.kipper_academy.persistence.ProcessedEventRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Orquestra a triagem de um evento consumido da fila: idempotencia -> harness -> publicacao -> banco.
 *
 * <p>Consumidor idempotente (Metodo distribuido 2, SPEC 3.2): o primeiro passo e reivindicar o
 * eventId em processed_events, NA MESMA transacao que grava o resultado. Se ja foi processado,
 * nada e refeito (nem IA, nem comentario). Se algo inesperado falhar depois da reivindicacao, a
 * transacao inteira e desfeita (inclusive a reivindicacao) e a mensagem pode ser entregue de novo.
 *
 * <p>Falha na publicacao (SPEC 9): nada foi publicado, entao o resultado e gravado como FAILED e a
 * reivindicacao e liberada na mesma transacao: reenviar o mesmo evento tenta de novo, sem duplicar.
 */
@Slf4j
@Service
@Profile("worker")
@RequiredArgsConstructor
public class TriageService {

    public enum Outcome { PROCESSED, DUPLICATE }

    private final ProcessedEventRepository processedEventRepository;
    private final IssueTriageRepository triageRepository;
    private final TriageHarness harness;
    private final GitHubCommentPublisher publisher;

    @Transactional
    public Outcome process(IssueEventMessage event) {
        if (processedEventRepository.claim(event.eventId(), Instant.now()) == 0) {
            log.info("Evento {} ja processado antes: ignorado (nenhuma acao repetida)", event.eventId());
            return Outcome.DUPLICATE;
        }

        TriageResult result = harness.analyze(event);

        IssueTriage triage = triageRepository
                .findByRepositoryAndIssueNumber(event.repository(), event.issueNumber())
                .orElseGet(() -> new IssueTriage(event.repository(), event.issueNumber()));
        triage.setLastEventId(event.eventId());
        triage.setCategory(result.category());
        triage.setSummary(result.summary());
        triage.setNextStep(result.nextStep());
        triage.setSpecialist(result.specialist());
        triage.setReviewReason(result.reviewReason());
        triage.setPublicationError(null);
        triage.setExternalCommentId(null);
        triage.setExternalCommentUrl(null);

        if (result.requiresHumanReview()) {
            // Revisao humana nunca publica comentario (SPEC 6.1 / 6.4).
            triage.setPublicationStatus(PublicationStatus.SKIPPED_REVIEW);
        } else {
            publish(event, result, triage);
        }
        triageRepository.save(triage);

        log.info("Evento {} ({}#{}) triado: {} / {}{}", event.eventId(), event.repository(),
                event.issueNumber(), result.category(), triage.getPublicationStatus(),
                result.reviewReason() == null ? "" : " (" + result.reviewReason() + ")");
        return Outcome.PROCESSED;
    }

    private void publish(IssueEventMessage event, TriageResult result, IssueTriage triage) {
        try {
            PublishedComment comment = publisher.publish(event.repository(), event.issueNumber(),
                    event.eventId(), TriageComment.render(event.eventId(), result));
            triage.setPublicationStatus(PublicationStatus.PUBLISHED);
            triage.setExternalCommentId(comment.id());
            triage.setExternalCommentUrl(comment.url());
        } catch (PublicationException e) {
            log.warn("Evento {}: publicacao falhou ({}); reivindicacao liberada para nova tentativa",
                    event.eventId(), e.getMessage());
            triage.setPublicationStatus(PublicationStatus.FAILED);
            triage.setPublicationError(e.getMessage());
            processedEventRepository.release(event.eventId());
        }
    }
}
