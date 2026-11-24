package org.example.kipper_academy.external;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adaptador de saida: publica o comentario de triagem na issue de origem via GitHub REST API
 * ({@code POST /repos/{owner}/{repo}/issues/{n}/comments}).
 *
 * <p>Duas protecoes alem da idempotencia do banco:
 * <ul>
 *   <li><b>Allowlist:</b> so comenta no repositorio configurado em {@code GITHUB_TEST_REPO}.</li>
 *   <li><b>Marcador:</b> antes de publicar, procura nos comentarios da issue o marcador oculto do
 *   eventId. Se achar, reaproveita o comentario existente em vez de publicar outro. Isso fecha a
 *   janela "publicou no GitHub mas o commit no banco falhou" (a reentrega nao duplica).</li>
 * </ul>
 */
@Slf4j
@Component
@Profile("worker")
@EnableConfigurationProperties(GitHubProperties.class)
public class GitHubCommentPublisher {

    private static final int PAGE_SIZE = 100;

    public record PublishedComment(long id, String url, boolean reused) {
    }

    private record CommentDto(Long id, String html_url, String body) {
    }

    private final RestClient http;
    private final GitHubProperties props;

    @Autowired
    public GitHubCommentPublisher(GitHubProperties props) {
        this(RestClient.builder().requestFactory(requestFactory(props)), props);
    }

    /** Usado nos testes, com um builder ligado a um servidor HTTP simulado. */
    GitHubCommentPublisher(RestClient.Builder builder, GitHubProperties props) {
        this.props = props;
        this.http = builder
                .baseUrl(props.apiUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + (props.token() == null ? "" : props.token()))
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.USER_AGENT, "kipper-academy-triage")
                .build();
    }

    public PublishedComment publish(String repository, int issueNumber, String eventId, String body) {
        if (props.allowedRepository() == null || !props.allowedRepository().equals(repository)) {
            throw new PublicationException("repositorio " + repository
                    + " nao autorizado para publicacao (permitido: " + props.allowedRepository() + ")");
        }
        try {
            Optional<PublishedComment> existing = findByMarker(repository, issueNumber, eventId);
            if (existing.isPresent()) {
                log.info("Comentario do evento {} ja existia em {}#{}: reaproveitado ({})",
                        eventId, repository, issueNumber, existing.get().url());
                return existing.get();
            }
            CommentDto created = http.post()
                    .uri("/repos/{owner}/{name}/issues/{n}/comments", uriVars(repository, issueNumber, Map.of()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("body", body))
                    .retrieve()
                    .body(CommentDto.class);
            if (created == null || created.id() == null) {
                throw new PublicationException("GitHub nao devolveu o comentario criado");
            }
            log.info("Comentario publicado em {}#{}: {}", repository, issueNumber, created.html_url());
            return new PublishedComment(created.id(), created.html_url(), false);
        } catch (RestClientResponseException e) {
            throw new PublicationException("GitHub respondeu HTTP " + e.getStatusCode().value()
                    + ": " + abbreviate(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            throw new PublicationException("falha de comunicacao com o GitHub: " + e.getMessage(), e);
        }
    }

    /** Percorre os comentarios da issue procurando o marcador oculto do evento. */
    private Optional<PublishedComment> findByMarker(String repository, int issueNumber, String eventId) {
        String marker = TriageComment.marker(eventId);
        for (int page = 1; ; page++) {
            List<CommentDto> comments = http.get()
                    .uri("/repos/{owner}/{name}/issues/{n}/comments?per_page={size}&page={page}",
                            uriVars(repository, issueNumber, Map.of("size", PAGE_SIZE, "page", page)))
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<CommentDto>>() { });
            if (comments == null || comments.isEmpty()) {
                return Optional.empty();
            }
            for (CommentDto c : comments) {
                if (c.body() != null && c.body().contains(marker)) {
                    return Optional.of(new PublishedComment(c.id(), c.html_url(), true));
                }
            }
            if (comments.size() < PAGE_SIZE) {
                return Optional.empty();
            }
        }
    }

    /** "dono/nome" vira duas variaveis: uma so seria codificada como dono%2Fnome. */
    private static Map<String, Object> uriVars(String repository, int issueNumber, Map<String, Object> extra) {
        String[] parts = repository.split("/", 2);
        Map<String, Object> vars = new java.util.HashMap<>(extra);
        vars.put("owner", parts[0]);
        vars.put("name", parts.length > 1 ? parts[1] : "");
        vars.put("n", issueNumber);
        return vars;
    }

    private static JdkClientHttpRequestFactory requestFactory(GitHubProperties props) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(props.timeout());
        return factory;
    }

    private static String abbreviate(String text) {
        return text == null ? "" : text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
