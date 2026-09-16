package org.folio.factory.connectors.jira;

import org.folio.factory.connectors.ConnectorHealth;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Jira REST API v2 client (works with both Data Center and Cloud).
 */
public class JiraRestConnector implements JiraConnector, ConnectorHealth {

    private final RestClient restClient;

    public JiraRestConnector(JiraProperties properties, RestClient.Builder builder) {
        builder.baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        // Without credentials the client reads anonymously (public Jira instances
        // such as folio-org.atlassian.net); a blank Basic header would be rejected.
        if (properties.hasCredentials()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + HttpHeaders.encodeBasicAuth(
                    properties.email(), properties.apiToken(), StandardCharsets.UTF_8));
        }
        this.restClient = builder.build();
    }

    @Override
    public JiraIssue getIssue(String issueKey) {
        JsonNode body = restClient.get()
                .uri("/rest/api/2/issue/{key}?expand=changelog", issueKey)
                .retrieve()
                .body(JsonNode.class);
        return toIssue(body);
    }

    @Override
    public JsonNode getComments(String issueKey, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("comment limit must be positive");
        }
        // Two bounded calls instead of orderBy (not portable across Server and
        // Cloud): the first learns the total, the second reads only the newest
        // page. A task with thousands of comments never floods the snapshot.
        JsonNode probe = restClient.get()
                .uri(builder -> builder.path("/rest/api/2/issue/{key}/comment")
                        .queryParam("maxResults", 0).build(issueKey))
                .retrieve()
                .body(JsonNode.class);
        int total = probe.path("total").asInt(0);
        int startAt = Math.max(0, total - limit);
        return restClient.get()
                .uri(builder -> builder.path("/rest/api/2/issue/{key}/comment")
                        .queryParam("startAt", startAt).queryParam("maxResults", limit)
                        .build(issueKey))
                .retrieve()
                .body(JsonNode.class);
    }

    @Override
    public JiraIssue getLinkedIssue(String issueKey) {
        JsonNode body = restClient.get()
                .uri(builder -> builder.path("/rest/api/2/issue/{key}")
                        .queryParam("fields", "summary,status,description,issuetype")
                        .build(issueKey))
                .retrieve()
                .body(JsonNode.class);
        return toIssue(body);
    }

    @Override
    public JsonNode getFields() {
        return restClient.get().uri("/rest/api/2/field").retrieve().body(JsonNode.class);
    }

    private static JiraIssue toIssue(JsonNode body) {
        JsonNode fields = body.path("fields");
        List<String> labels = new ArrayList<>();
        fields.path("labels").forEach(label -> labels.add(label.asString("")));
        return new JiraIssue(
                body.path("key").asString(""),
                fields.path("summary").asString(""),
                fields.path("description").isTextual() ? fields.path("description").asString()
                        : fields.path("description").toString(),
                fields.path("status").path("name").asString(""),
                fields.path("issuetype").path("name").asString(""),
                labels,
                body);
    }

    @Override
    public void addComment(String issueKey, String body) {
        restClient.post()
                .uri("/rest/api/2/issue/{key}/comment", issueKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void transitionIssue(String issueKey, String transitionName) {
        JsonNode transitions = restClient.get()
                .uri("/rest/api/2/issue/{key}/transitions", issueKey)
                .retrieve()
                .body(JsonNode.class);
        String transitionId = null;
        for (JsonNode transition : transitions.path("transitions")) {
            if (transitionName.equalsIgnoreCase(transition.path("name").asString(""))) {
                transitionId = transition.path("id").asString("");
                break;
            }
        }
        if (transitionId == null) {
            throw new IllegalArgumentException(
                    "Issue " + issueKey + " has no transition named '" + transitionName + "'");
        }
        restClient.post()
                .uri("/rest/api/2/issue/{key}/transitions", issueKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("transition", Map.of("id", transitionId)))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public String connectorName() {
        return "jira";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }
}
