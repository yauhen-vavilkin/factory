package org.folio.factory.core.hitl;

import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.engine.ExecutionClaimService;
import org.folio.factory.core.engine.ExecutionEngine;
import org.folio.factory.core.engine.HitlGateOpener;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
class HitlDecisionIntegrationTest {

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
    HitlReviewRepository reviews;

    @Autowired
    HitlDecisionService decisionService;

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

    private HitlReview pendingReview(UUID executionId) {
        return reviews.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
                .filter(r -> r.getStatus() == HitlReviewStatus.PENDING)
                .findFirst().orElseThrow();
    }

    @Test
    void approveResumesPastGate() {
        PipelineExecution execution = stateManager.createExecution("fake-gated", "1.0.0", "{\"k\":\"v\"}");
        drive();
        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.AWAITING_HITL);

        HitlReview review = pendingReview(execution.getId());
        assertThat(review.getGateId()).isEqualTo("gate-1");
        assertThat(review.getReviewPackage()).contains("Review the draft").contains("draft.md");

        decisionService.decide(review.getId(), HitlDecision.APPROVE, "qa-lead", "looks good", null);
        drive();

        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(artifactStore.getLatest(execution.getId(), "final.md")).isPresent();
        assertThat(artifactStore.getLatest(execution.getId(), "draft.md").orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void amendWritesNewArtifactVersionAttributedToReviewerAndDownstreamUsesIt() {
        PipelineExecution execution = stateManager.createExecution("fake-gated", "1.0.0", null);
        drive();

        HitlReview review = pendingReview(execution.getId());
        decisionService.decide(review.getId(), HitlDecision.AMEND, "qa-lead", "tightened wording",
                Map.of("draft.md", "reviewer-approved draft content"));
        drive();

        var draft = artifactStore.getLatest(execution.getId(), "draft.md").orElseThrow();
        assertThat(draft.getVersion()).isEqualTo(2);
        assertThat(draft.getCreatedBy()).isEqualTo("hitl:qa-lead");
        var finalArtifact = artifactStore.getLatest(execution.getId(), "final.md").orElseThrow();
        assertThat(finalArtifact.getContent()).contains("reviewer-approved draft content");
        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void rejectTerminatesExecution() {
        PipelineExecution execution = stateManager.createExecution("fake-gated", "1.0.0", null);
        drive();

        HitlReview review = pendingReview(execution.getId());
        decisionService.decide(review.getId(), HitlDecision.REJECT, "qa-lead", "not acceptable", null);

        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(artifactStore.getLatest(execution.getId(), "final.md")).isEmpty();
        assertThatThrownBy(() -> decisionService.decide(review.getId(), HitlDecision.APPROVE, "x", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been decided");
    }

    @Test
    void approvingEscalationResetsRetryBudgetAndRequeues() {
        PipelineExecution execution = stateManager.createExecution("fake-failing", "1.0.0", null);
        drive();
        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.FAILED_ESCALATED);
        assertThat(stateManager.retryCount(execution.getId(), "doomed")).isEqualTo(2);

        HitlReview escalation = pendingReview(execution.getId());
        assertThat(escalation.getGateId()).isEqualTo(HitlGateOpener.ESCALATION_GATE_ID);
        decisionService.decide(escalation.getId(), HitlDecision.APPROVE, "tech-lead", "retry it", null);

        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(stateManager.retryCount(execution.getId(), "doomed")).isZero();
    }

    @Test
    void rejectingEscalationCancelsExecution() {
        PipelineExecution execution = stateManager.createExecution("fake-failing", "1.0.0", null);
        drive();

        HitlReview escalation = pendingReview(execution.getId());
        decisionService.decide(escalation.getId(), HitlDecision.REJECT, "tech-lead", "give up", null);

        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void amendRequiresContentAndValidReviewer() {
        PipelineExecution execution = stateManager.createExecution("fake-gated", "1.0.0", null);
        drive();
        HitlReview review = pendingReview(execution.getId());

        assertThatThrownBy(() -> decisionService.decide(review.getId(), HitlDecision.AMEND, "qa", null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amendedArtifacts");
        assertThatThrownBy(() -> decisionService.decide(review.getId(), HitlDecision.APPROVE, " ", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewer");
    }
}
