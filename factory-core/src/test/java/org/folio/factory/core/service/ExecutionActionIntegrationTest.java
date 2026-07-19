package org.folio.factory.core.service;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.engine.ExecutionClaimService;
import org.folio.factory.core.engine.ExecutionEngine;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
class ExecutionActionIntegrationTest {

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
    ExecutionActionService actionService;

    @Autowired
    HitlDecisionService decisionService;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    HitlReviewRepository reviews;

    @Autowired
    JsonMapper jsonMapper;

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

    private void driveParentToAwaitSubflow(UUID parentId) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
            claimService.claim(10).stream().filter(id -> id.equals(parentId)).forEach(engine::advance);
            assertThat(stateManager.get(parentId).getStatus()).isEqualTo(ExecutionStatus.AWAITING_SUBFLOW);
        });
    }

    private AuditEvent onlyEvent(UUID executionId, AuditEventType type) {
        return auditLog.forExecution(executionId).stream()
                .filter(e -> e.getEventType() == type)
                .findFirst().orElseThrow();
    }

    @Test
    void cancelPendingRecordsAuditAndSetsCompletedAt() {
        PipelineExecution execution = stateManager.createExecution("fake-simple", "1.0.0", "{\"seed\":\"x\"}");

        PipelineExecution cancelled = actionService.cancel(execution.getId(), "ops-1", "no longer needed");

        assertThat(cancelled.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(cancelled.getCompletedAt()).isNotNull();

        AuditEvent cancelEvent = onlyEvent(execution.getId(), AuditEventType.EXECUTION_CANCELLED);
        assertThat(cancelEvent.getActor()).isEqualTo("ops-1");
        assertThat(cancelEvent.getDetail()).contains("no longer needed");

        AuditEvent stateEvent = onlyEvent(execution.getId(), AuditEventType.STATE_TRANSITION);
        assertThat(stateEvent.getActor()).isEqualTo("ops-1");
        assertThat(stateEvent.getDetail()).contains("CANCELLED");
    }

    @Test
    void cancelAtHitlGateRejectsPendingReviewAndBlocksLaterDecision() {
        PipelineExecution execution = stateManager.createExecution("fake-gated", "1.0.0", null);
        driveUntil(execution.getId(), ExecutionStatus.AWAITING_HITL);
        HitlReview review = reviews.findByExecutionIdAndStatus(execution.getId(), HitlReviewStatus.PENDING).getFirst();

        actionService.cancel(execution.getId(), "ops-1", null);

        HitlReview reloaded = reviews.findById(review.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(HitlReviewStatus.REJECTED);
        assertThat(reloaded.getDecision()).isEqualTo("CANCELLED");
        assertThat(reloaded.getComments()).isEqualTo("execution cancelled");
        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);

        assertThatThrownBy(() -> decisionService.decide(review.getId(), HitlDecision.APPROVE, "qa", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been decided");
    }

    @Test
    void cancelledRunningExecutionRefusesLateWriters() {
        PipelineExecution execution = stateManager.createExecution("fake-simple", "1.0.0", null);
        claimUntilRunning(execution.getId());

        actionService.cancel(execution.getId(), "ops-1", "abort");
        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);

        stateManager.scheduleRetry(execution.getId(), 0, "late failure");
        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);

        stateManager.transition(execution.getId(), ExecutionStatus.FAILED_ESCALATED, Map.of());
        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void cancelCascadesToNonTerminalChildren() {
        PipelineExecution parent = stateManager.createExecution("fake-parent", "1.0.0", null);
        driveParentToAwaitSubflow(parent.getId());
        PipelineExecution child = executions.findByParentExecutionId(parent.getId()).getFirst();
        assertThat(child.getStatus().isTerminal()).isFalse();

        actionService.cancel(parent.getId(), "ops-1", null);

        assertThat(stateManager.get(parent.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(stateManager.get(child.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void cancelCompletedExecutionIsRefused() {
        PipelineExecution execution = stateManager.createExecution("fake-simple", "1.0.0", null);
        driveUntil(execution.getId(), ExecutionStatus.COMPLETED);

        assertThatThrownBy(() -> actionService.cancel(execution.getId(), "ops-1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already COMPLETED");
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

    @Test
    void rerunOfCompletedStartsNewExecutionWithSamePayload() {
        PipelineExecution source = stateManager.createExecution("fake-simple", "1.0.0", "{\"seed\":\"x\"}");
        driveUntil(source.getId(), ExecutionStatus.COMPLETED);

        UUID newId = actionService.rerun(source.getId(), "ops-1");

        assertThat(newId).isNotEqualTo(source.getId());
        PipelineExecution rerun = stateManager.get(newId);
        assertThat(rerun.getStatus()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(rerun.getFlowId()).isEqualTo("fake-simple");
        assertThat(rerun.getDedupKey()).isNull();
        assertThat(jsonMapper.readTree(rerun.getTriggerPayload()))
                .isEqualTo(jsonMapper.readTree(source.getTriggerPayload()));

        AuditEvent rerunEvent = onlyEvent(newId, AuditEventType.EXECUTION_RERUN);
        assertThat(rerunEvent.getActor()).isEqualTo("ops-1");
        assertThat(rerunEvent.getDetail()).contains(source.getId().toString());
    }

    @Test
    void rerunOfNonTerminalExecutionIsRefused() {
        PipelineExecution execution = stateManager.createExecution("fake-simple", "1.0.0", null);

        assertThatThrownBy(() -> actionService.rerun(execution.getId(), "ops-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only finished executions can be re-run");
    }

    @Test
    void actionsValidateActorAndExecutionExistence() {
        assertThatThrownBy(() -> actionService.cancel(UUID.randomUUID(), " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> actionService.cancel(UUID.randomUUID(), "ops-1", null))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThatThrownBy(() -> actionService.rerun(UUID.randomUUID(), "ops-1"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }
}
