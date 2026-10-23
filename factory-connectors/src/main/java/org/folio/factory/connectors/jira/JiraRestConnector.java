package org.folio.factory.connectors.jira;

import org.folio.factory.connectors.ConnectorHealth;
import org.folio.factory.connectors.ConnectorNotConfiguredException;
import org.folio.factory.connectors.jira.JiraSourceModels.DiscoveredIssue;
import org.folio.factory.connectors.jira.JiraSourceModels.IssuePage;
import org.folio.factory.connectors.jira.JiraSourceModels.ResolvedSource;
import org.folio.factory.connectors.jira.JiraSourceModels.SourceReference;
import org.folio.factory.connectors.jira.JiraSourceModels.Sprint;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jira REST API v2 client. Issue search is pinned to the Cloud
 * POST /rest/api/2/search/jql contract with opaque nextPageToken paging.
 * Without email/token it reads public issues anonymously and refuses every write.
 */
public class JiraRestConnector implements JiraConnector, JiraSourceConnector, ConnectorHealth {

    private static final int SEARCH_PAGE_SIZE = 50;
    private static final List<String> SEARCH_FIELDS = List.of(
            "summary", "description", "status", "issuetype", "labels", "project", "components", "created");

    private final RestClient restClient;
    private final boolean authenticated;
    private final String baseUrl;

    public JiraRestConnector(JiraProperties properties, RestClient.Builder builder) {
        this.authenticated = properties.isConfigured();
        this.baseUrl = properties.baseUrl();
        builder.baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (authenticated) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + HttpHeaders.encodeBasicAuth(
                    properties.email(), properties.apiToken(), StandardCharsets.UTF_8));
        }
        this.restClient = builder.build();
    }

    @Override
    public JiraIssue getIssue(String issueKey) {
        JsonNode body = restClient.get()
                .uri("/rest/api/2/issue/{key}", issueKey)
                .retrieve()
                .body(JsonNode.class);
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
        requireWriteAccess();
        restClient.post()
                .uri("/rest/api/2/issue/{key}/comment", issueKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void transitionIssue(String issueKey, String transitionName) {
        requireWriteAccess();
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

    private void requireWriteAccess() {
        if (!authenticated) {
            throw new ConnectorNotConfiguredException("Jira connector is read-only: set "
                    + "FACTORY_CONNECTORS_JIRA_EMAIL and FACTORY_CONNECTORS_JIRA_API_TOKEN to enable writes");
        }
    }

    @Override
    public SourceReference parseSourceReference(String raw) {
        return SourceReference.parse(raw, baseUrl);
    }

    @Override
    public ResolvedSource resolveSource(SourceReference reference) {
        return switch (reference.kind()) {
            case BOARD -> resolveBoard(reference.id());
            case FILTER -> resolveFilter(reference.id());
        };
    }

    private ResolvedSource resolveBoard(String boardId) {
        JsonNode filter;
        try {
            filter = requireBody(restClient.get()
                    .uri("/rest/agile/1.0/board/{id}/filter", boardId)
                    .retrieve()
                    .body(JsonNode.class), "board " + boardId + " filter");
        } catch (RestClientException e) {
            throw new IllegalStateException("Board " + boardId + " could not be resolved; if it is a saved "
                    + "filter id, use the 'filter:" + boardId + "' form", e);
        }
        String filterId = requiredField(filter, "id", "board " + boardId + " filter");
        return new ResolvedSource(boardId,
                requiredField(filter, "name", "board " + boardId + " filter"),
                filterLink(filterId),
                filterId,
                requiredField(filter, "jql", "board " + boardId + " filter"));
    }

    private ResolvedSource resolveFilter(String filterId) {
        JsonNode filter = requireBody(restClient.get()
                .uri("/rest/api/2/filter/{id}", filterId)
                .retrieve()
                .body(JsonNode.class), "saved filter " + filterId);
        return new ResolvedSource(filterId,
                requiredField(filter, "name", "saved filter " + filterId),
                filterLink(filterId),
                requiredField(filter, "id", "saved filter " + filterId),
                requiredField(filter, "jql", "saved filter " + filterId));
    }

    private URI filterLink(String filterId) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(base + "/issues/?filter=" + filterId);
    }

    @Override
    public Sprint getSprint(String sprintId) {
        JsonNode sprint = requireBody(restClient.get()
                .uri("/rest/agile/1.0/sprint/{id}", sprintId)
                .retrieve()
                .body(JsonNode.class), "sprint " + sprintId);
        return new Sprint(requiredField(sprint, "id", "sprint " + sprintId),
                requiredField(sprint, "name", "sprint " + sprintId));
    }

    @Override
    public IssuePage searchIssues(String jql, String cursor) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jql", jql);
        request.put("maxResults", SEARCH_PAGE_SIZE);
        request.put("fields", SEARCH_FIELDS);
        if (cursor != null && !cursor.isBlank()) {
            request.put("nextPageToken", cursor);
        }
        JsonNode body = requireBody(restClient.post()
                .uri("/rest/api/2/search/jql")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class), "issue search");
        JsonNode issuesNode = body.path("issues");
        if (!issuesNode.isArray()) {
            throw new IllegalStateException("Unexpected Jira search response: 'issues' is missing");
        }
        List<DiscoveredIssue> issues = new ArrayList<>();
        issuesNode.forEach(issue -> issues.add(toDiscoveredIssue(issue)));
        String nextCursor = nextPageToken(body);
        if (issues.isEmpty() && nextCursor != null) {
            throw new IllegalStateException("Unexpected Jira search response: empty issues page "
                    + "still carries a nextPageToken");
        }
        return new IssuePage(issues, nextCursor);
    }

    private static String nextPageToken(JsonNode body) {
        JsonNode token = body.path("nextPageToken");
        if (token.isMissingNode() || token.isNull()) {
            return null;
        }
        if (!token.isTextual() || token.asString().isBlank()) {
            throw new IllegalStateException("Unexpected Jira search response: 'nextPageToken' "
                    + "is present but not a usable token");
        }
        return token.asString();
    }

    private static DiscoveredIssue toDiscoveredIssue(JsonNode issue) {
        String id = requiredField(issue, "id", "issue search result");
        String key = requiredField(issue, "key", "issue search result " + id);
        JsonNode fields = issue.path("fields");
        String projectKey = requiredField(fields.path("project"), "key", "issue search result " + key);
        String created = requiredField(fields, "created", "issue search result " + key);
        List<String> components = new ArrayList<>();
        fields.path("components").forEach(component -> components.add(component.path("name").asString("")));
        List<String> labels = new ArrayList<>();
        fields.path("labels").forEach(label -> labels.add(label.asString("")));
        return new DiscoveredIssue(id, key, projectKey, components, parseJiraTimestamp(created, key),
                new JiraIssue(key,
                        fields.path("summary").asString(""),
                        fields.path("description").isTextual() ? fields.path("description").asString()
                                : fields.path("description").toString(),
                        fields.path("status").path("name").asString(""),
                        fields.path("issuetype").path("name").asString(""),
                        labels,
                        issue));
    }

    private static Instant parseJiraTimestamp(String value, String issueKey) {
        String normalized = value;
        // Jira Data Center writes offsets like "+0000", which ISO parsing rejects.
        if (normalized.length() >= 5) {
            String tail = normalized.substring(normalized.length() - 5);
            char sign = tail.charAt(0);
            if ((sign == '+' || sign == '-') && tail.charAt(3) != ':') {
                normalized = normalized.substring(0, normalized.length() - 5)
                        + sign + tail.substring(1, 3) + ":" + tail.substring(3);
            }
        }
        try {
            return OffsetDateTime.parse(normalized).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(
                    "Unexpected Jira timestamp '" + value + "' on issue " + issueKey, e);
        }
    }

    private static JsonNode requireBody(JsonNode body, String context) {
        if (body == null || body.isMissingNode()) {
            throw new IllegalStateException("Unexpected empty Jira response for " + context);
        }
        return body;
    }

    private static String requiredField(JsonNode parent, String field, String context) {
        String value = parent.path(field).asString("");
        if (value.isBlank()) {
            throw new IllegalStateException("Unexpected Jira response for "
                    + context + ": missing '" + field + "'");
        }
        return value;
    }

    @Override
    public String connectorName() {
        return "jira";
    }

    @Override
    public boolean isConfigured() {
        return authenticated;
    }
}
