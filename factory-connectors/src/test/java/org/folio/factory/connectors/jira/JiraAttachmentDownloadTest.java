package org.folio.factory.connectors.jira;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JiraAttachmentDownloadTest {

    private static final byte[] IMAGE = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    private HttpServer server;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/2/attachment/content/1", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.getResponseHeaders().add("Location", "/media/file?token=secret-token");
            exchange.sendResponseHeaders(303, -1);
            exchange.close();
        });
        server.createContext("/media/file", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.sendResponseHeaders(200, IMAGE.length);
            exchange.getResponseBody().write(IMAGE);
            exchange.close();
        });
        server.createContext("/rest/api/2/attachment/content/2", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://localhost:" + server.getAddress().getPort()
                    + "/media/file?token=secret-token");
            exchange.sendResponseHeaders(303, -1);
            exchange.close();
        });
        server.createContext("/rest/api/2/attachment/content/3", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[100]);
            exchange.close();
        });
        server.createContext("/rest/api/2/attachment/content/5", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[10]);
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(15_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.createContext("/rest/api/2/attachment/content/4", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private JiraRestConnector connector(String email, String token) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new JiraRestConnector(new JiraProperties(base, email, token), RestClient.builder());
    }

    @Test
    void followsASameOriginRedirectAndKeepsTheCredentialsOnJiraOnly() {
        assertThat(connector("bot@example.org", "secret").downloadAttachment("1", 1000)).isEqualTo(IMAGE);

        assertThat(authorizations).hasSize(2).allMatch(value -> value.startsWith("Basic "));
    }

    @Test
    void readsAnonymouslyWithoutCredentials() {
        assertThat(connector(null, null).downloadAttachment("1", 1000)).isEqualTo(IMAGE);

        assertThat(authorizations).containsExactly("null", "null");
    }

    @Test
    void refusesARedirectToAnotherHostWithoutLeakingItsUrl() {
        assertThatThrownBy(() -> connector("bot@example.org", "secret").downloadAttachment("2", 1000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not allowed")
                .hasMessageNotContaining("secret-token")
                .hasMessageNotContaining("localhost");
    }

    @Test
    void refusesMoreThanTheLimitEvenWithoutAContentLength() {
        assertThatThrownBy(() -> connector(null, null).downloadAttachment("3", 50))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("larger than 50");
    }

    @Test
    void aBodyThatStallsAfterTheHeadersIsCutOffAtTheDeadline() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var connector = new JiraRestConnector(new JiraProperties(base, null, null), RestClient.builder(),
                java.time.Duration.ofSeconds(2));
        long started = System.nanoTime();

        assertThatThrownBy(() -> connector.downloadAttachment("5", 1000))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("timed out");
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(10));
    }

    @Test
    void reportsTheStatusOfAFailedDownload() {
        assertThatThrownBy(() -> connector(null, null).downloadAttachment("4", 1000))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 404");
    }

    @Test
    void rejectsAttachmentIdsThatAreNotNumbers() {
        assertThatThrownBy(() -> connector(null, null).downloadAttachment("1/../../issue", 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> connector(null, null).downloadAttachment(null, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redirectsMayOnlyReachTheJiraOriginOrItsMediaHostOverHttps() {
        URI base = URI.create("https://folio-org.atlassian.net");

        assertThat(JiraRestConnector.redirectAllowed(base, URI.create("https://folio-org.atlassian.net/x"))).isTrue();
        assertThat(JiraRestConnector.redirectAllowed(base, URI.create("https://api.media.atlassian.com/file/1/binary?token=t"))).isTrue();
        assertThat(JiraRestConnector.redirectAllowed(base, URI.create("http://api.media.atlassian.com/file/1/binary"))).isFalse();
        assertThat(JiraRestConnector.redirectAllowed(base, URI.create("https://api.media.atlassian.com:8443/file"))).isFalse();
        assertThat(JiraRestConnector.redirectAllowed(base, URI.create("https://evil.example.org/file"))).isFalse();
        assertThat(JiraRestConnector.redirectAllowed(base, URI.create("https://api.media.atlassian.com.evil.org/file"))).isFalse();
        assertThat(JiraRestConnector.redirectAllowed(base, URI.create("http://folio-org.atlassian.net/x"))).isFalse();
    }
}
