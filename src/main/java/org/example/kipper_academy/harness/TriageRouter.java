package org.example.kipper_academy.harness;

import com.anthropic.models.messages.OutputConfig;
import lombok.RequiredArgsConstructor;
import org.example.kipper_academy.harness.ai.AiResponseException;
import org.example.kipper_academy.harness.ai.ClaudeCaller;
import org.example.kipper_academy.harness.ai.IssuePrompt;
import org.example.kipper_academy.messaging.IssueEventMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Roteador (SPEC 6.2): uma chamada curta, sem ferramentas, que so classifica a issue.
 * "INDEFINIDO" existe para o modelo poder dizer que nao tem confianca; o harness manda isso
 * para revisao humana em vez de forcar um palpite.
 */
@Component
@Profile("worker")
@RequiredArgsConstructor
public class TriageRouter {

    public enum Route { BUG, MELHORIA, DUVIDA, INDEFINIDO }

    public record Decision(Route categoria, String justificativa) {
    }

    static final String SYSTEM = """
            Voce e o roteador de um assistente de triagem de issues de software. Classifique a issue em \
            exatamente uma categoria:
            - BUG: algo que deveria funcionar e nao funciona (erro, falha, comportamento incorreto).
            - MELHORIA: pedido de funcionalidade nova ou de mudanca em algo que ja funciona.
            - DUVIDA: pergunta sobre como usar ou como algo funciona.
            - INDEFINIDO: o texto nao permite decidir com seguranca entre as anteriores.
            Responda com a categoria e uma justificativa de uma frase, em portugues.
            """ + IssuePrompt.DATA_NOT_INSTRUCTIONS;

    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "categoria", Map.of("type", "string", "enum", List.of("BUG", "MELHORIA", "DUVIDA", "INDEFINIDO")),
                    "justificativa", Map.of("type", "string")),
            "required", List.of("categoria", "justificativa"),
            "additionalProperties", false);

    private final ClaudeCaller claude;
    private final JsonMapper jsonMapper;

    public Decision classify(IssueEventMessage event) {
        String json = claude.run(new ClaudeCaller.Request(
                "router " + event.eventId(), SYSTEM, IssuePrompt.of(event),
                List.of(), (name, input) -> ClaudeCaller.ToolOutcome.error("nenhuma ferramenta disponivel"),
                SCHEMA, OutputConfig.Effort.LOW, 4_000));
        try {
            return jsonMapper.readValue(json, Decision.class);
        } catch (JacksonException e) {
            throw new AiResponseException("resposta do roteador fora do schema", e);
        }
    }
}
