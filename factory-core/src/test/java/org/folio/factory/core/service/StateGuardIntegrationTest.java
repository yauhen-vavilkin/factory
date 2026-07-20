package org.folio.factory.core.service;

import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.engine.ExecutionClaimService;
import org.folio.factory.core.engine.ExecutionEngine;
import org.folio.factory.core.engine.HitlGateOpener;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Exercises the {@link StateManager} final-state guards and {@link HitlGateOpener}
 * atomic gate-opening against duplicate-driver / late-writer races: once a run has
 * resolved to a final state, an in-flight step's late transition, retry, or gate
 * insert must be refused, while a resumable FAILED_ESCALATED run still transitions
 * freely.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
class StateGuardIntegrationTest {

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
    AuditLog auditLog;

    @Autowired
    HitlGateOpener hitlGateOpener;

    @Autowired
    FlowRegistry flowRegistry;

    @Autowired
    HitlReviewRepository reviews;

    private void driveUntil(UUID executionId, ExecutionStatus expected) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
            claimService.claim(10).forEach(engine::advance);
            assertThat(stateManager.get(executionId).getStatus()).isEqualTo(expected);
        });
    }

    private void claimUntilRunning(UUID executionId) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
            claimService.claim(10);
            assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        });
    }

    private List<HitlReview> pendingReviews(UUID executionId) {
        return reviews.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
                .filter(review -> review.getStatus() == HitlReviewStatus.PENDING)
                .toList();
    }

    private void assertNoEventOfType(UUID executionId, AuditEventType type) {
        assertThat(auditLog.forExecution(executionId)).noneMatch(e -> e.getEventType() == type);
    }

    @Test
    void finalStateGuardRefusesLateWritersOnCancelledRunningExecution() {
        PipelineExecution execution = stateManager.createExecution("fake-simple", "1.0.0", null);
        claimUntilRunning(execution.getId());

        stateManager.transition(execution.getId(), ExecutionStatus.CANCELLED, Map.of("reason", "abort"));
        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);

        stateManager.scheduleRetry(execution.getId(), 0, "late failure");
        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);

        stateManager.transition(execution.getId(), ExecutionStatus.FAILED_ESCALATED, Map.of());
        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void lateEscalationOnCancelledExecutionOpensNoReviewNorAudit() {
        PipelineExecution execution = stateManager.createExecution("fake-cancel-escalate", "1.0.0", null);
        // The worker finalizes its own run to CANCELLED, then fails: the engine's
        // escalation path runs against an execution that has already resolved.
        driveUntil(execution.getId(), ExecutionStatus.CANCELLED);

        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(pendingReviews(execution.getId())).isEmpty();
        assertNoEventOfType(execution.getId(), AuditEventType.ESCALATED);
    }

    @Test
    void lateRetryOnCancelledExecutionSchedulesNoRetryNorAudit() {
        PipelineExecution execution = stateManager.createExecution("fake-cancel-retry", "1.0.0", null);
        driveUntil(execution.getId(), ExecutionStatus.CANCELLED);

        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(pendingReviews(execution.getId())).isEmpty();
        assertNoEventOfType(execution.getId(), AuditEventType.RETRY_SCHEDULED);
    }

    @Test
    void openGateOnCancelledExecutionInsertsNoReview() {
        PipelineExecution execution = stateManager.createExecution("fake-gated", "1.0.0", null);
        claimUntilRunning(execution.getId());
        stateManager.transition(execution.getId(), ExecutionStatus.CANCELLED, Map.of());

        StepDescriptor gateStep = flowRegistry.require("fake-gated").step(1);
        HitlReview review = hitlGateOpener.openGate(stateManager.get(execution.getId()), gateStep);

        assertThat(review).isNull();
        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(pendingReviews(execution.getId())).isEmpty();
    }

    @Test
    void transitionOnCompletedExecutionIsRefused() {
        PipelineExecution execution = stateManager.createExecution("fake-simple", "1.0.0", null);
        driveUntil(execution.getId(), ExecutionStatus.COMPLETED);

        stateManager.transition(execution.getId(), ExecutionStatus.FAILED_ESCALATED, Map.of());

        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void guardsDoNotBlockEscalationResume() {
        PipelineExecution execution = stateManager.createExecution("fake-failing", "1.0.0", null);
        driveUntil(execution.getId(), ExecutionStatus.FAILED_ESCALATED);

        stateManager.scheduleRetry(execution.getId(), 0, null);

        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.PENDING);
    }
}
