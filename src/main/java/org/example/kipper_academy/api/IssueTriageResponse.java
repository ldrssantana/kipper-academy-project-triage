package org.example.kipper_academy.api;

import org.example.kipper_academy.domain.IssueTriage;
import org.example.kipper_academy.domain.PublicationStatus;
import org.example.kipper_academy.domain.Specialist;
import org.example.kipper_academy.domain.TriageCategory;

import java.time.Instant;

/**
 * Resposta de {@code GET /api/issues} (SPEC 7). Alem dos campos da SPEC, expoe o motivo da
 * revisao humana e o erro de publicacao, para o resultado ser auditavel sem olhar o banco.
 */
public record IssueTriageResponse(
        String repository,
        Integer issueNumber,
        TriageCategory category,
        String summary,
        String nextStep,
        Specialist specialist,
        String reviewReason,
        PublicationStatus publicationStatus,
        String publicationError,
        String externalCommentUrl,
        String lastEventId,
        Instant updatedAt) {

    public static IssueTriageResponse from(IssueTriage t) {
        return new IssueTriageResponse(
                t.getRepository(), t.getIssueNumber(), t.getCategory(), t.getSummary(), t.getNextStep(),
                t.getSpecialist(), t.getReviewReason(), t.getPublicationStatus(), t.getPublicationError(),
                t.getExternalCommentUrl(), t.getLastEventId(), t.getUpdatedAt());
    }
}
