package org.example.kipper_academy.harness;

import lombok.RequiredArgsConstructor;
import org.example.kipper_academy.messaging.IssueEventMessage;
import org.example.kipper_academy.persistence.ProjectCatalogRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Guardas deterministicas (SPEC 6.1): rodam ANTES de qualquer chamada a IA.
 * Cobrem literalmente "repositorio desconhecido ou conteudo insuficiente segue para revisao
 * humana", sem depender do modelo decidir certo.
 */
@Component
@RequiredArgsConstructor
public class DeterministicGuards {

    public static final String UNKNOWN_REPOSITORY = "repositorio desconhecido";
    public static final String INSUFFICIENT_CONTENT = "conteudo insuficiente";

    private final ProjectCatalogRepository catalogRepository;

    /** @return o motivo da revisao humana, ou vazio se o evento pode seguir para a IA */
    public Optional<String> check(IssueEventMessage event) {
        if (!catalogRepository.existsById(event.repository())) {
            return Optional.of(UNKNOWN_REPOSITORY);
        }
        if (event.description() == null || event.description().isBlank()) {
            return Optional.of(INSUFFICIENT_CONTENT);
        }
        return Optional.empty();
    }
}
