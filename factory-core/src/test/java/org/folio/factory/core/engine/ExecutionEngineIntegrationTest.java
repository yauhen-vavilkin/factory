package org.folio.factory.core.engine;

import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
class ExecutionEngineIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    ExecutionEngine engine;

    @Autowired
    ExecutionClaimService claimService;

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    AuditLog auditLog;

    @Autowired
    HitlReviewRepository reviews;

    /**
     * Simulates the poller synchronously: claim and advance until the execution
     * reaches the expected quiescent state. Condition-based like
     * SubFlowIntegrationTest#driveUntilTerminal: a freshly (re)scheduled row can be
     * momentarily unclaimable while its JVM-clock next_run_at is ahead of the
     * database clock under load, so a fixed number of sleepless rounds races that
     * skew instead of absorbing it.
     */
    private void driveUntil(UUID executionId, ExecutionStatus expected) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
            claimService.claim(10).forEach(engine::advance);
            assertThat(stateManager.get(executionId).getStatus()).isEqualTo(expected);
        });
    }

    /** Claims (without advancing) until the execution is RUNNING, absorbing the same skew. */
    private void claimUntilRunning(UUID executionId) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
            claimService.claim(10);
            assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        });
    }

    @Test
    void runsTwoStepFlowToCompletionThroughArtifacts() {
        PipelineExecution execution = stateManager.createExecution(
                "fake-simple", "1.0.0", "{\"issueKey\":\"ERM-1\"}");
        driveUntil(execution.getId(), ExecutionStatus.COMPLETED);

        PipelineExecution finished = stateManager.get(execution.getId());
        assertThat(finished.getCompletedAt()).isNotNull();

        var first = artifactStore.getLatest(execution.getId(), "first.md").orElseThrow();
        var second = artifactStore.getLatest(execution.getId(), "second.md").orElseThrow();
        assertThat(first.getContent()).contains("$trigger").contains("ERM-1");
        assertThat(second.getContent()).contains("first.md:");
        assertThat(first.getCreatedBy()).isEqualTo("first");

        var eventTypes = auditLog.forExecution(execution.getId()).stream()
                .map(e -> e.getEventType()).toList();
        assertThat(eventTypes).containsSubsequence(
                AuditEventType.EXECUTION_STARTED,
                AuditEventType.STEP_STARTED,
                AuditEventType.ARTIFACT_WRITTEN,
                AuditEventType.STEP_COMPLETED,
                AuditEventType.STEP_STARTED,
                AuditEventType.ARTIFACT_WRITTEN,
                AuditEventType.STEP_COMPLETED,
                AuditEventType.EXECUTION_COMPLETED);
    }

    @Test
    void undeclaredOutputIsRejectedAndNothingPersisted() {
        PipelineExecution execution = stateManager.createExecution("fake-rogue", "1.0.0", null);
        driveUntil(execution.getId(), ExecutionStatus.FAILED_ESCALATED);

        assertThat(artifactStore.getLatest(execution.getId(), "rogue.md")).isEmpty();
        assertThat(artifactStore.getLatest(execution.getId(), "expected.md")).isEmpty();

        var failure = auditLog.forExecution(execution.getId()).stream()
                .filter(e -> e.getEventType() == AuditEventType.STEP_FAILED)
                .findFirst().orElseThrow();
        assertThat(failure.getDetail()).contains("undeclared").contains("rogue.md");
    }

    @Test
    void exhaustedRetryBudgetEscalatesToHumanReview() {
        PipelineExecution execution = stateManager.createExecution("fake-failing", "1.0.0", null);
        driveUntil(execution.getId(), ExecutionStatus.FAILED_ESCALATED);

        assertThat(stateManager.retryCount(execution.getId(), "doomed")).isEqualTo(2);

        var eventTypes = auditLog.forExecution(execution.getId()).stream()
                .map(e -> e.getEventType()).toList();
        // The escalation review is inserted atomically with the FAILED_ESCALATED
        // transition (HITL_REQUESTED), then the engine attributes the escalation
        // (ESCALATED) only once that review exists.
        assertThat(eventTypes).containsSubsequence(
                AuditEventType.STEP_FAILED,
                AuditEventType.RETRY_SCHEDULED,
                AuditEventType.STEP_FAILED,
                AuditEventType.HITL_REQUESTED,
                AuditEventType.ESCALATED);

        var escalations = reviews.findByExecutionIdOrderByCreatedAtAsc(execution.getId());
        assertThat(escalations).hasSize(1);
        assertThat(escalations.getFirst().getGateId()).isEqualTo(HitlGateOpener.ESCALATION_GATE_ID);
        assertThat(escalations.getFirst().getStatus()).isEqualTo(HitlReviewStatus.PENDING);
        assertThat(escalations.getFirst().getReviewPackage()).contains("deliberate test failure");
    }

    @Test
    void guardedAdvanceRefusesStaleDrivers() {
        PipelineExecution execution = stateManager.createExecution("fake-simple", "1.0.0", null);
        claimUntilRunning(execution.getId());

        // Wrong index (stale duplicate driver) → refused; correct index → advances.
        assertThat(stateManager.advanceStep(execution.getId(), 5, ExecutionStatus.RUNNING)).isFalse();
        assertThat(stateManager.get(execution.getId()).getCurrentStepIndex()).isZero();
        assertThat(stateManager.advanceStep(execution.getId(), 0, ExecutionStatus.RUNNING)).isTrue();
        assertThat(stateManager.get(execution.getId()).getCurrentStepIndex()).isEqualTo(1);
        // Wrong expected status → refused.
        assertThat(stateManager.advanceStep(execution.getId(), 1, ExecutionStatus.AWAITING_HITL)).isFalse();
    }

    @Test
    void reaperReturnsStaleRunningExecutionsToPending() {
        PipelineExecution execution = stateManager.createExecution("fake-simple", "1.0.0", null);
        claimUntilRunning(execution.getId());

        int reaped = claimService.reapStale(0);

        assertThat(reaped).isGreaterThanOrEqualTo(1);
        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.PENDING);
    }
}
