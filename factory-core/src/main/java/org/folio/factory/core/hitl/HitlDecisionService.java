package org.folio.factory.core.hitl;

import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.engine.HitlGateOpener;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Applies human review decisions. The decision is recorded transactionally and the
 * execution is returned to PENDING where applicable — the poller resumes it, so no
 * pipeline work happens on the request thread.
 *
 * <p>Stale or duplicate decisions are rejected: the execution must still be
 * waiting at the review's recorded step (optimistic locking on the review row
 * additionally prevents two concurrent decisions from both committing).</p>
 */
@Service
public class HitlDecisionService {

    private final HitlReviewRepository reviews;
    private final StateManager stateManager;
    private final ArtifactStore artifactStore;
    private final AuditLog auditLog;
    private final FlowRegistry flowRegistry;
    private final List<ArtifactAmendmentValidator> amendmentValidators;
    private final JsonMapper jsonMapper;

    public HitlDecisionService(HitlReviewRepository reviews, StateManager stateManager,
                               ArtifactStore artifactStore, AuditLog auditLog, FlowRegistry flowRegistry,
                               List<ArtifactAmendmentValidator> amendmentValidators, JsonMapper jsonMapper) {
        this.reviews = reviews;
        this.stateManager = stateManager;
        this.artifactStore = artifactStore;
        this.auditLog = auditLog;
        this.flowRegistry = flowRegistry;
        this.amendmentValidators = amendmentValidators;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public HitlReview decide(UUID reviewId, HitlDecision decision, String reviewer,
                             String comments, Map<String, String> amendedArtifacts) {
        HitlReview review = reviews.findById(reviewId)
                .orElseThrow(() -> new NoSuchElementException("No HITL review " + reviewId));
        if (review.getStatus() != HitlReviewStatus.PENDING) {
            throw new IllegalStateException("Review " + reviewId + " has already been decided ("
                    + review.getStatus() + ")");
        }
        if (reviewer == null || reviewer.isBlank()) {
            throw new IllegalArgumentException("reviewer is required");
        }
        boolean escalation = HitlGateOpener.ESCALATION_GATE_ID.equals(review.getGateId());
        PipelineExecution execution = stateManager.get(review.getExecutionId());
        requireExecutionStillAtReview(review, execution, escalation);

        switch (decision) {
            case APPROVE -> {
                review.decide(HitlReviewStatus.APPROVED, decision.name(), reviewer, comments, null);
                resume(review, execution, escalation);
            }
            case AMEND -> {
                Map<String, String> amendments = changedAmendments(review, amendedArtifacts);
                if (amendments.isEmpty()) {
                    throw new IllegalArgumentException(
                            "AMEND decision requires amendedArtifacts with changed content");
                }
                for (Map.Entry<String, String> amendment : amendments.entrySet()) {
                    for (ArtifactAmendmentValidator validator : amendmentValidators) {
                        validator.validate(amendment.getKey(), amendment.getValue());
                    }
                    artifactStore.putMarkdown(review.getExecutionId(), amendment.getKey(),
                            amendment.getValue(), "hitl:" + reviewer);
                }
                review.decide(HitlReviewStatus.AMENDED, decision.name(), reviewer, comments,
                        jsonMapper.writeValueAsString(amendments.keySet()));
                resume(review, execution, escalation);
            }
            case REJECT -> {
                review.decide(HitlReviewStatus.REJECTED, decision.name(), reviewer, comments, null);
                stateManager.transition(review.getExecutionId(),
                        escalation ? ExecutionStatus.CANCELLED : ExecutionStatus.REJECTED,
                        Map.of("gateId", review.getGateId(), "reviewer", reviewer));
            }
        }
        reviews.save(review);
        auditLog.record(review.getExecutionId(), AuditEventType.HITL_DECIDED, null, reviewer,
                Map.of("reviewId", reviewId.toString(), "gateId", review.getGateId(),
                        "decision", decision.name()));
        return review;
    }

    /**
     * A decision is only valid while the execution is still parked where this
     * review was opened; anything else means the review is stale (duplicate gate
     * from a recovered run, or the execution was already resumed elsewhere).
     */
    private void requireExecutionStillAtReview(HitlReview review, PipelineExecution execution,
                                               boolean escalation) {
        if (escalation) {
            if (execution.getStatus() != ExecutionStatus.FAILED_ESCALATED) {
                throw new IllegalStateException("Execution " + execution.getId()
                        + " is not awaiting escalation handling (status " + execution.getStatus() + ")");
            }
            return;
        }
        if (execution.getStatus() != ExecutionStatus.AWAITING_HITL
                || execution.getCurrentStepIndex() != review.getStepIndex()) {
            throw new IllegalStateException("Execution " + execution.getId()
                    + " is no longer waiting at gate '" + review.getGateId() + "' (status "
                    + execution.getStatus() + ", step " + execution.getCurrentStepIndex() + ")");
        }
    }

    /**
     * Drops amendments whose content is identical to the current artifact version,
     * so re-submitting unchanged review-package content never pollutes the
     * immutable artifact history with no-op versions.
     */
    private Map<String, String> changedAmendments(HitlReview review, Map<String, String> amendedArtifacts) {
        Map<String, String> changed = new LinkedHashMap<>();
        if (amendedArtifacts == null) {
            return changed;
        }
        for (Map.Entry<String, String> amendment : amendedArtifacts.entrySet()) {
            String current = artifactStore.getLatest(review.getExecutionId(), amendment.getKey())
                    .map(a -> a.getContent()).orElse("");
            if (!normalise(current).equals(normalise(amendment.getValue()))) {
                changed.put(amendment.getKey(), amendment.getValue());
            }
        }
        return changed;
    }

    private String normalise(String content) {
        return content == null ? "" : content.replace("\r\n", "\n").strip();
    }

    private void resume(HitlReview review, PipelineExecution execution, boolean escalation) {
        UUID executionId = review.getExecutionId();
        if (escalation) {
            // Approving an escalation means "try the failed step again": reset the
            // step's retry budget and requeue at the same step index. The step id
            // is resolved from durable state (the review's recorded position in
            // the flow descriptor), never from display JSON.
            String stepId = flowRegistry.require(execution.getFlowId())
                    .step(review.getStepIndex()).stepId();
            stateManager.resetRetry(executionId, stepId);
        } else if (!stateManager.advanceStep(executionId, review.getStepIndex(),
                ExecutionStatus.AWAITING_HITL)) {
            throw new IllegalStateException("Execution " + executionId
                    + " moved while the decision was being applied; please re-review");
        }
        stateManager.scheduleRetry(executionId, 0, null);
    }
}
