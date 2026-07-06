package org.folio.factory.connectors.testrail;

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
 * TestRail API v2 client.
 */
public class TestRailRestConnector implements TestRailConnector, ConnectorHealth {

    private final RestClient restClient;
    private final TestRailProperties properties;

    public TestRailRestConnector(TestRailProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + HttpHeaders.encodeBasicAuth(
                        properties.username(), properties.apiKey(), StandardCharsets.UTF_8))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public long addCase(long sectionId, String title, String steps, String expected) {
        JsonNode response = restClient.post()
                .uri("/index.php?/api/v2/add_case/{sectionId}", sectionId)
                .body(Map.of(
                        "title", title,
                        "custom_steps", steps == null ? "" : steps,
                        "custom_expected", expected == null ? "" : expected))
                .retrieve()
                .body(JsonNode.class);
        return response.path("id").asLong();
    }

    @Override
    public long addRun(String name, List<Long> caseIds) {
        JsonNode response = restClient.post()
                .uri("/index.php?/api/v2/add_run/{projectId}", properties.projectId())
                .body(Map.of(
                        "name", name,
                        "include_all", false,
                        "case_ids", caseIds))
                .retrieve()
                .body(JsonNode.class);
        return response.path("id").asLong();
    }

    @Override
    public void addResults(long runId, Map<Long, Boolean> results, String comment) {
        List<Map<String, Object>> resultEntries = new ArrayList<>();
        for (Map.Entry<Long, Boolean> result : results.entrySet()) {
            resultEntries.add(Map.of(
                    "case_id", result.getKey(),
                    // TestRail status ids: 1 = passed, 5 = failed
                    "status_id", result.getValue() ? 1 : 5,
                    "comment", comment == null ? "" : comment));
        }
        restClient.post()
                .uri("/index.php?/api/v2/add_results_for_cases/{runId}", runId)
                .body(Map.of("results", resultEntries))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public String connectorName() {
        return "testrail";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }
}
