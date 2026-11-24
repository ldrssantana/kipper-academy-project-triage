package org.example.kipper_academy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Estado atual da triagem de uma issue; uma linha por (repository, issue_number) (SPEC 8). */
@Entity
@Table(name = "issue_triage",
        uniqueConstraints = @UniqueConstraint(name = "uk_issue_triage_repo_number",
                columnNames = {"repository", "issue_number"}))
@Getter
@Setter
@NoArgsConstructor
public class IssueTriage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String repository;

    @Column(name = "issue_number", nullable = false)
    private Integer issueNumber;

    @Column(name = "last_event_id", nullable = false)
    private String lastEventId;

    @Enumerated(EnumType.STRING)
    private TriageCategory category;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "next_step", columnDefinition = "TEXT")
    private String nextStep;

    @Enumerated(EnumType.STRING)
    private Specialist specialist;

    /** Motivo do encaminhamento para revisao humana (ex.: "repositorio desconhecido"). */
    @Column(name = "review_reason", columnDefinition = "TEXT")
    private String reviewReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "publication_status", nullable = false)
    private PublicationStatus publicationStatus;

    @Column(name = "publication_error", columnDefinition = "TEXT")
    private String publicationError;

    @Column(name = "external_comment_id")
    private Long externalCommentId;

    @Column(name = "external_comment_url")
    private String externalCommentUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public IssueTriage(String repository, Integer issueNumber) {
        this.repository = repository;
        this.issueNumber = issueNumber;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
