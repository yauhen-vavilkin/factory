package org.folio.factory.devfactory.feedback;

import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.core.service.ArtifactStore;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

public class GitHubPrFeedbackGateway implements PrFeedbackGateway {
    private final RestClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    public GitHubPrFeedbackGateway(GitHubProperties properties, RestClient.Builder builder) {
        client = builder.baseUrl(properties.effectiveBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.token())
                .defaultHeader("Accept", "application/vnd.github+json").build();
    }
    public PullRequest read(String repository, int number) {
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || number < 1)
            throw new IllegalArgumentException("Invalid PR identity");
        var node = get("/repos/" + repository + "/pulls/" + number);
        if (!repository.equals(node.path("head").path("repo").path("full_name").asString(""))
                || !repository.equals(node.path("base").path("repo").path("full_name").asString("")))
            throw new IllegalStateException("PR repository no longer matches the authorized delivery target");
        String sha = node.path("head").path("sha").asString("");
        if (!sha.matches("[a-f0-9]{40}")) throw new IllegalStateException("GitHub returned an invalid PR head");
        return new PullRequest(repository, number, node.path("head").path("ref").asString(""),
                node.path("base").path("ref").asString(""), sha, node.path("html_url").asString(""),
                "open".equals(node.path("state").asString("")) && !node.path("merged").asBoolean(false));
    }
    public Status status(PullRequest previous) {
        if (previous == null) throw new IllegalArgumentException("No delivered PR is available");
        String repository = previous.repository();
        int number = previous.number();
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || number < 1)
            throw new IllegalArgumentException("Invalid PR identity");
        var node = get("/repos/" + repository + "/pulls/" + number);
        if (!repository.equals(node.path("base").path("repo").path("full_name").asString(""))
                || !previous.baseBranch().equals(node.path("base").path("ref").asString("")))
            throw new IllegalStateException("PR destination no longer matches the authorized delivery target");
        String state = node.path("state").asString("");
        if ("closed".equals(state) && node.path("merged").asBoolean(false)) return Status.MERGED;
        if ("closed".equals(state) && !node.path("merged").asBoolean(false)) return Status.CLOSED;
        if ("open".equals(state) && !node.path("merged").asBoolean(false)) {
            if (!repository.equals(node.path("head").path("repo").path("full_name").asString(""))
                    || !previous.branch().equals(node.path("head").path("ref").asString("")))
                throw new IllegalStateException("PR branch identity changed");
            return Status.OPEN;
        }
        throw new IllegalStateException("GitHub returned an inconsistent PR status");
    }
    public Snapshot comments(PullRequest previous) {
        PullRequest pr = read(previous.repository(), previous.number());
        var comments = new ArrayList<Comment>();
        collect("/repos/" + pr.repository() + "/issues/" + pr.number() + "/comments", "issue", comments);
        collect("/repos/" + pr.repository() + "/pulls/" + pr.number() + "/reviews", "review", comments);
        collect("/repos/" + pr.repository() + "/pulls/" + pr.number() + "/comments", "inline", comments);
        comments.sort(Comparator.comparing(Comment::id));
        return new Snapshot(ArtifactStore.sha256(json.writeValueAsString(List.of(pr, comments))), pr, List.copyOf(comments));
    }
    private void collect(String path, String kind, List<Comment> result) {
        for (int page = 1; ; page++) {
            if (page > 100) throw new IllegalStateException("PR has too many comments to load safely");
            var nodes = get(path + "?per_page=100&page=" + page);
            if (!nodes.isArray()) throw new IllegalStateException("Invalid GitHub comments response");
            for (var n : nodes) {
                String body = n.path("body").asString("");
                if (body.isBlank()) continue;
                result.add(new Comment(kind + ":" + n.path("id").asString(""), n.path("user").path("login").asString(""),
                        body, n.path("path").asString(""), n.path("line").isNumber() ? n.path("line").asInt() : null,
                        kind.equals("inline") && n.path("position").isNull(),
                        n.path("updated_at").asString(n.path("submitted_at").asString("")), n.path("html_url").asString("")));
            }
            if (nodes.size() < 100) return;
        }
    }
    private JsonNode get(String path) {
        JsonNode result = client.get().uri(path).retrieve().body(JsonNode.class);
        if (result == null) throw new IllegalStateException("Empty GitHub response");
        return result;
    }
}
