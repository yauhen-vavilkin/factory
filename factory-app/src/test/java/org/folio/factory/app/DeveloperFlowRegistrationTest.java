package org.folio.factory.app;

import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Developer Flow is an ordinary plugin: registered next to Test Factory, visible
 * through the existing flow API/UI, and executed by the real engine from the
 * existing manual trigger.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "factory.engine.poll-interval-ms=250"
})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class DeveloperFlowRegistrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort
    int port;

    @Autowired
    FlowRegistry flowRegistry;

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    HitlReviewRepository reviews;

    private RestClient rest;

    @BeforeEach
    void setUpClient() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void registersAlongsideTestFactoryAndIsVisibleInExistingFlowViews() {
        assertThat(flowRegistry.require("dev-factory").name()).isEqualTo("Developer Flow");
        assertThat(flowRegistry.require("test-factory").name()).isEqualTo("Test Factory");

        JsonNode flows = rest.get().uri("/api/flows").retrieve().body(JsonNode.class);
        assertThat(flows.findValuesAsString("id")).contains("dev-factory", "test-factory");

        JsonNode detail = rest.get().uri("/api/flows/dev-factory").retrieve().body(JsonNode.class);
        assertThat(detail.path("name").asString()).isEqualTo("Developer Flow");
        assertThat(detail.path("steps").get(0).path("workerId").asString()).isEqualTo("dev-smoke-worker");

        assertThat(rest.get().uri("/flows").retrieve().body(String.class))
                .contains("Developer Flow").contains("Test Factory");
        assertThat(rest.get().uri("/flows/dev-factory").retrieve().body(String.class))
                .contains("Developer Flow").contains("dev-smoke-worker");
    }

    @Test
    void manualTriggerRunsSmokeWorkerToCompletion() {
        ResponseEntity<JsonNode> response = rest.post()
                .uri("/api/triggers/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("flowId", "dev-factory", "payload", Map.of("issueKey", "DEMO-1")))
                .retrieve()
                .toEntity(JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID executionId = UUID.fromString(response.getBody().path("executionId").asString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.COMPLETED));

        assertThat(stateManager.get(executionId).getFlowId()).isEqualTo("dev-factory");
        assertThat(artifactStore.getLatest(executionId, "dev_smoke.md").orElseThrow().getContent())
                .contains("state: \"SMOKE_ONLY\"")
                .contains("issue_key: \"DEMO-1\"");
        assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId)).isEmpty();
    }
}
