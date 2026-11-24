package org.example.kipper_academy.ingestion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.example.kipper_academy.api.IssueTriageResponse;
import org.example.kipper_academy.persistence.IssueTriageRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Consulta o resultado da triagem de uma issue (SPEC 7). Fica no processo ingestion, que e a API
 * publica; so le o banco que o worker preenche. 404 enquanto o worker ainda nao processou a issue.
 */
@RestController
@Profile("ingestion")
@RequestMapping("/api/issues")
@RequiredArgsConstructor
public class IssueQueryController {

    private final IssueTriageRepository triageRepository;

    @GetMapping
    public IssueTriageResponse find(@RequestParam @NotBlank String repository,
                                    @RequestParam @Positive Integer issueNumber) {
        return triageRepository.findByRepositoryAndIssueNumber(repository, issueNumber)
                .map(IssueTriageResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Issue " + repository + "#" + issueNumber + " ainda nao foi processada."));
    }
}
