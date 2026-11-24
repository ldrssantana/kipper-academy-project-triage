package org.example.kipper_academy.persistence;

import org.example.kipper_academy.domain.IssueEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface IssueEventRepository extends JpaRepository<IssueEvent, String> {

    /**
     * Grava a auditoria do evento so se o eventId ainda nao existir.
     * INSERT nativo (e nao save()), porque save() com id atribuido faz merge e sobrescreveria
     * em silencio o evento original. Seguro contra requisicoes concorrentes com o mesmo eventId.
     *
     * @return 1 se gravou, 0 se o eventId ja existia
     */
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
            INSERT INTO issue_event
                (event_id, event_type, repository, issue_number, title, description, occurred_at, received_at)
            VALUES
                (:eventId, :eventType, :repository, :issueNumber, :title, :description, :occurredAt, :receivedAt)
            ON CONFLICT (event_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("eventId") String eventId,
                       @Param("eventType") String eventType,
                       @Param("repository") String repository,
                       @Param("issueNumber") Integer issueNumber,
                       @Param("title") String title,
                       @Param("description") String description,
                       @Param("occurredAt") Instant occurredAt,
                       @Param("receivedAt") Instant receivedAt);
}
