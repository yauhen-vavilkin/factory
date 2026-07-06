package org.folio.factory.app;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.web.client.RestClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full Flow A pipeline through the real poller, REST API, WireMock'd Jira /
 * GitHub / TestRail and a scripted LLM: trigger → triage (Jira fetch) → test
 * plan → QA amend at gate 1 → script generation → advisory execution → QA
 * approve at gate 2 → finalizer syncs all three connectors.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "factory.engine.poll-interval-ms=250"
})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class FlowAEndToEndTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer jira = new WireMockServer(0);
    static WireMockServer gitHub = new WireMockServer(0);
    static WireMockServer testRail = new WireMockServer(0);

    @DynamicPropertySource
    static void connectorProperties(DynamicPropertyRegistry registry) {
        jira.start();
        gitHub.start();
        testRail.start();
        registry.add("factory.connectors.jira.base-url", jira::baseUrl);
        registry.add("factory.connectors.jira.email", () -> "bot@example.org");
        registry.add("factory.connectors.jira.api-token", () -> "token");
        registry.add("factory.connectors.github.base-url", gitHub::baseUrl);
        registry.add("factory.connectors.github.token", () -> "ghp_test");
        registry.add("factory.connectors.testrail.base-url", testRail::baseUrl);
        registry.add("factory.connectors.testrail.username", () -> "bot");
        registry.add("factory.connectors.testrail.api-key", () -> "key");
        registry.add("factory.connectors.testrail.project-id", () -> "12");
        registry.add("factory.flowa.target-repo", () -> "folio-org/mod-agreements");
        registry.add("factory.flowa.jira-transition", () -> "QA Complete");
        registry.add("factory.flowa.testrail-section-id", () -> "55");
    }

    @AfterAll
    static void stopWireMock() {
        jira.stop();
        gitHub.stop();
        testRail.stop();
    }

    @LocalServerPort
    int port;

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    AuditLog auditLog;

    @Autowired
    HitlReviewRepository reviews;

    private final JsonMapper json = JsonMapper.builder().build();

    private RestClient rest;

    @BeforeEach
    void setUpClientAndStubs() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        jira.resetAll();
        gitHub.resetAll();
        testRail.resetAll();

        jira.stubFor(get(urlEqualTo("/rest/api/2/issue/ERM-1001")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"key": "ERM-1001",
                         "fields": {"summary": "Add agreement name validation",
                                    "description": "As a librarian I want agreement names validated. AC1: valid name creates agreement. AC2: empty name rejected.",
                                    "status": {"name": "Ready for QA"},
                                    "issuetype": {"name": "Story"},
                                    "labels": []}}
                        """)));
        jira.stubFor(post(urlEqualTo("/rest/api/2/issue/ERM-1001/comment"))
                .willReturn(aResponse().withStatus(201)));
        jira.stubFor(get(urlEqualTo("/rest/api/2/issue/ERM-1001/transitions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"transitions\": [{\"id\": \"31\", \"name\": \"QA Complete\"}]}")));
        jira.stubFor(post(urlEqualTo("/rest/api/2/issue/ERM-1001/transitions"))
                .willReturn(aResponse().withStatus(204)));

        gitHub.stubFor(get(urlEqualTo("/repos/folio-org/mod-agreements/git/ref/heads/main"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"object\": {\"sha\": \"abc123\"}}")));
        gitHub.stubFor(post(urlEqualTo("/repos/folio-org/mod-agreements/git/refs"))
                .willReturn(aResponse().withStatus(201)));
        gitHub.stubFor(get(urlPathEqualTo("/repos/folio-org/mod-agreements/contents/features/agreements.feature"))
                .willReturn(aResponse().withStatus(404)));
        gitHub.stubFor(put(urlEqualTo("/repos/folio-org/mod-agreements/contents/features/agreements.feature"))
                .willReturn(aResponse().withStatus(201)));
        gitHub.stubFor(post(urlEqualTo("/repos/folio-org/mod-agreements/pulls"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"html_url\": \"https://github.com/folio-org/mod-agreements/pull/7\"}")));

        testRail.stubFor(post(urlPathEqualTo("/index.php")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\": 991}")));
    }

    @Test
    void rawJiraWebhookBodyTriggersTheFlow() {
        // Real Jira webhook bodies have issue.key, not a top-level issueKey — the
        // adapter must normalise them so the declared trigger actually fires.
        ResponseEntity<JsonNode> response = rest.post()
                .uri("/api/webhooks/jira")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "webhookEvent", "jira:issue_updated",
                        "issue", Map.of(
                                "key", "ERM-1001",
                                "fields", Map.of("status", Map.of("name", "Ready for QA")))))
                .retrieve()
                .toEntity(JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().path("executionIds").size()).isEqualTo(1);
        UUID executionId = UUID.fromString(response.getBody().path("executionIds").get(0).asString());
        assertThat(stateManager.get(executionId).getFlowId()).isEqualTo("test-factory");
        assertThat(stateManager.get(executionId).getTriggerPayload()).contains("\"issueKey\"");
    }

    @Test
    void fullPipelineWithAmendmentAndConnectorSync() {
        // 1. Trigger manually with only the issue key — triage must fetch from Jira.
        ResponseEntity<JsonNode> triggerResponse = rest.post()
                .uri("/api/triggers/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("flowId", "test-factory", "payload", Map.of("issueKey", "ERM-1001")))
                .retrieve()
                .toEntity(JsonNode.class);
        assertThat(triggerResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID executionId = UUID.fromString(triggerResponse.getBody().path("executionId").asString());

        // 2. Wait for HITL gate 1 and amend the test plan.
        JsonNode review1 = awaitPendingReview(executionId, "gate-1-test-plan");
        String planContent = artifactContent(review1, "test_plan.md");
        assertThat(planContent).contains("TC-01").contains("TC-02");
        String amendedPlan = planContent + "\n\n> Amended by QA: verified boundary coverage.\n";

        ResponseEntity<JsonNode> decision1 = rest.post()
                .uri("/api/hitl/reviews/" + review1.path("id").asString() + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("decision", "AMEND", "reviewer", "qa-lead", "comments", "tightened",
                        "amendedArtifacts", Map.of("test_plan.md", amendedPlan)))
                .retrieve()
                .toEntity(JsonNode.class);
        assertThat(decision1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decision1.getBody().path("status").asString()).isEqualTo("AMENDED");

        // 3. Wait for HITL gate 2 (sign-off) and approve.
        JsonNode review2 = awaitPendingReview(executionId, "gate-2-signoff");
        assertThat(artifactContent(review2, "test_results.md")).contains("ADVISORY");
        rest.post()
                .uri("/api/hitl/reviews/" + review2.path("id").asString() + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("decision", "APPROVE", "reviewer", "qa-lead"))
                .retrieve()
                .toEntity(JsonNode.class);

        // 4. Execution completes.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.COMPLETED));

        // 5. Artifacts: amended plan is v2 and downstream consumed it.
        assertThat(artifactStore.getLatest(executionId, "test_plan.md").orElseThrow().getVersion()).isEqualTo(2);
        assertThat(artifactStore.getLatest(executionId, "test_plan.md").orElseThrow().getCreatedBy())
                .isEqualTo("hitl:qa-lead");
        assertThat(artifactStore.getLatest(executionId, "scope_manifest.md")).isPresent();
        assertThat(artifactStore.getLatest(executionId, "test_scripts.md").orElseThrow().getContent())
                .contains("Feature: Agreement name validation");
        String syncReport = artifactStore.getLatest(executionId, "sync_report.md").orElseThrow().getContent();
        assertThat(syncReport).contains("github").contains("done")
                .contains("https://github.com/folio-org/mod-agreements/pull/7");

        // 6. Audit trail covers the whole lifecycle.
        var eventTypes = auditLog.forExecution(executionId).stream().map(e -> e.getEventType()).toList();
        assertThat(eventTypes).containsSubsequence(
                AuditEventType.EXECUTION_STARTED,
                AuditEventType.STEP_COMPLETED,
                AuditEventType.HITL_REQUESTED,
                AuditEventType.HITL_DECIDED,
                AuditEventType.HITL_REQUESTED,
                AuditEventType.HITL_DECIDED,
                AuditEventType.CONNECTOR_ACTION,
                AuditEventType.EXECUTION_COMPLETED);

        // 7. External systems really were called.
        jira.verify(getRequestedFor(urlEqualTo("/rest/api/2/issue/ERM-1001")));
        jira.verify(postRequestedFor(urlEqualTo("/rest/api/2/issue/ERM-1001/comment")));
        jira.verify(postRequestedFor(urlEqualTo("/rest/api/2/issue/ERM-1001/transitions")));
        gitHub.verify(postRequestedFor(urlEqualTo("/repos/folio-org/mod-agreements/git/refs")));
        gitHub.verify(putRequestedFor(
                urlEqualTo("/repos/folio-org/mod-agreements/contents/features/agreements.feature")));
        gitHub.verify(postRequestedFor(urlEqualTo("/repos/folio-org/mod-agreements/pulls")));
        // TestRail: 2 add_case calls + 1 add_run (advisory mode → no results call).
        testRail.verify(3, postRequestedFor(urlPathEqualTo("/index.php")));
    }

    private JsonNode awaitPendingReview(UUID executionId, String gateId) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
                        .anyMatch(r -> r.getGateId().equals(gateId)
                                && r.getStatus() == HitlReviewStatus.PENDING)).isTrue());
        var review = reviews.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
                .filter(r -> r.getGateId().equals(gateId) && r.getStatus() == HitlReviewStatus.PENDING)
                .findFirst().orElseThrow();
        return rest.get().uri("/api/hitl/reviews/" + review.getId()).retrieve().body(JsonNode.class);
    }

    private String artifactContent(JsonNode reviewDetail, String artifactName) {
        for (JsonNode artifact : reviewDetail.path("reviewPackage").path("artifacts")) {
            if (artifactName.equals(artifact.path("name").asString())) {
                return artifact.path("content").asString();
            }
        }
        throw new AssertionError("Review package has no artifact " + artifactName + ": " + reviewDetail);
    }
}
