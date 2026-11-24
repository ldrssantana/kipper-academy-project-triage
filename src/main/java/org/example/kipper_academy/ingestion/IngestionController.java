package org.example.kipper_academy.ingestion;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.kipper_academy.api.EventAcceptedResponse;
import org.example.kipper_academy.api.IssueEventRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Porta de entrada dos eventos de issue (SPEC 7). So existe no processo ingestion.
 * Responde 202 sem esperar a analise: a triagem acontece depois, no processo worker.
 */
@RestController
@Profile("ingestion")
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EventAcceptedResponse receive(@Valid @RequestBody IssueEventRequest request) {
        ingestionService.receive(request);
        return EventAcceptedResponse.received(request.eventId());
    }
}
