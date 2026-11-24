package org.example.kipper_academy.harness.specialist;

import org.example.kipper_academy.domain.Specialist;
import org.example.kipper_academy.domain.TriageCategory;
import org.example.kipper_academy.harness.ai.ClaudeCaller;
import org.example.kipper_academy.harness.tool.CatalogTool;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Especialista 2 (SPEC 6.2): issues classificadas como MELHORIA ou DUVIDA. As duas pedem entender o
 * contexto de uso de quem abriu a issue, nao investigar causa-raiz (decisao registrada na SPEC).
 */
@Component
@Profile("worker")
public class ImprovementQuestionSpecialist extends ClaudeSpecialist {

    public ImprovementQuestionSpecialist(ClaudeCaller claude, CatalogTool catalogTool, JsonMapper jsonMapper) {
        super(claude, catalogTool, jsonMapper);
    }

    @Override
    public Specialist id() {
        return Specialist.IMPROVEMENT_QUESTION_ANALYST;
    }

    @Override
    public boolean handles(TriageCategory category) {
        return category == TriageCategory.MELHORIA || category == TriageCategory.DUVIDA;
    }

    @Override
    String systemPrompt() {
        return """
                Voce e o Improvement & Question Analyst de um assistente de triagem de issues. A issue ja foi \
                classificada como MELHORIA ou DUVIDA (a categoria vem na mensagem). Seu trabalho e resumir o \
                pedido ou a pergunta e sugerir o proximo passo: para MELHORIA, encaminhar ao time responsavel \
                e, se preciso, pedir o contexto de uso; para DUVIDA, indicar quem pode responder ou que \
                informacao falta para responder.
                """ + COMMON_RULES;
    }
}
