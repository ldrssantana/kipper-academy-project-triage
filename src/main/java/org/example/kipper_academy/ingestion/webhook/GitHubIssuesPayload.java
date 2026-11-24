package org.example.kipper_academy.ingestion.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.example.kipper_academy.api.IssueEventRequest;

import java.time.Instant;

/**
 * Parte do payload do evento {@code issues} do GitHub que interessa a triagem (o resto e ignorado).
 *
 * <p>Mapeamento para o contrato interno (SPEC 4), documentado no README:
 * <pre>
 * X-GitHub-Delivery     -> eventId  ("gh-" + GUID; o Redeliver do GitHub reusa o mesmo GUID)
 * issues + "opened"     -> type     ("issue.created")
 * issue.created_at      -> occurredAt
 * repository.full_name  -> data.repository
 * issue.number          -> data.issueNumber
 * issue.title           -> data.title
 * issue.body            -> data.description (null quando a issue nao tem descricao)
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubIssuesPayload(String action, Issue issue, Repository repository) {

    public static final String OPENED = "opened";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Issue(Integer number, String title, String body,
                        @JsonProperty("created_at") Instant createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(@JsonProperty("full_name") String fullName) {
    }

    public IssueEventRequest toEventRequest(String deliveryId) {
        Issue i = issue == null ? new Issue(null, null, null, null) : issue;
        return new IssueEventRequest(
                "gh-" + deliveryId,
                IssueEventRequest.DEFAULT_TYPE,
                i.createdAt(),
                new IssueEventRequest.Data(
                        repository == null ? null : repository.fullName(),
                        i.number(),
                        i.title(),
                        i.body()));
    }
}
