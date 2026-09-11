package org.folio.factory.devfactory.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Hermetic regression tests for the bounded GitHub client.
 *
 * Regression origin (dataset batch 2026-09-11): admission of curated dataset
 * bases failed with "remote response exceeds 65536 bytes" because
 * verifyCommit used the REST commits endpoint, whose response embeds every
 * changed-file patch and overflows the bounded-response cap for ordinary
 * merge commits (observed on folio-org/mgr-tenant-entitlements 0845da6,
 * PR #311). The client now verifies commits through the Git Data commit
 * endpoint, which returns only the commit object.
 */
class GitHubRepositoryAccessTest {

  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private static final String SLUG = "folio-org/example-repo";

  private HttpServer server;
  private GitHubRepositoryAccess access;
  private String lastPath;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      lastPath = exchange.getRequestURI().getPath();
      byte[] body = switch (lastPath) {
        // Git Data commit object: sha only, small by construction.
        case "/repos/" + SLUG + "/git/commits/" + SHA ->
            ("{\"sha\":\"" + SHA + "\",\"message\":\"merge huge PR\"}").getBytes(StandardCharsets.UTF_8);
        default -> "{}".getBytes(StandardCharsets.UTF_8);
      };
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    server.start();
    access = new GitHubRepositoryAccess("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void verifyCommitUsesTheGitDataCommitEndpointAndResolvesMergeBases() {
    String resolved = access.verifyCommit(SLUG, SHA);

    assertThat(resolved).isEqualTo(SHA);
    assertThat(lastPath).isEqualTo("/repos/" + SLUG + "/git/commits/" + SHA);
  }

  @Test
  void oversizedResponsesAreStillRejectedByTheBoundedReader() {
    server.removeContext("/");
    server.createContext("/", exchange -> {
      byte[] body = new byte[70 * 1024];
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });

    assertThatThrownBy(() -> access.verifyCommit(SLUG, SHA))
        .isInstanceOf(RepositoryAccessException.class)
        .hasMessageContaining("remote response exceeds");
  }

  @Test
  void resolveBranchUsesTheGitRefEndpoint() {
    server.removeContext("/");
    server.createContext("/", exchange -> {
      lastPath = exchange.getRequestURI().getPath();
      byte[] body = ("{\"object\":{\"sha\":\"" + SHA + "\"}}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });

    assertThat(access.resolveBranch(SLUG, "main")).isEqualTo(SHA);
    assertThat(lastPath).isEqualTo("/repos/" + SLUG + "/git/ref/heads/main");
  }
}
