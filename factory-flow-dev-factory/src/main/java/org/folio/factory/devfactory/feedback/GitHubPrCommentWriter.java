package org.folio.factory.devfactory.feedback;

import org.folio.factory.connectors.github.GitHubProperties;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Posts Factory's round comment through the GitHub REST API. */
public class GitHubPrCommentWriter implements PrCommentWriter {

    private final GitHubProperties properties;
    private final RestClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    public GitHubPrCommentWriter(GitHubProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.client = GitHubPrFeedbackGateway.restClient(properties, builder);
    }

    @Override
    public boolean configured() {
        return properties.isConfigured();
    }

    @Override
    public String post(PrFeedbackGateway.PullRequest pr, String body) {
        if (!pr.repository().matches(GitHubPrFeedbackGateway.REPOSITORY) || pr.number() < 1) {
            throw new IllegalArgumentException("Invalid PR identity");
        }
        var response = client.post().uri("/repos/" + pr.repository() + "/issues/" + pr.number() + "/comments")
                .body(Map.of("body", body)).retrieve().body(String.class);
        JsonNode node = json.readTree(response == null ? "{}" : response);
        return node.path("html_url").asString("");
    }
}
