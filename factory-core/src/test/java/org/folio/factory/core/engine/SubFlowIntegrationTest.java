package org.folio.factory.core.engine;

import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
class SubFlowIntegrationTest {

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
    PipelineExecutionRepository executions;

    @Autowired
    SubFlowInvoker subFlowInvoker;

    @Autowired
    HitlReviewRepository reviews;

    private void drive() {
        for (int rounds = 0; rounds < 20; rounds++) {
            List<UUID> claimed = claimService.claim(10);
            if (claimed.isEmpty()) {
                return;
            }
            claimed.forEach(engine::advance);
        }
        throw new IllegalStateException("Executions still runnable after 20 rounds");
    }

    @Test
    void parentInvokesChildWaitsAndCollectsMappedOutputs() {
        PipelineExecution parent = stateManager.createExecution("fake-parent", "1.0.0", "{\"seed\":\"x\"}");

        // First round: parent runs its first step, then reaches SUB_FLOW and waits;
        // the child is created PENDING and picked up by subsequent claims.
        drive();

        PipelineExecution finishedParent = stateManager.get(parent.getId());
        assertThat(finishedParent.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);

        List<PipelineExecution> children = executions.findByParentExecutionId(parent.getId());
        assertThat(children).hasSize(1);
        PipelineExecution child = children.getFirst();
        assertThat(child.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(child.getFlowId()).isEqualTo("fake-child");
        assertThat(child.getParentStepIndex()).isEqualTo(1);

        // Input mapping: parent_doc.md -> child_input.md
        var childInput = artifactStore.getLatest(child.getId(), "child_input.md").orElseThrow();
        assertThat(childInput.getContent()).contains("echo[parent_doc.md]");
        // Output mapping: child_output.md -> collected.md
        var collected = artifactStore.getLatest(parent.getId(), "collected.md").orElseThrow();
        assertThat(collected.getContent()).contains("child_input.md:");
        assertThat(collected.getCreatedBy()).isEqualTo("subflow:" + child.getId());

        var parentEvents = auditLog.forExecution(parent.getId()).stream()
                .map(e -> e.getEventType()).toList();
        assertThat(parentEvents).containsSubsequence(
                AuditEventType.SUBFLOW_INVOKED,
                AuditEventType.SUBFLOW_RETURNED,
                AuditEventType.EXECUTION_COMPLETED);
    }

    @Test
    void terminatedChildEscalatesWaitingParentWithReviewInInbox() {
        PipelineExecution parent = stateManager.createExecution("fake-parent", "1.0.0", null);

        // Advance parent only up to the sub-flow invocation.
        List<UUID> claimed = claimService.claim(10);
        claimed.stream().filter(id -> id.equals(parent.getId())).forEach(engine::advance);
        assertThat(stateManager.get(parent.getId()).getStatus()).isEqualTo(ExecutionStatus.AWAITING_SUBFLOW);

        PipelineExecution child = executions.findByParentExecutionId(parent.getId()).getFirst();
        stateManager.transition(child.getId(), ExecutionStatus.REJECTED, null);

        subFlowInvoker.escalateParentsOfTerminatedChildren();

        assertThat(stateManager.get(parent.getId()).getStatus()).isEqualTo(ExecutionStatus.FAILED_ESCALATED);
        var parentReviews = reviews.findByExecutionIdOrderByCreatedAtAsc(parent.getId());
        assertThat(parentReviews).hasSize(1);
        assertThat(parentReviews.getFirst().getGateId()).isEqualTo(HitlGateOpener.ESCALATION_GATE_ID);
        assertThat(parentReviews.getFirst().getReviewPackage()).contains("terminated with status REJECTED");
    }

    @Test
    void escalatedChildKeepsParentWaiting() {
        PipelineExecution parent = stateManager.createExecution("fake-parent", "1.0.0", null);
        List<UUID> claimed = claimService.claim(10);
        claimed.stream().filter(id -> id.equals(parent.getId())).forEach(engine::advance);

        PipelineExecution child = executions.findByParentExecutionId(parent.getId()).getFirst();
        // A child awaiting its own escalation review is resumable — not terminal.
        stateManager.transition(child.getId(), ExecutionStatus.FAILED_ESCALATED, null);

        subFlowInvoker.escalateParentsOfTerminatedChildren();

        assertThat(stateManager.get(parent.getId()).getStatus()).isEqualTo(ExecutionStatus.AWAITING_SUBFLOW);
    }

    @Test
    void reconcileRecoversLostChildCompletion() {
        PipelineExecution parent = stateManager.createExecution("fake-parent", "1.0.0", null);
        List<UUID> claimed = claimService.claim(10);
        claimed.stream().filter(id -> id.equals(parent.getId())).forEach(engine::advance);

        // Simulate a crash after the child completed but before the parent
        // hand-off: mark the child COMPLETED directly, bypassing the engine.
        PipelineExecution child = executions.findByParentExecutionId(parent.getId()).getFirst();
        artifactStore.putMarkdown(child.getId(), "child_output.md", "recovered content", "transform");
        stateManager.transition(child.getId(), ExecutionStatus.COMPLETED, null);
        assertThat(stateManager.get(parent.getId()).getStatus()).isEqualTo(ExecutionStatus.AWAITING_SUBFLOW);

        subFlowInvoker.reconcileCompletedChildren();
        drive();

        assertThat(stateManager.get(parent.getId()).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(artifactStore.getLatest(parent.getId(), "collected.md").orElseThrow().getContent())
                .isEqualTo("recovered content");
    }
}
