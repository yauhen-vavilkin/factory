package org.folio.factory.core.hitl;

import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.engine.HitlGateOpener;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Applies human review decisions. The decision is recorded transactionally and the
 * execution is returned to PENDING where applicable — the poller resumes it, so no
 * pipeline work happens on the request thread.
 */
@Service
public class HitlDecisionService {

    private final HitlReviewRepository reviews;
    private final StateManager stateManager;
    private final ArtifactStore artifactStore;
    private final AuditLog auditLog;
    private final List<ArtifactAmendmentValidator> amendmentValidators;
    private final JsonMapper jsonMapper;

    public HitlDecisionService(HitlReviewRepository reviews, StateManager stateManager,
                               ArtifactStore artifactStore, AuditLog auditLog,
                               List<ArtifactAmendmentValidator> amendmentValidators, JsonMapper jsonMapper) {
        this.reviews = reviews;
        this.stateManager = stateManager;
        this.artifactStore = artifactStore;
        this.auditLog = auditLog;
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
        Map<String, String> amendments = amendedArtifacts == null ? Map.of() : amendedArtifacts;

        switch (decision) {
            case APPROVE -> {
                review.decide(HitlReviewStatus.APPROVED, decision.name(), reviewer, comments, null);
                resume(review, escalation);
            }
            case AMEND -> {
                if (amendments.isEmpty()) {
                    throw new IllegalArgumentException("AMEND decision requires amendedArtifacts");
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
                resume(review, escalation);
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

    private void resume(HitlReview review, boolean escalation) {
        UUID executionId = review.getExecutionId();
        if (escalation) {
            // Approving an escalation means "try the failed step again": reset the
            // step's retry budget and requeue at the same step index.
            String stepId = jsonMapper.readTree(review.getReviewPackage()).path("stepId").asString();
            if (stepId == null || stepId.isBlank()) {
                throw new IllegalStateException(
                        "Cannot determine failed step for escalation review " + review.getId());
            }
            stateManager.resetRetry(executionId, stepId);
        } else {
            stateManager.advanceStep(executionId);
        }
        stateManager.scheduleRetry(executionId, 0, null);
    }
}
