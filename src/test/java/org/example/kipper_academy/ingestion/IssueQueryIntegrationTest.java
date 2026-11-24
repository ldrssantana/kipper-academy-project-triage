package org.example.kipper_academy.ingestion;

import org.example.kipper_academy.domain.IssueTriage;
import org.example.kipper_academy.domain.PublicationStatus;
import org.example.kipper_academy.domain.Specialist;
import org.example.kipper_academy.domain.TriageCategory;
import org.example.kipper_academy.persistence.IssueTriageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Passo 7 da SPEC: GET /api/issues devolve o resultado gravado pelo worker. Banco faz rollback. */
@SpringBootTest(properties = {
        "app.messaging.exchange=kipper.test.issue-events",
        "app.messaging.queue=kipper.test.issue-events.triage",
        "app.messaging.dead-letter-queue=kipper.test.issue-events.triage.dlq"
})
@AutoConfigureMockMvc
@ActiveProfiles("ingestion")
@Transactional
class IssueQueryIntegrationTest {

    private static final String REPO = "equipe/projeto-teste";
    private static final int ISSUE = 1_999_001;

    @Autowired
    MockMvc mvc;

    @Autowired
    IssueTriageRepository triageRepository;

    @Test
    void issueTriadaEPublicadaRetorna200ComOResultado() throws Exception {
        IssueTriage triage = new IssueTriage(REPO, ISSUE);
        triage.setLastEventId("evt-q-1");
        triage.setCategory(TriageCategory.BUG);
        triage.setSpecialist(Specialist.BUG_INVESTIGATOR);
        triage.setSummary("Login retorna erro 500.");
        triage.setNextStep("Time Billing: pedir log completo.");
        triage.setPublicationStatus(PublicationStatus.PUBLISHED);
        triage.setExternalCommentId(987L);
        triage.setExternalCommentUrl("https://github.com/x/y/issues/1#issuecomment-987");
        triageRepository.saveAndFlush(triage);

        mvc.perform(get("/api/issues").param("repository", REPO).param("issueNumber", String.valueOf(ISSUE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repository").value(REPO))
                .andExpect(jsonPath("$.issueNumber").value(ISSUE))
                .andExpect(jsonPath("$.category").value("BUG"))
                .andExpect(jsonPath("$.specialist").value("BUG_INVESTIGATOR"))
                .andExpect(jsonPath("$.summary").value("Login retorna erro 500."))
                .andExpect(jsonPath("$.nextStep").value("Time Billing: pedir log completo."))
                .andExpect(jsonPath("$.publicationStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$.externalCommentUrl").value("https://github.com/x/y/issues/1#issuecomment-987"))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void revisaoHumanaMostraOMotivo() throws Exception {
        IssueTriage triage = new IssueTriage("equipe/desconhecido", ISSUE);
        triage.setLastEventId("evt-q-2");
        triage.setCategory(TriageCategory.REVISAO_HUMANA);
        triage.setReviewReason("repositorio desconhecido");
        triage.setPublicationStatus(PublicationStatus.SKIPPED_REVIEW);
        triageRepository.saveAndFlush(triage);

        mvc.perform(get("/api/issues").param("repository", "equipe/desconhecido").param("issueNumber", String.valueOf(ISSUE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("REVISAO_HUMANA"))
                .andExpect(jsonPath("$.reviewReason").value("repositorio desconhecido"))
                .andExpect(jsonPath("$.publicationStatus").value("SKIPPED_REVIEW"))
                .andExpect(jsonPath("$.externalCommentUrl").doesNotExist());
    }

    @Test
    void issueNuncaProcessadaRetorna404() throws Exception {
        mvc.perform(get("/api/issues").param("repository", REPO).param("issueNumber", "1999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Issue " + REPO + "#1999999 ainda nao foi processada."));
    }

    @Test
    void parametroAusenteOuInvalidoRetorna400() throws Exception {
        mvc.perform(get("/api/issues").param("repository", REPO))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/issues").param("repository", REPO).param("issueNumber", "abc"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/issues").param("repository", REPO).param("issueNumber", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/issues").param("repository", " ").param("issueNumber", "1"))
                .andExpect(status().isBadRequest());
    }
}
