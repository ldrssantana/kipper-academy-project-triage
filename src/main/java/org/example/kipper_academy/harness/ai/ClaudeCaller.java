package org.example.kipper_academy.harness.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Executa uma conversa com o Claude sob as regras do harness (SPEC 6.3 / 6.4):
 * <ul>
 *   <li>so as ferramentas passadas pelo chamador ficam visiveis para o modelo;</li>
 *   <li>cada chamada de ferramenta passa pelo {@link ToolExecutor} do chamador (que pode negar);</li>
 *   <li>numero maximo de rodadas de ferramenta ({@code app.ai.max-tool-rounds});</li>
 *   <li>a resposta final e JSON restrito por schema (structured outputs);</li>
 *   <li>recusa, resposta cortada ou ausencia de texto viram {@link AiResponseException}.</li>
 * </ul>
 * Loop manual (e nao o tool runner do SDK) justamente para o harness controlar cada passo.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class ClaudeCaller {

    /** Se o modelo recusar, a API refaz a chamada num modelo adequado a categoria da recusa. */
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final AiProperties props;

    /** Executa uma chamada de ferramenta pedida pelo modelo e devolve o resultado (ou um erro). */
    @FunctionalInterface
    public interface ToolExecutor {
        ToolOutcome execute(String toolName, Map<String, Object> input);
    }

    public record ToolOutcome(String content, boolean error) {
        public static ToolOutcome ok(String content) {
            return new ToolOutcome(content, false);
        }

        public static ToolOutcome error(String content) {
            return new ToolOutcome(content, true);
        }
    }

    /** Pedido de uma conversa: instrucoes fixas, conteudo do usuario, ferramentas e schema da resposta. */
    public record Request(
            String label,
            String system,
            String userContent,
            List<Tool> tools,
            ToolExecutor toolExecutor,
            Map<String, Object> outputSchema,
            OutputConfig.Effort effort,
            long maxTokens) {
    }

    /** @return o texto final (JSON no formato de {@code outputSchema}) */
    public String run(Request req) {
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.ofUser(req.userContent()));

        for (int round = 0; ; round++) {
            Message response = client.messages().create(buildParams(req, messages));
            StopReason stop = response.stopReason().orElse(null);
            log.info("[{}] rodada {}: stop_reason={} tokens in/out={}/{}", req.label(), round,
                    stop == null ? "?" : stop.asString(),
                    response.usage().inputTokens(), response.usage().outputTokens());

            if (StopReason.REFUSAL.equals(stop)) {
                String category = response.stopDetails()
                        .flatMap(d -> d.category())
                        .map(Object::toString)
                        .orElse("?");
                throw new AiResponseException("IA recusou a solicitacao (categoria " + category + ")");
            }
            if (StopReason.MAX_TOKENS.equals(stop)) {
                throw new AiResponseException("resposta da IA cortada pelo limite de tokens");
            }
            if (StopReason.TOOL_USE.equals(stop)) {
                if (round >= props.maxToolRounds()) {
                    throw new AiResponseException(
                            "IA excedeu o limite de " + props.maxToolRounds() + " rodadas de ferramenta");
                }
                messages.add(response.toParam());
                messages.add(MessageParam.ofUserOfContentBlockParams(runTools(req, response)));
                continue;
            }

            String text = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(TextBlock::text)
                    .collect(Collectors.joining());
            if (text.isBlank()) {
                throw new AiResponseException("IA nao devolveu texto (stop_reason=" + stop + ")");
            }
            return text;
        }
    }

    private List<ContentBlockParam> runTools(Request req, Message response) {
        List<ContentBlockParam> results = new ArrayList<>();
        for (var block : response.content()) {
            if (block.toolUse().isEmpty()) {
                continue;
            }
            ToolUseBlock call = block.toolUse().get();
            ToolOutcome outcome;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> input = call._input().convert(Map.class);
                outcome = req.toolExecutor().execute(call.name(), input);
            } catch (RuntimeException e) {
                outcome = ToolOutcome.error("falha ao executar a ferramenta: " + e.getMessage());
            }
            log.info("[{}] ferramenta {} -> {}{}", req.label(), call.name(),
                    outcome.error() ? "ERRO " : "", outcome.content());
            // Todo tool_use precisa de um tool_result correspondente, inclusive quando negado.
            results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(call.id())
                    .content(outcome.content())
                    .isError(outcome.error())
                    .build()));
        }
        return results;
    }

    private MessageCreateParams buildParams(Request req, List<MessageParam> messages) {
        // Thinking omitido: no Opus 5.5 ele e sempre adaptativo; a profundidade e controlada por effort.
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(props.model())
                .maxTokens(req.maxTokens())
                .system(req.system())
                .messages(messages)
                .outputConfig(OutputConfig.builder()
                        .effort(req.effort())
                        .format(JsonOutputFormat.builder()
                                .schema(JsonOutputFormat.Schema.builder()
                                        .putAllAdditionalProperties(toJsonValues(req.outputSchema()))
                                        .build())
                                .build())
                        .build())
                .putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        req.tools().forEach(builder::addTool);
        return builder.build();
    }

    private static Map<String, JsonValue> toJsonValues(Map<String, Object> schema) {
        return schema.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> JsonValue.from(e.getValue())));
    }
}
