package org.folio.factory.core.limits;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.engine.HitlGateOpener;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.repository.AuditEventRepository;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.core.trigger.TriggerEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Trigger deduplication semantics at the router: a re-fired trigger with the same
 * payload identity collapses onto the existing execution. The class is deliberately
 * NOT {@code @Transactional} — the concurrency test needs real commits from
 * independent transactions — so rows persist across methods; each test uses a
 * distinct issueKey for isolation.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "factory.limits.dedup.enabled=true",
        "factory.limits.dedup.window=10m",
        "factory.limits.dedup.id-pointers=/issueKey"
})
@Testcontainers
class DedupIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    PipelineRouter router;

    @Autowired
    StateManager stateManager;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    HitlReviewRepository reviews;

    @Autowired
    HitlDecisionService decisionService;

    @Autowired
    DataSource dataSource;

    JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void createDedupIndex() {
        jdbc = new JdbcTemplate(dataSource);
        // Mirrors V2__execution_limits.sql: the partial unique index is Flyway-managed
        // in production and absent under Hibernate create-drop schema generation.
        jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_execution_active_dedup
                    ON pipeline_execution (flow_id, dedup_key)
                    WHERE dedup_key IS NOT NULL
                      AND status NOT IN ('COMPLETED', 'FAILED_ESCALATED', 'REJECTED', 'CANCELLED')
                """);
    }

    private JsonNode webhookPayload(String issueKey) {
        return json.readTree("""
                {"issueKey": "%s",
                 "issue": {"fields": {"status": {"name": "Ready for QA"}}}}
                """.formatted(issueKey));
    }

    private int rowCount(String flowId, String dedupKey) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pipeline_execution WHERE flow_id = ? AND dedup_key = ?",
                Integer.class, flowId, dedupKey);
        return count == null ? 0 : count;
    }

    @Test
    void route_refiredWebhookWithinWindow_collapsesOntoExistingExecution() {
        JsonNode payload = webhookPayload("DEDUP-1");
        List<UUID> first = router.route(TriggerEvent.of("jira.issue.transitioned", "test", payload));
        assertThat(first).hasSize(1);

        List<UUID> second = router.route(TriggerEvent.of("jira.issue.transitioned", "test", payload));

        assertThat(second).containsExactly(first.getFirst());
        String dedupKey = stateManager.get(first.getFirst()).getDedupKey();
        assertThat(dedupKey).isNotNull();
        assertThat(rowCount("fake-webhook", dedupKey)).isEqualTo(1);
        assertThat(auditEvents.findByExecutionIdOrderByIdAsc(first.getFirst()))
                .extracting(AuditEvent::getEventType)
                .contains(AuditEventType.EXECUTION_DEDUPED);
    }

    @Test
    void route_differentIssueKey_startsIndependentExecution() {
        List<UUID> first = router.route(
                TriggerEvent.of("jira.issue.transitioned", "test", webhookPayload("DEDUP-2A")));
        List<UUID> second = router.route(
                TriggerEvent.of("jira.issue.transitioned", "test", webhookPayload("DEDUP-2B")));

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(second.getFirst()).isNotEqualTo(first.getFirst());
    }

    @Test
    void route_manualTriggerWithoutDedupKey_neverDeduped() {
        JsonNode payload = json.readTree("{\"issueKey\": \"DEDUP-3\"}");

        UUID first = router.routeManual("fake-simple", payload, null);
        UUID second = router.routeManual("fake-simple", payload, null);

        assertThat(second).isNotEqualTo(first);
        assertThat(stateManager.get(first).getDedupKey()).isNull();
        assertThat(stateManager.get(second).getDedupKey()).isNull();
    }

    @Test
    void route_concurrentIdenticalTriggers_exactlyOneExecutionWins() throws Exception {
        JsonNode payload = webhookPayload("DEDUP-4");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<UUID> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await();
                        results.addAll(router.route(
                                TriggerEvent.of("jira.issue.transitioned", "test", payload)));
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(failures).isEmpty();
        // One thread creates; the other either sees it in the dedup lookup or loses the
        // unique-index race and is handed the winner — both paths return the same id.
        assertThat(results).hasSize(2);
        assertThat(results.stream().distinct().count()).isEqualTo(1);
        String dedupKey = stateManager.get(results.getFirst()).getDedupKey();
        assertThat(rowCount("fake-webhook", dedupKey)).isEqualTo(1);
    }

    @Test
    void route_terminalExecutionInsideWindow_stillDeduped() {
        // Pins the window clause of findDuplicates on its own: the prior run is
        // terminal (fails the active clause) but recent, and must still collapse —
        // "a re-fire after an execution finishes is governed by the time-window check".
        JsonNode payload = webhookPayload("DEDUP-7");
        List<UUID> first = router.route(TriggerEvent.of("jira.issue.transitioned", "test", payload));
        assertThat(first).hasSize(1);
        stateManager.transition(first.getFirst(), ExecutionStatus.COMPLETED, null);

        List<UUID> second = router.route(TriggerEvent.of("jira.issue.transitioned", "test", payload));

        assertThat(second).containsExactly(first.getFirst());
        assertThat(rowCount("fake-webhook", stateManager.get(first.getFirst()).getDedupKey())).isEqualTo(1);
    }

    @Test
    void route_activeExecutionOlderThanWindow_stillDeduped() {
        // Pins the active clause of findDuplicates on its own: the prior run is older
        // than the window (fails the window clause) but still active, and must still
        // collapse — losing this clause would also break the unique-index race
        // recovery in PipelineRouter.create, turning webhook re-fires into 500s.
        JsonNode payload = webhookPayload("DEDUP-8");
        List<UUID> first = router.route(TriggerEvent.of("jira.issue.transitioned", "test", payload));
        assertThat(first).hasSize(1);
        jdbc.update("UPDATE pipeline_execution SET created_at = now() - interval '20 minutes' WHERE id = ?",
                first.getFirst());

        List<UUID> second = router.route(TriggerEvent.of("jira.issue.transitioned", "test", payload));

        assertThat(second).containsExactly(first.getFirst());
        assertThat(rowCount("fake-webhook", stateManager.get(first.getFirst()).getDedupKey())).isEqualTo(1);
    }

    @Test
    void approveEscalation_activeDuplicateHoldsSameDedupKey_conflictInsteadOfDbError() {
        // An escalated run leaves the partial unique index (FAILED_ESCALATED is not
        // "active"), so a re-fire outside the window legitimately starts a second run
        // with the same key. Resuming the escalation would then collide with the
        // duplicate's index entry — the reviewer must get a clear conflict, not an
        // opaque constraint violation at commit.
        JsonNode payload = webhookPayload("DEDUP-9");
        UUID escalated = router.route(TriggerEvent.of("jira.issue.transitioned", "test", payload)).getFirst();
        stateManager.transition(escalated, ExecutionStatus.FAILED_ESCALATED, null);
        HitlReview escalation = reviews.save(
                new HitlReview(escalated, HitlGateOpener.ESCALATION_GATE_ID, 0, "{}"));
        jdbc.update("UPDATE pipeline_execution SET created_at = now() - interval '20 minutes' WHERE id = ?",
                escalated);
        UUID duplicate = router.route(TriggerEvent.of("jira.issue.transitioned", "test", payload)).getFirst();
        assertThat(duplicate).isNotEqualTo(escalated);

        assertThatThrownBy(() -> decisionService.decide(
                escalation.getId(), HitlDecision.APPROVE, "tech-lead", "retry it", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active execution");

        // The escalated run is untouched and can be resumed once the duplicate ends.
        assertThat(stateManager.get(escalated).getStatus()).isEqualTo(ExecutionStatus.FAILED_ESCALATED);
    }

    @Test
    void route_terminalExecutionOutsideWindow_newExecutionStarted() {
        JsonNode payload = webhookPayload("DEDUP-5");
        List<UUID> first = router.route(TriggerEvent.of("jira.issue.transitioned", "test", payload));
        assertThat(first).hasSize(1);
        UUID oldId = first.getFirst();
        stateManager.transition(oldId, ExecutionStatus.COMPLETED, null);
        jdbc.update("""
                UPDATE pipeline_execution
                SET created_at = now() - interval '20 minutes',
                    completed_at = now() - interval '20 minutes'
                WHERE id = ?
                """, oldId);

        List<UUID> second = router.route(TriggerEvent.of("jira.issue.transitioned", "test", payload));

        assertThat(second).hasSize(1);
        assertThat(second.getFirst()).isNotEqualTo(oldId);
    }
}
