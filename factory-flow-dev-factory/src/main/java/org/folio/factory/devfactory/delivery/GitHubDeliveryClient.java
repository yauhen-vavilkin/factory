package org.folio.factory.devfactory.delivery;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivery-scope GitHub REST client against the fixed public API host, with
 * redirects disabled and bounded responses. It holds the delivery token; it
 * lives only in the Factory process and is never handed to a sandbox.
 */
public final class GitHubDeliveryClient implements DeliveryGitHub {
  private static final int MAX_API_BYTES = 256 * 1024;
  // Compare responses embed commits and file patches between the two refs.
  private static final int MAX_COMPARE_BYTES = 16 * 1024 * 1024;
  private final String apiBase;
  private final String token;
  private final HttpClient client = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
  private final JsonMapper json = JsonMapper.builder().build();

  public GitHubDeliveryClient(String token) {
    this("https://api.github.com", token);
  }

  GitHubDeliveryClient(String apiBase, String token) {
    this.apiBase = apiBase;
    this.token = token;
  }

  @Override
  public RepositoryInfo repository(String slug) {
    JsonNode repo = json.readTree(require(send("GET", "/repos/" + slug, null, MAX_API_BYTES)));
    return new RepositoryInfo(repo.path("full_name").asString(""), repo.path("fork").asBoolean(false),
        repo.path("parent").path("full_name").asString(""),
        repo.path("source").path("full_name").asString(""), repo.path("default_branch").asString(""));
  }

  @Override
  public boolean branchContains(String slug, String branch, String sha) {
    Response response = send("GET", "/repos/" + slug + "/compare/" + sha + "..."
        + encode(branch) + "?per_page=1", null, MAX_COMPARE_BYTES);
    if (response.status() == 404) {
      return false;
    }
    String status = json.readTree(require(response)).path("status").asString("");
    return "ahead".equals(status) || "identical".equals(status);
  }

  @Override
  public Optional<RemoteCommit> branchHead(String slug, String branch) {
    Response ref = send("GET", "/repos/" + slug + "/git/ref/heads/" + branch, null, MAX_API_BYTES);
    if (ref.status() == 404) {
      return Optional.empty();
    }
    String sha = json.readTree(require(ref)).path("object").path("sha").asString("");
    JsonNode commit = json.readTree(require(send("GET", "/repos/" + slug + "/git/commits/" + sha,
        null, MAX_API_BYTES)));
    List<String> parents = new ArrayList<>();
    commit.path("parents").forEach(parent -> parents.add(parent.path("sha").asString("")));
    return Optional.of(new RemoteCommit(sha, commit.path("tree").path("sha").asString(""), parents));
  }

  @Override
  public Optional<PullRequest> findPullRequest(String slug, String owner, String branch) {
    JsonNode pulls = json.readTree(require(send("GET", "/repos/" + slug + "/pulls?state=all&head="
        + encode(owner + ":" + branch), null, MAX_API_BYTES)));
    return pulls.isArray() && !pulls.isEmpty() ? Optional.of(pullRequest(pulls.get(0))) : Optional.empty();
  }

  @Override
  public PullRequest createPullRequest(String slug, String headBranch, String baseBranch, String title,
                                       String body) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("title", title);
    request.put("head", headBranch);
    request.put("base", baseBranch);
    request.put("body", body);
    request.put("maintainer_can_modify", false);
    return pullRequest(json.readTree(require(send("POST", "/repos/" + slug + "/pulls",
        json.writeValueAsString(request), MAX_API_BYTES))));
  }

  private static PullRequest pullRequest(JsonNode pull) {
    return new PullRequest(pull.path("number").asLong(0), pull.path("html_url").asString(""),
        pull.path("state").asString(""), pull.path("head").path("sha").asString(""));
  }

  private Response send(String method, String path, String body, int maxBytes) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(apiBase + path))
        .timeout(Duration.ofSeconds(60))
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("Authorization", "Bearer " + token)
        .header("User-Agent", "folio-factory-delivery/1");
    if (body == null) {
      request.GET();
    } else {
      request.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    try {
      HttpResponse<InputStream> response = client.send(request.build(),
          HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream stream = response.body()) {
        byte[] bytes = stream.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
          throw new IllegalStateException("GitHub response exceeds " + maxBytes + " bytes: " + method + " " + path);
        }
        return new Response(method + " " + path, response.statusCode(), bytes);
      }
    } catch (IOException e) {
      throw new IllegalStateException("GitHub request failed: " + method + " " + path, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub request interrupted: " + method + " " + path, e);
    }
  }

  private static byte[] require(Response response) {
    if (response.status() / 100 != 2) {
      String message = new String(response.body(), StandardCharsets.UTF_8);
      throw new IllegalStateException("GitHub returned HTTP " + response.status() + " for "
          + response.request() + ": " + (message.length() > 512 ? message.substring(0, 512) : message));
    }
    return response.body();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private record Response(String request, int status, byte[] body) {
  }
}
