package org.folio.factory.core.hitl;

import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.PipelineExecution;
import java.util.Map;

/** Flow-owned restrictions, applied to every entry point inside the decision transaction. */
public interface HitlDecisionPolicy {
    void validate(HitlReview review, PipelineExecution execution, HitlDecision decision,
                  Map<String, String> amendments);
    /** A flow may turn its own authorized rejection into cancellation. */
    default boolean cancelOnReject(HitlReview review, PipelineExecution execution) { return false; }
}
