package org.example.kipper_academy.harness;

import com.anthropic.errors.AnthropicException;
import lombok.extern.slf4j.Slf4j;
import org.example.kipper_academy.domain.TriageCategory;
import org.example.kipper_academy.harness.ai.AiResponseException;
import org.example.kipper_academy.harness.specialist.SpecialistAgent;
import org.example.kipper_academy.messaging.IssueEventMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Harness da triagem (SPEC 6): decide QUANDO chamar a IA, PARA QUEM encaminhar e VALIDA o
 * resultado antes de qualquer acao externa. O texto do evento e tratado como dado, nunca como
 * instrucao. Toda saida invalida ou falha vira REVISAO_HUMANA: nada invalido segue para publicacao.
 *
 * <pre>
 * guardas deterministicas ──(bloqueou)──▶ REVISAO_HUMANA (sem IA)
 *        │ ok
 *        ▼
 * roteador (IA) ──(INDEFINIDO / invalido)──▶ REVISAO_HUMANA
 *        │ BUG / MELHORIA / DUVIDA
 *        ▼
 * especialista (IA + ferramenta de catalogo) ──(resposta invalida)──▶ REVISAO_HUMANA
 *        │
 *        ▼
 * validacao (6.4) ──▶ resultado aceito
 * </pre>
 */
@Slf4j
@Component
@Profile("worker")
public class TriageHarness {

    static final String NOT_CONFIDENT = "IA nao classificou com confianca";
    static final String INVALID_PROPOSAL = "resposta da IA invalida";
    static final String AI_UNAVAILABLE = "falha ao chamar a IA";

    /** Limite por campo: o resultado vira comentario no GitHub; texto gigante indica resposta fora do esperado. */
    static final int MAX_FIELD_LENGTH = 1_500;

    private final DeterministicGuards guards;
    private final TriageRouter router;
    private final List<SpecialistAgent> specialists;

    public TriageHarness(DeterministicGuards guards, TriageRouter router, List<SpecialistAgent> specialists) {
        this.guards = guards;
        this.router = router;
        this.specialists = specialists;
    }

    public TriageResult analyze(IssueEventMessage event) {
        Optional<String> blocked = guards.check(event);
        if (blocked.isPresent()) {
            log.info("Evento {}: guarda deterministica -> REVISAO_HUMANA ({}); IA NAO chamada",
                    event.eventId(), blocked.get());
            return TriageResult.humanReview(blocked.get());
        }

        try {
            TriageRouter.Decision decision = router.classify(event);
            Optional<TriageCategory> category = toCategory(decision);
            if (category.isEmpty()) {
                return review(event, NOT_CONFIDENT + (decision == null ? "" : " (" + decision.justificativa() + ")"));
            }
            log.info("Evento {}: roteador -> {} ({})", event.eventId(), category.get(), decision.justificativa());

            SpecialistAgent specialist = specialists.stream()
                    .filter(s -> s.handles(category.get()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("nenhum especialista para " + category.get()));
            SpecialistAgent.Proposal proposal = specialist.analyze(event, category.get());

            Optional<String> invalid = validate(proposal);
            if (invalid.isPresent()) {
                return review(event, INVALID_PROPOSAL + ": " + invalid.get());
            }
            log.info("Evento {}: {} aceito pelo harness", event.eventId(), specialist.id());
            return new TriageResult(category.get(), proposal.resumo().strip(), proposal.proximoPasso().strip(),
                    specialist.id(), null);

        } catch (AiResponseException e) {
            return review(event, INVALID_PROPOSAL + ": " + e.getMessage());
        } catch (AnthropicException e) {
            // Rede, chave invalida, rate limit apos os retries do SDK, etc.
            log.warn("Evento {}: erro na API da IA", event.eventId(), e);
            return review(event, AI_UNAVAILABLE + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** SPEC 6.4: categoria aceita e campos obrigatorios preenchidos. */
    static Optional<String> validate(SpecialistAgent.Proposal p) {
        if (p == null) {
            return Optional.of("proposta ausente");
        }
        if (p.resumo() == null || p.resumo().isBlank()) {
            return Optional.of("resumo vazio");
        }
        if (p.proximoPasso() == null || p.proximoPasso().isBlank()) {
            return Optional.of("proximo passo vazio");
        }
        if (p.resumo().length() > MAX_FIELD_LENGTH || p.proximoPasso().length() > MAX_FIELD_LENGTH) {
            return Optional.of("texto acima de " + MAX_FIELD_LENGTH + " caracteres");
        }
        return Optional.empty();
    }

    private static Optional<TriageCategory> toCategory(TriageRouter.Decision decision) {
        if (decision == null || decision.categoria() == null) {
            return Optional.empty();
        }
        return switch (decision.categoria()) {
            case BUG -> Optional.of(TriageCategory.BUG);
            case MELHORIA -> Optional.of(TriageCategory.MELHORIA);
            case DUVIDA -> Optional.of(TriageCategory.DUVIDA);
            case INDEFINIDO -> Optional.empty();
        };
    }

    private static TriageResult review(IssueEventMessage event, String reason) {
        log.info("Evento {}: harness -> REVISAO_HUMANA ({})", event.eventId(), reason);
        return TriageResult.humanReview(reason);
    }
}
