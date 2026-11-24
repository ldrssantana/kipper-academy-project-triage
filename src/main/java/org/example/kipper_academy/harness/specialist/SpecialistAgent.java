package org.example.kipper_academy.harness.specialist;

import org.example.kipper_academy.domain.Specialist;
import org.example.kipper_academy.domain.TriageCategory;
import org.example.kipper_academy.messaging.IssueEventMessage;

/** Especialista de IA para o qual o roteador encaminha a issue (SPEC 6.2). */
public interface SpecialistAgent {

    Specialist id();

    boolean handles(TriageCategory category);

    /** Proposta ainda NAO validada: quem valida e o TriageHarness. */
    Proposal analyze(IssueEventMessage event, TriageCategory category);

    record Proposal(String resumo, String proximoPasso) {
    }
}
