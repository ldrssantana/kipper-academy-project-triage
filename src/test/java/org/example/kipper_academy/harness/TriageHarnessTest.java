package org.example.kipper_academy.harness;

import com.anthropic.errors.AnthropicIoException;
import org.example.kipper_academy.domain.Specialist;
import org.example.kipper_academy.domain.TriageCategory;
import org.example.kipper_academy.harness.ai.AiResponseException;
import org.example.kipper_academy.harness.specialist.SpecialistAgent;
import org.example.kipper_academy.harness.specialist.SpecialistAgent.Proposal;
import org.example.kipper_academy.messaging.IssueEventMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regras do harness (SPEC 6.1, 6.2, 6.4) sem chamar a IA: roteador e especialistas sao simulados.
 */
class TriageHarnessTest {

    private final DeterministicGuards guards = mock(DeterministicGuards.class);
    private final TriageRouter router = mock(TriageRouter.class);
    private final SpecialistAgent bugInvestigator = mock(SpecialistAgent.class);
    private final SpecialistAgent analyst = mock(SpecialistAgent.class);
    private final TriageHarness harness = new TriageHarness(guards, router, List.of(bugInvestigator, analyst));

    private final IssueEventMessage event = new IssueEventMessage("evt-1", "issue.created", Instant.now(),
            "equipe/projeto-teste", 42, "Erro ao entrar no sistema", "Recebo erro 500 no login.");

    @BeforeEach
    void setUp() {
        when(guards.check(any())).thenReturn(Optional.empty());
        when(bugInvestigator.id()).thenReturn(Specialist.BUG_INVESTIGATOR);
        when(bugInvestigator.handles(TriageCategory.BUG)).thenReturn(true);
        when(analyst.id()).thenReturn(Specialist.IMPROVEMENT_QUESTION_ANALYST);
        when(analyst.handles(TriageCategory.MELHORIA)).thenReturn(true);
        when(analyst.handles(TriageCategory.DUVIDA)).thenReturn(true);
    }

    @Test
    void guardaBloqueadaNaoChamaAIA() {
        when(guards.check(any())).thenReturn(Optional.of(DeterministicGuards.UNKNOWN_REPOSITORY));

        TriageResult result = harness.analyze(event);

        assertThat(result.category()).isEqualTo(TriageCategory.REVISAO_HUMANA);
        assertThat(result.reviewReason()).isEqualTo(DeterministicGuards.UNKNOWN_REPOSITORY);
        verifyNoInteractions(router, bugInvestigator, analyst);
    }

    @Test
    void bugVaiParaOBugInvestigator() {
        route(TriageRouter.Route.BUG);
        when(bugInvestigator.analyze(event, TriageCategory.BUG))
                .thenReturn(new Proposal("  Login retorna erro 500.  ", "Billing: pedir log completo e passos de reproducao."));

        TriageResult result = harness.analyze(event);

        assertThat(result.category()).isEqualTo(TriageCategory.BUG);
        assertThat(result.specialist()).isEqualTo(Specialist.BUG_INVESTIGATOR);
        assertThat(result.summary()).isEqualTo("Login retorna erro 500.");
        assertThat(result.reviewReason()).isNull();
        verify(analyst, never()).analyze(any(), any());
    }

    @Test
    void melhoriaEDuvidaVaoParaOAnalista() {
        for (TriageRouter.Route route : List.of(TriageRouter.Route.MELHORIA, TriageRouter.Route.DUVIDA)) {
            TriageCategory category = TriageCategory.valueOf(route.name());
            route(route);
            when(analyst.analyze(event, category)).thenReturn(new Proposal("resumo", "proximo passo"));

            TriageResult result = harness.analyze(event);

            assertThat(result.category()).isEqualTo(category);
            assertThat(result.specialist()).isEqualTo(Specialist.IMPROVEMENT_QUESTION_ANALYST);
        }
        verify(bugInvestigator, never()).analyze(any(), any());
    }

    @Test
    void roteadorSemConfiancaVaiParaRevisaoHumana() {
        route(TriageRouter.Route.INDEFINIDO);

        TriageResult result = harness.analyze(event);

        assertThat(result.category()).isEqualTo(TriageCategory.REVISAO_HUMANA);
        assertThat(result.reviewReason()).startsWith(TriageHarness.NOT_CONFIDENT);
        verify(bugInvestigator, never()).analyze(any(), any());
        verify(analyst, never()).analyze(any(), any());
    }

    @Test
    void propostaComCampoVazioOuGiganteVaiParaRevisaoHumana() {
        route(TriageRouter.Route.BUG);
        for (Proposal invalid : List.of(
                new Proposal(" ", "proximo"),
                new Proposal("resumo", null),
                new Proposal("x".repeat(TriageHarness.MAX_FIELD_LENGTH + 1), "proximo"))) {
            when(bugInvestigator.analyze(event, TriageCategory.BUG)).thenReturn(invalid);

            TriageResult result = harness.analyze(event);

            assertThat(result.category()).isEqualTo(TriageCategory.REVISAO_HUMANA);
            assertThat(result.reviewReason()).startsWith(TriageHarness.INVALID_PROPOSAL);
        }
    }

    @Test
    void respostaForaDoSchemaOuRecusaVaiParaRevisaoHumana() {
        when(router.classify(event)).thenThrow(new AiResponseException("IA recusou a solicitacao (categoria cyber)"));

        TriageResult result = harness.analyze(event);

        assertThat(result.category()).isEqualTo(TriageCategory.REVISAO_HUMANA);
        assertThat(result.reviewReason()).contains("recusou");
    }

    @Test
    void falhaDeRedeNaIAVaiParaRevisaoHumana() {
        route(TriageRouter.Route.BUG);
        when(bugInvestigator.analyze(event, TriageCategory.BUG)).thenThrow(new AnthropicIoException("timeout", null));

        TriageResult result = harness.analyze(event);

        assertThat(result.category()).isEqualTo(TriageCategory.REVISAO_HUMANA);
        assertThat(result.reviewReason()).startsWith(TriageHarness.AI_UNAVAILABLE);
    }

    private void route(TriageRouter.Route route) {
        when(router.classify(event)).thenReturn(new TriageRouter.Decision(route, "justificativa"));
    }
}
