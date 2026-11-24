package org.example.kipper_academy.external;

import org.example.kipper_academy.domain.Specialist;
import org.example.kipper_academy.domain.TriageCategory;
import org.example.kipper_academy.harness.TriageResult;

/** Texto (Markdown) do comentario de triagem publicado na issue. */
public final class TriageComment {

    private TriageComment() {
    }

    /**
     * Marcador oculto (comentario HTML, invisivel na pagina) que identifica o evento de origem.
     * Permite descobrir se o comentario de um evento ja foi publicado, mesmo que o banco nao saiba.
     */
    public static String marker(String eventId) {
        return "<!-- kipper-triage:event=" + eventId + " -->";
    }

    public static String render(String eventId, TriageResult result) {
        return """
                ### Triagem automatica

                **Categoria:** %s
                **Especialista:** %s

                **Resumo**
                %s

                **Proximo passo sugerido**
                %s

                <sub>Gerado pelo Assistente de Manutencao a partir do evento `%s`. Sugestao automatica: revise antes de agir.</sub>
                %s""".formatted(
                label(result.category()), label(result.specialist()),
                result.summary(), result.nextStep(),
                eventId, marker(eventId));
    }

    private static String label(TriageCategory category) {
        return switch (category) {
            case BUG -> "Bug";
            case MELHORIA -> "Melhoria";
            case DUVIDA -> "Duvida";
            case REVISAO_HUMANA -> "Revisao humana";
        };
    }

    private static String label(Specialist specialist) {
        return switch (specialist) {
            case BUG_INVESTIGATOR -> "Bug Investigator";
            case IMPROVEMENT_QUESTION_ANALYST -> "Improvement & Question Analyst";
        };
    }
}
