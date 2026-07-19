package org.folio.factory.core.engine;

import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opens HITL gates: builds the structured review package, persists the pending
 * review, and pauses the execution. Escalations from exhausted retry budgets go
 * through the same review inbox with the reserved gate id {@code escalation}.
 */
@Component
public class HitlGateOpener {

    public static final String ESCALATION_GATE_ID = "escalation";

    private final HitlReviewRepository reviews;
    private final ArtifactStore artifactStore;
    private final StateManager stateManager;
    private final AuditLog auditLog;
    private final JsonMapper jsonMapper;

    public HitlGateOpener(HitlReviewRepository reviews, ArtifactStore artifactStore,
                          StateManager stateManager, AuditLog auditLog, JsonMapper jsonMapper) {
        this.reviews = reviews;
        this.artifactStore = artifactStore;
        this.stateManager = stateManager;
        this.auditLog = auditLog;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public HitlReview openGate(PipelineExecution execution, StepDescriptor step) {
        var gate = step.gate();
        // Transition first, in this transaction: only park at the gate and insert the
        // review when the state change took effect. A concurrent cancel then either
        // commits first (transition refused → no orphan review) or after (its review
        // sweep rejects this one) — never leaves an undecidable PENDING review.
        PipelineExecution current = stateManager.transition(execution.getId(),
                ExecutionStatus.AWAITING_HITL, Map.of("gateId", gate.gateId()));
        if (current.getStatus() != ExecutionStatus.AWAITING_HITL) {
            return null;
        }
        Map<String, Object> reviewPackage = new LinkedHashMap<>();
        reviewPackage.put("gateId", gate.gateId());
        reviewPackage.put("title", gate.title());
        reviewPackage.put("instructions", gate.reviewInstructions());
        reviewPackage.put("flowId", execution.getFlowId());
        reviewPackage.put("artifacts", artifactRefs(execution, gate.reviewedArtifacts()));

        HitlReview review = new HitlReview(execution.getId(), gate.gateId(),
                execution.getCurrentStepIndex(), jsonMapper.writeValueAsString(reviewPackage));
        reviews.save(review);
        auditLog.record(execution.getId(), AuditEventType.HITL_REQUESTED, step.stepId(),
                Map.of("gateId", gate.gateId(), "reviewId", review.getId().toString()));
        return review;
    }

    @Transactional
    public HitlReview openEscalationReview(PipelineExecution execution, StepDescriptor step,
                                           String error, int attempts) {
        // Same ordering as openGate: escalate the state and insert the review in one
        // transaction, and skip the review when the transition is refused (the run
        // resolved first). A null return tells the caller not to attribute an
        // escalation that never happened.
        PipelineExecution current = stateManager.transition(execution.getId(),
                ExecutionStatus.FAILED_ESCALATED, Map.of("stepId", step.stepId()));
        if (current.getStatus() != ExecutionStatus.FAILED_ESCALATED) {
            return null;
        }
        Map<String, Object> reviewPackage = new LinkedHashMap<>();
        reviewPackage.put("gateId", ESCALATION_GATE_ID);
        reviewPackage.put("stepId", step.stepId());
        reviewPackage.put("title", "Escalation: step '" + step.stepId() + "' failed " + attempts + " time(s)");
        reviewPackage.put("instructions",
                "The step exhausted its automated retry budget. Investigate the error, then reject the "
                        + "execution or correct inputs and re-trigger the flow.");
        reviewPackage.put("flowId", execution.getFlowId());
        reviewPackage.put("error", error);
        reviewPackage.put("artifacts", artifactRefs(execution, step.inputs().stream()
                .filter(name -> !StepDescriptor.TRIGGER_INPUT.equals(name)).toList()));

        HitlReview review = new HitlReview(execution.getId(), ESCALATION_GATE_ID,
                execution.getCurrentStepIndex(), jsonMapper.writeValueAsString(reviewPackage));
        reviews.save(review);
        auditLog.record(execution.getId(), AuditEventType.HITL_REQUESTED, step.stepId(),
                Map.of("gateId", ESCALATION_GATE_ID, "reviewId", review.getId().toString()));
        return review;
    }

    private List<Map<String, Object>> artifactRefs(PipelineExecution execution, List<String> names) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (String name : names) {
            artifactStore.getLatest(execution.getId(), name).ifPresent(artifact -> refs.add(ref(artifact)));
        }
        return refs;
    }

    private Map<String, Object> ref(Artifact artifact) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("name", artifact.getName());
        ref.put("version", artifact.getVersion());
        ref.put("contentType", artifact.getContentType());
        ref.put("content", artifact.getContent());
        return ref;
    }
}
