package org.folio.factory.connectors.github;

import org.folio.factory.connectors.ConnectorHealth;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * GitHub REST API client using the contents API — sufficient for committing
 * generated test files and opening pull requests in milestone 1.
 */
public class GitHubRestConnector implements GitHubConnector, ConnectorHealth {

    private final RestClient restClient;

    public GitHubRestConnector(GitHubProperties properties, RestClient.Builder builder) {
        this.restClient = builder
                .baseUrl(properties.effectiveBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    @Override
    public void createBranch(String repo, String baseBranch, String newBranch) {
        JsonNode ref = restClient.get()
                .uri("/repos/" + repo + "/git/ref/heads/" + baseBranch)
                .retrieve()
                .body(JsonNode.class);
        String sha = ref.path("object").path("sha").asString("");
        restClient.post()
                .uri("/repos/" + repo + "/git/refs")
                .body(Map.of("ref", "refs/heads/" + newBranch, "sha", sha))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void commitFiles(String repo, String branch, Map<String, String> files, String message) {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Map<String, Object> request = new HashMap<>();
            request.put("message", message + " (" + file.getKey() + ")");
            request.put("content", Base64.getEncoder()
                    .encodeToString(file.getValue().getBytes(StandardCharsets.UTF_8)));
            request.put("branch", branch);
            existingFileSha(repo, branch, file.getKey()).ifPresent(sha -> request.put("sha", sha));
            restClient.put()
                    .uri("/repos/" + repo + "/contents/" + file.getKey())
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    @Override
    public String createPullRequest(String repo, String headBranch, String baseBranch, String title, String body) {
        JsonNode response = restClient.post()
                .uri("/repos/" + repo + "/pulls")
                .body(Map.of("title", title, "head", headBranch, "base", baseBranch, "body", body))
                .retrieve()
                .body(JsonNode.class);
        return response.path("html_url").asString("");
    }

    @Override
    public java.util.Optional<String> findOpenPullRequest(String repo, String headBranch, String baseBranch) {
        String owner = repo.substring(0, repo.indexOf('/'));
        JsonNode response = restClient.get()
                .uri(builder -> builder.path("/repos/" + repo + "/pulls")
                        .queryParam("state", "open").queryParam("head", owner + ":" + headBranch)
                        .queryParam("base", baseBranch).build())
                .retrieve().body(JsonNode.class);
        if (response != null && response.isArray()) {
            for (JsonNode pr : response) {
                if (repo.equalsIgnoreCase(pr.path("head").path("repo").path("full_name").asString(""))
                        && repo.equalsIgnoreCase(pr.path("base").path("repo").path("full_name").asString(""))
                        && headBranch.equals(pr.path("head").path("ref").asString(""))
                        && baseBranch.equals(pr.path("base").path("ref").asString(""))) {
                    String url = pr.path("html_url").asString("");
                    if (!url.isBlank()) return java.util.Optional.of(url);
                }
            }
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<String> existingFileSha(String repo, String branch, String path) {
        try {
            JsonNode existing = restClient.get()
                    .uri("/repos/" + repo + "/contents/" + path + "?ref=" + branch)
                    .retrieve()
                    .body(JsonNode.class);
            String sha = existing.path("sha").asString("");
            return sha == null || sha.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(sha);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatusCode.valueOf(404)) {
                return java.util.Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public String connectorName() {
        return "github";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }
}
