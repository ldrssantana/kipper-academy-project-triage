package org.example.kipper_academy.harness.tool;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import lombok.RequiredArgsConstructor;
import org.example.kipper_academy.harness.ai.ClaudeCaller.ToolOutcome;
import org.example.kipper_academy.persistence.ProjectCatalogRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Ferramenta controlada (SPEC 6.3): a UNICA coisa que o modelo pode acessar.
 * Le somente a tabela project_catalog e somente o repositorio do evento em analise:
 * se o modelo pedir outro repositorio, a chamada e negada.
 */
@Component
@RequiredArgsConstructor
public class CatalogTool {

    public static final String NAME = "consultarCatalogoProjeto";

    private final ProjectCatalogRepository catalogRepository;
    private final JsonMapper jsonMapper;

    public Tool definition() {
        return Tool.builder()
                .name(NAME)
                .description("""
                        Consulta o catalogo de dominio do repositorio da issue em analise. Devolve o time \
                        responsavel (responsible) e as orientacoes de triagem do projeto (triageGuidance). \
                        Use antes de sugerir o proximo passo. So aceita o repositorio da issue atual.""")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("repository", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Repositorio no formato dono/nome, ex.: equipe/projeto-teste")))
                                .build())
                        .required(List.of("repository"))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .strict(true)
                .build();
    }

    /** @param allowedRepository o repositorio do evento; qualquer outro e negado */
    public ToolOutcome execute(String toolName, Map<String, Object> input, String allowedRepository) {
        if (!NAME.equals(toolName)) {
            return ToolOutcome.error("ferramenta desconhecida: " + toolName);
        }
        Object requested = input.get("repository");
        if (!allowedRepository.equals(requested)) {
            return ToolOutcome.error("acesso negado: so e permitido consultar o repositorio da issue (" + allowedRepository + ")");
        }
        return catalogRepository.findById(allowedRepository)
                .map(p -> ToolOutcome.ok(jsonMapper.writeValueAsString(Map.of(
                        "repository", p.getRepository(),
                        "responsible", p.getResponsible(),
                        "triageGuidance", p.getTriageGuidance()))))
                .orElseGet(() -> ToolOutcome.error("repositorio nao encontrado no catalogo"));
    }
}
