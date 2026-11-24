package org.example.kipper_academy.ingestion.webhook;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.kipper_academy.api.EventAcceptedResponse;
import org.example.kipper_academy.api.IssueEventRequest;
import org.example.kipper_academy.ingestion.IngestionService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Recebe eventos REAIS do GitHub (webhook do repositorio de teste) e os coloca no MESMO fluxo de
 * {@code POST /api/events}: auditoria -> fila -> worker. Responde em milissegundos, como o GitHub
 * exige (limite de 10 s), porque a analise acontece depois, no worker (Metodo distribuido 1).
 *
 * <p>Seguranca: so aceita entregas assinadas com o segredo do webhook (HMAC-SHA256). O conteudo da
 * issue continua sendo apenas dado: a assinatura prova a ORIGEM, nao da permissao nenhuma ao texto.
 */
@Slf4j
@RestController
@Profile("ingestion")
@RequestMapping("/api/webhooks/github")
@RequiredArgsConstructor
public class GitHubWebhookController {

    private final GitHubSignatureVerifier signatureVerifier;
    private final IngestionService ingestionService;
    private final JsonMapper jsonMapper;
    private final Validator validator;

    @PostMapping
    public ResponseEntity<?> receive(@RequestHeader("X-GitHub-Event") String event,
                                     @RequestHeader("X-GitHub-Delivery") String deliveryId,
                                     @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
                                     @RequestBody byte[] body) {
        if (!signatureVerifier.isConfigured()) {
            log.warn("Webhook recebido mas GITHUB_WEBHOOK_SECRET nao esta configurado: recusado");
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "Webhook nao configurado neste servidor.");
        }
        if (!signatureVerifier.isValid(body, signature)) {
            log.warn("Webhook {} com assinatura invalida: recusado", deliveryId);
            return problem(HttpStatus.UNAUTHORIZED, "Assinatura do webhook invalida.");
        }

        if ("ping".equals(event)) {
            log.info("Webhook do GitHub conectado (ping {})", deliveryId);
            return ResponseEntity.ok(Map.of("status", "PONG"));
        }
        if (!"issues".equals(event)) {
            return ignored(deliveryId, "evento " + event + " nao e tratado");
        }

        GitHubIssuesPayload payload;
        try {
            payload = jsonMapper.readValue(body, GitHubIssuesPayload.class);
        } catch (JacksonException e) {
            return problem(HttpStatus.BAD_REQUEST, "Payload do GitHub ilegivel.");
        }
        if (!GitHubIssuesPayload.OPENED.equals(payload.action())) {
            return ignored(deliveryId, "acao " + payload.action() + " nao dispara triagem");
        }

        IssueEventRequest request = payload.toEventRequest(deliveryId);
        Set<ConstraintViolation<IssueEventRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            Map<String, String> errors = new TreeMap<>();
            violations.forEach(v -> errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Evento do GitHub invalido.");
            pd.setProperty("errors", errors);
            return ResponseEntity.badRequest().body(pd);
        }

        log.info("Webhook {}: issue {}#{} aberta no GitHub", deliveryId,
                request.data().repository(), request.data().issueNumber());
        ingestionService.receive(request);
        return ResponseEntity.accepted().body(EventAcceptedResponse.received(request.eventId()));
    }

    /** 200 (e nao erro) para eventos que nao interessam: o GitHub nao deve marcar a entrega como falha. */
    private static ResponseEntity<?> ignored(String deliveryId, String reason) {
        log.info("Webhook {} ignorado: {}", deliveryId, reason);
        return ResponseEntity.ok(Map.of("status", "IGNORED", "reason", reason));
    }

    private static ResponseEntity<?> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
