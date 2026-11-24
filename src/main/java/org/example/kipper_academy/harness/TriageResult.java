package org.example.kipper_academy.harness;

import org.example.kipper_academy.domain.Specialist;
import org.example.kipper_academy.domain.TriageCategory;

/**
 * Proposta de triagem ja validada pelo harness (pronta para gravar e, no passo 6, publicar).
 *
 * @param reviewReason preenchido so quando a categoria e REVISAO_HUMANA
 */
public record TriageResult(
        TriageCategory category,
        String summary,
        String nextStep,
        Specialist specialist,
        String reviewReason) {

    public static TriageResult humanReview(String reason) {
        return new TriageResult(TriageCategory.REVISAO_HUMANA, null, null, null, reason);
    }

    public boolean requiresHumanReview() {
        return category == TriageCategory.REVISAO_HUMANA;
    }
}
