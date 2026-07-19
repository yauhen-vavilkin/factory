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

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

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

    /**
     * Pumps the engine until the execution reaches the expected quiescent state.
     * Condition-based like SubFlowIntegrationTest#driveUntilTerminal: a freshly
     * (re)scheduled row can be momentarily unclaimable while its JVM-clock
     * next_run_at is ahead of the database clock under load, so a fixed number of
     * sleepless rounds races that skew instead of absorbing it.
     */
    private void driveUntil(UUID executionId, ExecutionStatus expected) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
            claimService.claim(10).forEach(engine::advance);
            assertThat(stateManager.get(executionId).getStatus()).isEqualTo(expected);
        });
    }

    private HitlReview pendingReview(UUID executionId) {
        return reviews.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
                .filter(r -> r.getStatus() == HitlReviewStatus.PENDING)
                .findFirst().orElseThrow();
    }

    @Test
    void approveResumesPastGate() {
        PipelineExecution execution = stateManager.createExecution("fake-gated", "1.0.0", "{\"k\":\"v\"}");
        driveUntil(execution.getId(), ExecutionStatus.AWAITING_HITL);

        HitlReview review = pendingReview(execution.getId());
        assertThat(review.getGateId()).isEqualTo("gate-1");
        assertThat(review.getReviewPackage()).contains("Review the draft").contains("draft.md");

        decisionService.decide(review.getId(), HitlDecision.APPROVE, "qa-lead", "looks good", null);
        driveUntil(execution.getId(), ExecutionStatus.COMPLETED);

        assertThat(artifactStore.getLatest(execution.getId(), "final.md")).isPresent();
        assertThat(artifactStore.getLatest(execution.getId(), "draft.md").orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void amendWritesNewArtifactVersionAttributedToReviewerAndDownstreamUsesIt() {
        PipelineExecution execution = stateManager.createExecution("fake-gated", "1.0.0", null);
        driveUntil(execution.getId(), ExecutionStatus.AWAITING_HITL);

        HitlReview review = pendingReview(execution.getId());
        decisionService.decide(review.getId(), HitlDecision.AMEND, "qa-lead", "tightened wording",
                Map.of("draft.md", "reviewer-approved draft content"));
        driveUntil(execution.getId(), ExecutionStatus.COMPLETED);

        var draft = artifactStore.getLatest(execution.getId(), "draft.md").orElseThrow();
        assertThat(draft.getVersion()).isEqualTo(2);
        assertThat(draft.getCreatedBy()).isEqualTo("hitl:qa-lead");
        var finalArtifact = artifactStore.getLatest(execution.getId(), "final.md").orElseThrow();
        assertThat(finalArtifact.getContent()).contains("reviewer-approved draft content");
    }

    @Test
    void rejectTerminatesExecution() {
        PipelineExecution execution = stateManager.createExecution("fake-gated", "1.0.0", null);
        driveUntil(execution.getId(), ExecutionStatus.AWAITING_HITL);

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
        driveUntil(execution.getId(), ExecutionStatus.FAILED_ESCALATED);
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
        driveUntil(execution.getId(), ExecutionStatus.FAILED_ESCALATED);

        HitlReview escalation = pendingReview(execution.getId());
        decisionService.decide(escalation.getId(), HitlDecision.REJECT, "tech-lead", "give up", null);

        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void staleReviewForMovedOnExecutionIsRejected() {
        PipelineExecution execution = stateManager.createExecution("fake-gated", "1.0.0", null);
        driveUntil(execution.getId(), ExecutionStatus.AWAITING_HITL);
        HitlReview review = pendingReview(execution.getId());

        // A duplicate pending review at the same gate (e.g. from a recovered
        // duplicate run): once the first decision advances the execution, the
        // second must be refused instead of force-advancing again.
        HitlReview duplicate = reviews.save(new HitlReview(
                execution.getId(), review.getGateId(), review.getStepIndex(), review.getReviewPackage()));
        decisionService.decide(review.getId(), HitlDecision.APPROVE, "qa-lead", null, null);

        assertThatThrownBy(() -> decisionService.decide(duplicate.getId(), HitlDecision.APPROVE, "qa-2", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer waiting");
    }

    @Test
    void amendWithUnchangedContentIsRejectedWithoutNewVersions() {
        PipelineExecution execution = stateManager.createExecution("fake-gated", "1.0.0", null);
        driveUntil(execution.getId(), ExecutionStatus.AWAITING_HITL);
        HitlReview review = pendingReview(execution.getId());
        String currentContent = artifactStore.getLatest(execution.getId(), "draft.md").orElseThrow().getContent();

        assertThatThrownBy(() -> decisionService.decide(review.getId(), HitlDecision.AMEND, "qa-lead",
                null, Map.of("draft.md", currentContent + "\r\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("changed content");
        assertThat(artifactStore.getLatest(execution.getId(), "draft.md").orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void amendRequiresContentAndValidReviewer() {
        PipelineExecution execution = stateManager.createExecution("fake-gated", "1.0.0", null);
        driveUntil(execution.getId(), ExecutionStatus.AWAITING_HITL);
        HitlReview review = pendingReview(execution.getId());

        assertThatThrownBy(() -> decisionService.decide(review.getId(), HitlDecision.AMEND, "qa", null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amendedArtifacts");
        assertThatThrownBy(() -> decisionService.decide(review.getId(), HitlDecision.APPROVE, " ", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewer");
    }
}
