package org.folio.factory.devfactory.resolution;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.folio.factory.devfactory.inbox.GitRefs;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Minimal GitHub read client with fixed public hosts and redirects disabled. */
public final class GitHubRepositoryAccess implements RepositoryAccess {
  private static final int MAX_API_BYTES = 64 * 1024;
  private final HttpClient client = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
  private final JsonMapper json = JsonMapper.builder().build();

  @Override
  public String resolveBranch(String slug, String branch) {
    String encoded = URLEncoder.encode(branch, StandardCharsets.UTF_8).replace("+", "%20");
    JsonNode response = getJson("https://api.github.com/repos/" + slug + "/git/ref/heads/" + encoded);
    String sha = response.path("object").path("sha").asString("");
    return requireSha(sha, "branch '" + branch + "'");
  }

  @Override
  public String verifyCommit(String slug, String fullSha) {
    JsonNode response = getJson("https://api.github.com/repos/" + slug + "/commits/" + fullSha);
    return requireSha(response.path("sha").asString(""), "commit '" + fullSha + "'");
  }

  @Override
  public Optional<byte[]> readFile(String slug, String fullSha, String path, int maxBytes) {
    if (path.startsWith("/") || path.contains("..") || path.contains("\\")) {
      throw new RepositorySecurityException("unsafe repository file path: " + path);
    }
    String url = "https://raw.githubusercontent.com/" + slug + "/" + fullSha + "/" + path;
    BoundedResponse response = get(url, Math.min(maxBytes, MAX_API_BYTES));
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    requireSuccess(response, url);
    if (response.body().length > maxBytes) {
      throw new RepositoryAccessException("repository evidence exceeds limit: " + path);
    }
    return Optional.of(response.body());
  }

  private JsonNode getJson(String url) {
    BoundedResponse response = get(url, MAX_API_BYTES);
    requireSuccess(response, url);
    try {
      return json.readTree(response.body());
    } catch (RuntimeException e) {
      throw new RepositoryAccessException("invalid GitHub response", e);
    }
  }

  private BoundedResponse get(String url, int maxBytes) {
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
          .header("Accept", "application/vnd.github+json")
          .header("User-Agent", "folio-factory/1").GET().build();
      HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        byte[] bytes = body.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
          throw new RepositoryAccessException("remote response exceeds " + maxBytes + " bytes");
        }
        return new BoundedResponse(response.statusCode(), bytes);
      }
    } catch (IOException e) {
      throw new RepositoryAccessException("GitHub repository access failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RepositoryAccessException("GitHub repository access interrupted", e);
    }
  }

  private static void requireSuccess(BoundedResponse response, String url) {
    if (response.statusCode() / 100 == 3) {
      throw new RepositorySecurityException("repository redirect is not allowed: " + url);
    }
    if (response.statusCode() / 100 != 2) {
      throw new RepositoryAccessException("GitHub returned HTTP " + response.statusCode() + " for " + url);
    }
  }

  private record BoundedResponse(int statusCode, byte[] body) {
  }

  private static String requireSha(String sha, String source) {
    if (!GitRefs.isFullCommitSha(sha)) {
      throw new RepositoryAccessException(source + " did not resolve to a full commit SHA");
    }
    return sha.toLowerCase();
  }
}
