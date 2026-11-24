package org.example.kipper_academy.harness.specialist;

import org.example.kipper_academy.domain.Specialist;
import org.example.kipper_academy.domain.TriageCategory;
import org.example.kipper_academy.harness.ai.ClaudeCaller;
import org.example.kipper_academy.harness.tool.CatalogTool;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Especialista 1 (SPEC 6.2): issues classificadas como BUG. */
@Component
@Profile("worker")
public class BugInvestigatorSpecialist extends ClaudeSpecialist {

    public BugInvestigatorSpecialist(ClaudeCaller claude, CatalogTool catalogTool, JsonMapper jsonMapper) {
        super(claude, catalogTool, jsonMapper);
    }

    @Override
    public Specialist id() {
        return Specialist.BUG_INVESTIGATOR;
    }

    @Override
    public boolean handles(TriageCategory category) {
        return category == TriageCategory.BUG;
    }

    @Override
    String systemPrompt() {
        return """
                Voce e o Bug Investigator de um assistente de triagem de issues. A issue ja foi classificada \
                como BUG. Seu trabalho e resumir o problema relatado e sugerir o proximo passo de \
                investigacao (por exemplo: coletar log completo, passos de reproducao, ambiente afetado), \
                sem tentar adivinhar a causa.
                """ + COMMON_RULES;
    }
}
