package org.example.kipper_academy.harness;

import org.example.kipper_academy.domain.Specialist;
import org.example.kipper_academy.domain.TriageCategory;
import org.example.kipper_academy.messaging.IssueEventMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chama o Claude DE VERDADE (custa alguns centavos). Desligado por padrao; rode com:
 * <pre>./gradlew test --tests '*ClaudeLiveTest' -Plive=true</pre>
 * Valida o criterio de aceite 4 e as duas rotas reais de especialista (SPEC 6.2).
 */
@SpringBootTest(properties = {
        "app.messaging.exchange=kipper.test-live.issue-events",
        "app.messaging.queue=kipper.test-live.issue-events.triage",
        "app.messaging.dead-letter-queue=kipper.test-live.issue-events.triage.dlq"
})
@ActiveProfiles("worker")
@EnabledIfSystemProperty(named = "live", matches = "true")
class ClaudeLiveTest {

    @Autowired
    TriageHarness harness;

    @Test
    void eventoDoDesafioEhBugComProximoPassoDoCatalogo() {
        TriageResult result = harness.analyze(new IssueEventMessage("evt-live-001", "issue.created", Instant.now(),
                "equipe/projeto-teste", 42, "Erro ao entrar no sistema",
                "Ao enviar o formulario de login, recebo erro 500."));

        System.out.println("BUG -> " + result);
        assertThat(result.category()).isEqualTo(TriageCategory.BUG);
        assertThat(result.specialist()).isEqualTo(Specialist.BUG_INVESTIGATOR);
        assertThat(result.summary()).isNotBlank();
        assertThat(result.nextStep()).isNotBlank();
    }

    @Test
    void pedidoDeFuncionalidadeVaiParaOAnalistaDeMelhorias() {
        TriageResult result = harness.analyze(new IssueEventMessage("evt-live-002", "issue.created", Instant.now(),
                "equipe/projeto-mobile", 7, "Modo escuro no app",
                "Seria otimo ter um modo escuro no aplicativo, uso muito a noite."));

        System.out.println("MELHORIA -> " + result);
        assertThat(result.category()).isEqualTo(TriageCategory.MELHORIA);
        assertThat(result.specialist()).isEqualTo(Specialist.IMPROVEMENT_QUESTION_ANALYST);
        assertThat(result.nextStep()).isNotBlank();
    }
}
