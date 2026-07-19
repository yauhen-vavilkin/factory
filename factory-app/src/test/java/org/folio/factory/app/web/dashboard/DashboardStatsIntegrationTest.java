package org.folio.factory.app.web.dashboard;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the native Postgres aggregation in {@link DashboardStatsService}
 * against a real database with data seeded through the production services, and
 * asserts the JSON shape the dashboard charts depend on. The service SQL uses
 * {@code date_trunc}, {@code FILTER (WHERE ...)} and {@code detail->>'connector'}
 * that no unit test with a mocked repository can validate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.model.chat=none", "factory.engine.enabled=false"})
@Import(StubLlmConfiguration.class)
@Testcontainers
class DashboardStatsIntegrationTest {

    private static final String FLOW_A = "test-factory";
    private static final String FLOW_B = "release-pipeline";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

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

    @Autowired
    DashboardStatsService dashboardStatsService;

    private RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // A single test: the exact-count assertions below depend on seeding running
    // exactly once, and this class shares one Spring context / database across
    // methods, so a second @Test that re-seeds would double the counts.
    @Test
    void aggregatesSeededDataAndSerializesDayAsIsoDateOverHttp() {
        seed();

        DashboardStats stats = dashboardStatsService.compute(7);

        assertThat(stats.totals().executions()).isEqualTo(4);
        assertThat(stats.totals().pendingReviews()).isEqualTo(1);
        assertThat(stats.totals().executionsToday()).isEqualTo(4);
        assertThat(stats.totals().artifacts()).isGreaterThanOrEqualTo(2);

        assertThat(stats.executionsByStatus())
                .anySatisfy(s -> assertThat(s.status()).isEqualTo(ExecutionStatus.COMPLETED.name()))
                .anySatisfy(s -> assertThat(s.status()).isEqualTo(ExecutionStatus.RUNNING.name()));

        assertThat(stats.executionsByFlowAndStatus())
                .anySatisfy(s -> {
                    assertThat(s.flowId()).isEqualTo(FLOW_B);
                    assertThat(s.status()).isEqualTo(ExecutionStatus.PENDING.name());
                });

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(stats.executionsPerDay())
                .as("today's bucket must be present")
                .anySatisfy(d -> assertThat(d.day()).isEqualTo(today));

        assertThat(stats.durationByFlow())
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.flowId()).isEqualTo(FLOW_A);
                    assertThat(d.completedCount()).isEqualTo(1);
                    assertThat(d.avgSeconds()).isNotNull().isGreaterThan(0.0);
                });

        assertThat(stats.stepFailures())
                .anySatisfy(f -> {
                    assertThat(f.stepId()).isEqualTo("triage");
                    assertThat(f.failures()).isEqualTo(1);
                    assertThat(f.retriesScheduled()).isEqualTo(1);
                });

        assertThat(stats.connectorOutcomes())
                .anySatisfy(c -> {
                    assertThat(c.connector()).isEqualTo("github");
                    assertThat(c.eventType()).isEqualTo(AuditEventType.CONNECTOR_ACTION.name());
                })
                .anySatisfy(c -> {
                    assertThat(c.connector()).isEqualTo("jira");
                    assertThat(c.eventType()).isEqualTo(AuditEventType.CONNECTOR_SKIPPED.name());
                });

        assertThat(stats.hitl().pending()).isEqualTo(1);
        assertThat(stats.hitl().decidedInWindow()).isEqualTo(1);
        assertThat(stats.artifactsByFlow())
                .anySatisfy(a -> assertThat(a.flowId()).isEqualTo(FLOW_A));

        // Same data over HTTP: charts.js string-joins on this exact shape
        // (r.day + '|' + r.status), so the day must serialize as a plain
        // "yyyy-MM-dd" string, not a [y,m,d] array.
        JsonNode body = rest.get().uri("/api/dashboard/stats?days=7").retrieve().body(JsonNode.class);
        JsonNode perDay = body.path("executionsPerDay");
        assertThat(perDay.isArray()).isTrue();
        assertThat(perDay).isNotEmpty();
        for (JsonNode row : perDay) {
            assertThat(row.path("day").asString()).matches("\\d{4}-\\d{2}-\\d{2}");
        }
    }

    private void seed() {
        // Created first, completed last: the elapsed seeding work guarantees a
        // strictly positive completed_at - created_at duration for FLOW_A.
        PipelineExecution completed = stateManager.createExecution(FLOW_A, "1", "{}");

        PipelineExecution running = stateManager.createExecution(FLOW_A, "1", "{}");
        stateManager.transition(running.getId(), ExecutionStatus.RUNNING, Map.of());

        stateManager.createExecution(FLOW_B, "1", "{}"); // stays PENDING

        PipelineExecution rejected = stateManager.createExecution(FLOW_B, "1", "{}");
        stateManager.transition(rejected.getId(), ExecutionStatus.REJECTED, Map.of());

        artifactStore.putMarkdown(completed.getId(), "test_plan.md", "# Plan\n", "test-spec");
        artifactStore.putMarkdown(running.getId(), "scope_manifest.md", "# Scope\n", "triage");

        auditLog.record(running.getId(), AuditEventType.STEP_FAILED, "triage",
                Map.of("error", "boom"));
        auditLog.record(running.getId(), AuditEventType.RETRY_SCHEDULED, "triage",
                Map.of("attempt", 1));
        auditLog.record(completed.getId(), AuditEventType.CONNECTOR_ACTION, "finalize",
                Map.of("connector", "github", "action", "open-pr"));
        auditLog.record(completed.getId(), AuditEventType.CONNECTOR_SKIPPED, "finalize",
                Map.of("connector", "jira", "reason", "unconfigured"));

        reviews.save(new HitlReview(running.getId(), "gate-1-test-plan", 2, "{}"));
        HitlReview decided = new HitlReview(completed.getId(), "gate-2-signoff", 5, "{}");
        decided.decide(HitlReviewStatus.AMENDED, "AMEND", "qa-lead", "looks good", null);
        reviews.save(decided);

        stateManager.transition(completed.getId(), ExecutionStatus.COMPLETED, Map.of());
    }
}
