package org.folio.factory.devfactory.feedback;
import org.folio.factory.core.hitl.*;
import org.folio.factory.core.domain.*;
import org.folio.factory.core.registry.FlowRegistry;
import java.util.*;

/** Generic review endpoints may never invent a paid feedback command or amend trusted receipts. */
public class FeedbackDecisionPolicy implements HitlDecisionPolicy {
    private final FlowRegistry flows;
    private record Authorization(UUID reviewId, HitlDecision decision) { }
    private final ThreadLocal<Authorization> authorized = new ThreadLocal<>();
    public FeedbackDecisionPolicy(FlowRegistry flows) { this.flows = flows; }
    public void validate(HitlReview review, PipelineExecution execution, HitlDecision decision, Map<String,String> amendments) {
        if (!execution.getFlowId().equals("dev-factory")) return;
        var flow = flows.require(execution.getFlowId());
        if (!flow.hasStep(review.getStepIndex()) || !flow.step(review.getStepIndex()).stepId().equals(FeedbackArtifacts.STEP)) return;
        if (!new Authorization(review.getId(),decision).equals(authorized.get()) || !amendments.isEmpty())
            throw new IllegalArgumentException("Use the PR feedback actions on the execution page");
    }
    public boolean cancelOnReject(HitlReview review, PipelineExecution execution) {
        if (!execution.getFlowId().equals("dev-factory")) return false;
        var flow = flows.require(execution.getFlowId());
        return flow.hasStep(review.getStepIndex())
                && flow.step(review.getStepIndex()).stepId().equals(FeedbackArtifacts.STEP)
                && new Authorization(review.getId(),HitlDecision.REJECT).equals(authorized.get());
    }
    void authorize(UUID review, HitlDecision decision, Runnable action) {
        if (authorized.get() != null) throw new IllegalStateException("Nested review command");
        authorized.set(new Authorization(review,decision));
        try { action.run(); } finally { authorized.remove(); }
    }
    void authorize(UUID review, Runnable action) { authorize(review,HitlDecision.APPROVE,action); }
}
