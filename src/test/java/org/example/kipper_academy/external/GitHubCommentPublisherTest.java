package org.example.kipper_academy.external;

import org.example.kipper_academy.external.GitHubCommentPublisher.PublishedComment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Publicacao no GitHub contra um servidor HTTP simulado (nenhuma chamada real). */
class GitHubCommentPublisherTest {

    private static final String REPO = "bot/projeto_teste";
    private static final String COMMENTS = "https://api.github.test/repos/bot/projeto_teste/issues/2/comments";
    private static final String LIST = COMMENTS + "?per_page=100&page=1";

    private MockRestServiceServer server;
    private GitHubCommentPublisher publisher;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        publisher = new GitHubCommentPublisher(builder,
                new GitHubProperties("token-de-teste", REPO, "https://api.github.test", Duration.ofSeconds(5)));
    }

    @Test
    void publicaQuandoAindaNaoHaComentarioDoEvento() {
        server.expect(requestTo(LIST)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-de-teste"))
                .andRespond(withSuccess("""
                        [{"id": 1, "html_url": "https://github.com/x#1", "body": "comentario de uma pessoa"}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(COMMENTS)).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("kipper-triage:event=evt-1")))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"id": 987, "html_url": "https://github.com/bot/projeto_teste/issues/2#issuecomment-987"}
                        """));

        PublishedComment comment = publisher.publish(REPO, 2, "evt-1",
                "texto\n" + TriageComment.marker("evt-1"));

        assertThat(comment.id()).isEqualTo(987);
        assertThat(comment.url()).endsWith("#issuecomment-987");
        assertThat(comment.reused()).isFalse();
        server.verify();
    }

    @Test
    void reaproveitaComentarioExistenteDoMesmoEventoSemPublicarDeNovo() {
        server.expect(requestTo(LIST)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"id": 555, "html_url": "https://github.com/x#555",
                          "body": "### Triagem\\n<!-- kipper-triage:event=evt-1 -->"}]
                        """, MediaType.APPLICATION_JSON));
        // Nenhum POST esperado: se o publisher tentasse publicar, verify() falharia.

        PublishedComment comment = publisher.publish(REPO, 2, "evt-1", "texto");

        assertThat(comment.id()).isEqualTo(555);
        assertThat(comment.reused()).isTrue();
        server.verify();
    }

    @Test
    void recusaRepositorioForaDaAllowlistSemChamarOGitHub() {
        assertThatThrownBy(() -> publisher.publish("outra-conta/outro-repo", 1, "evt-1", "texto"))
                .isInstanceOf(PublicationException.class)
                .hasMessageContaining("nao autorizado");
        server.verify();
    }

    @Test
    void erroDoGitHubViraPublicationException() {
        server.expect(requestTo(LIST)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(COMMENTS)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\": \"Resource not accessible by personal access token\"}"));

        assertThatThrownBy(() -> publisher.publish(REPO, 2, "evt-1", "texto"))
                .isInstanceOf(PublicationException.class)
                .hasMessageContaining("HTTP 403")
                .hasMessageContaining("not accessible");
    }
}
