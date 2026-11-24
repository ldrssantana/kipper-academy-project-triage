package org.example.kipper_academy.harness;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.kipper_academy.messaging.IssueEventMessage;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Harness da triagem (SPEC 6): decide QUANDO chamar a IA e valida o resultado antes de
 * qualquer acao externa. O texto do evento e tratado como dado, nunca como instrucao.
 *
 * <p>Passo 4: so as guardas deterministicas. Todo evento que passa por elas tambem vai para
 * revisao humana, ate o roteador e os especialistas de IA entrarem no passo 5.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriageHarness {

    static final String AI_NOT_ENABLED = "analise de IA ainda nao habilitada";

    private final DeterministicGuards guards;

    public TriageResult analyze(IssueEventMessage event) {
        Optional<String> blocked = guards.check(event);
        if (blocked.isPresent()) {
            log.info("Evento {}: guarda deterministica -> REVISAO_HUMANA ({}); IA NAO chamada",
                    event.eventId(), blocked.get());
            return TriageResult.humanReview(blocked.get());
        }
        return TriageResult.humanReview(AI_NOT_ENABLED);
    }
}
