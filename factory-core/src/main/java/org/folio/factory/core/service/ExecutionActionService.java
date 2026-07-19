package org.folio.factory.core.service;

import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.trigger.PipelineRouter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Operator-initiated execution actions (cancel, re-run), shared by the REST and UI
 * layers. Mirrors {@link org.folio.factory.core.hitl.HitlDecisionService}: the state
 * change and its audit record commit in one transaction, and every mutation flows
 * through {@link StateManager} so the final-state guards apply.
 */
@Service
public class ExecutionActionService {

    private final PipelineExecutionRepository executions;
    private final StateManager stateManager;
    private final HitlReviewRepository reviews;
    private final AuditLog auditLog;
    private final PipelineRouter router;
    private final JsonMapper jsonMapper;

    public ExecutionActionService(PipelineExecutionRepository executions, StateManager stateManager,
                                  HitlReviewRepository reviews, AuditLog auditLog, PipelineRouter router,
                                  JsonMapper jsonMapper) {
        this.executions = executions;
        this.stateManager = stateManager;
        this.reviews = reviews;
        this.auditLog = auditLog;
        this.router = router;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Cancels a non-terminal execution: transitions it to CANCELLED, rejects any
     * pending review in its inbox, and cascades to non-terminal children. The
     * StateManager final-state guard makes this safe against an in-flight worker —
     * its late writes to the cancelled execution are refused.
     */
    @Transactional
    public PipelineExecution cancel(UUID executionId, String actor, String reason) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
        PipelineExecution execution = load(executionId);
        if (execution.getStatus().isTerminal()) {
            throw new IllegalStateException("Execution " + executionId + " is already " + execution.getStatus());
        }

        Map<String, ?> detail = reason == null ? Map.of() : Map.of("reason", reason);
        stateManager.transition(executionId, ExecutionStatus.CANCELLED, actor, detail);
        auditLog.record(executionId, AuditEventType.EXECUTION_CANCELLED, null, actor, detail);

        for (HitlReview review : reviews.findByExecutionIdAndStatus(executionId, HitlReviewStatus.PENDING)) {
            review.decide(HitlReviewStatus.REJECTED, "CANCELLED", actor,
                    reason != null ? reason : "execution cancelled", null);
            reviews.save(review);
        }

        for (PipelineExecution child : executions.findByParentExecutionId(executionId)) {
            if (!child.getStatus().isTerminal()) {
                cancel(child.getId(), actor, reason);
            }
        }

        return load(executionId);
    }

    /**
     * Starts a fresh execution of the same flow as {@code sourceExecutionId} with the
     * source's trigger payload. The re-run deliberately opts out of dedup (null dedup
     * key) so it is never collapsed onto the terminal source. Router input-schema and
     * daily-budget checks still apply and may throw.
     */
    @Transactional
    public UUID rerun(UUID sourceExecutionId, String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
        PipelineExecution source = load(sourceExecutionId);
        if (!source.getStatus().isTerminal()) {
            throw new IllegalStateException("Only finished executions can be re-run");
        }

        String payloadJson = source.getTriggerPayload();
        JsonNode payload = payloadJson == null || payloadJson.isBlank() ? null : jsonMapper.readTree(payloadJson);
        UUID newId = router.routeManual(source.getFlowId(), payload, null);
        auditLog.record(newId, AuditEventType.EXECUTION_RERUN, null, actor,
                Map.of("sourceExecutionId", sourceExecutionId.toString()));
        return newId;
    }

    private PipelineExecution load(UUID executionId) {
        return executions.findById(executionId)
                .orElseThrow(() -> new NoSuchElementException("No pipeline execution " + executionId));
    }
}
