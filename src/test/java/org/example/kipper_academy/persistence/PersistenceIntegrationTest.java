package org.example.kipper_academy.persistence;

import jakarta.persistence.EntityManager;
import org.example.kipper_academy.domain.IssueTriage;
import org.example.kipper_academy.domain.ProcessedEvent;
import org.example.kipper_academy.domain.PublicationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Valida o passo 2 da SPEC: schema.sql + data.sql aplicados, entidades batendo com o schema
 * (ddl-auto=validate) e a constraint que sustenta a idempotencia.
 * Roda contra o Postgres do docker-compose; cada teste faz rollback.
 */
@SpringBootTest
@Transactional
class PersistenceIntegrationTest {

    @Autowired
    ProjectCatalogRepository catalogRepository;

    @Autowired
    IssueTriageRepository triageRepository;

    @Autowired
    EntityManager entityManager;

    @Test
    void catalogoFicticioEstaSemeado() {
        assertThat(catalogRepository.findById("equipe/projeto-teste"))
                .hasValueSatisfying(p -> {
                    assertThat(p.getResponsible()).contains("billing-team");
                    assertThat(p.getTriageGuidance()).contains("log completo");
                });
        assertThat(catalogRepository.findById("equipe/inexistente")).isEmpty();
    }

    @Test
    void mesmoEventIdNaoPodeSerRegistradoDuasVezesComoProcessado() {
        entityManager.persist(new ProcessedEvent("evt-test-dup", Instant.now()));
        entityManager.flush();

        // persist (e nao save/merge): um segundo registro do mesmo eventId tem que ser rejeitado.
        assertThatThrownBy(() -> {
            entityManager.persist(new ProcessedEvent("evt-test-dup", Instant.now()));
            entityManager.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    void triagemEhEncontradaPorRepositorioENumero() {
        IssueTriage triage = new IssueTriage("equipe/projeto-teste", 4242);
        triage.setLastEventId("evt-test-1");
        triage.setPublicationStatus(PublicationStatus.PENDING);
        triageRepository.saveAndFlush(triage);

        assertThat(triageRepository.findByRepositoryAndIssueNumber("equipe/projeto-teste", 4242))
                .hasValueSatisfying(t -> {
                    assertThat(t.getPublicationStatus()).isEqualTo(PublicationStatus.PENDING);
                    assertThat(t.getCreatedAt()).isNotNull();
                });
    }
}
