package org.folio.factory.core.engine;

import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Flow composition: creates child executions for SUB_FLOW steps and returns their
 * outputs to the waiting parent when they complete. The parent stays in
 * AWAITING_SUBFLOW for the child's entire lifecycle, including any HITL gates the
 * child opens.
 */
@Component
public class SubFlowInvoker {

    private static final Logger log = LoggerFactory.getLogger(SubFlowInvoker.class);

    private final FlowRegistry flowRegistry;
    private final StateManager stateManager;
    private final ArtifactStore artifactStore;
    private final AuditLog auditLog;
    private final HitlGateOpener hitlGateOpener;

    public SubFlowInvoker(FlowRegistry flowRegistry, StateManager stateManager,
                          ArtifactStore artifactStore, AuditLog auditLog, HitlGateOpener hitlGateOpener) {
        this.flowRegistry = flowRegistry;
        this.stateManager = stateManager;
        this.artifactStore = artifactStore;
        this.auditLog = auditLog;
        this.hitlGateOpener = hitlGateOpener;
    }

    @Transactional
    public UUID invoke(PipelineExecution parent, StepDescriptor step) {
        var spec = step.subFlow();
        FlowDescriptor childFlow = flowRegistry.require(spec.flowId());
        PipelineExecution child = stateManager.createChildExecution(
                childFlow.id(), childFlow.version(), parent.getTriggerPayload(),
                parent.getId(), parent.getCurrentStepIndex());

        for (Map.Entry<String, String> mapping : spec.inputMapping().entrySet()) {
            var parentArtifact = artifactStore.getLatest(parent.getId(), mapping.getKey())
                    .orElseThrow(() -> new AgentExecutionException("Sub-flow step '" + step.stepId()
                            + "' maps parent artifact '" + mapping.getKey() + "' which does not exist"));
            artifactStore.put(child.getId(), mapping.getValue(), parentArtifact.getContent(),
                    parentArtifact.getContentType(), "subflow:" + parent.getId());
        }

        stateManager.transition(parent.getId(), ExecutionStatus.AWAITING_SUBFLOW,
                Map.of("childExecutionId", child.getId().toString(), "childFlowId", childFlow.id()));
        auditLog.record(parent.getId(), AuditEventType.SUBFLOW_INVOKED, step.stepId(),
                Map.of("childExecutionId", child.getId().toString(), "childFlowId", childFlow.id()));
        return child.getId();
    }

    /**
     * Called whenever an execution completes. If it is a child, its mapped outputs
     * are copied up and the parent resumes.
     */
    @Transactional
    public void onChildCompleted(UUID childExecutionId) {
        PipelineExecution child = stateManager.get(childExecutionId);
        if (child.getParentExecutionId() == null) {
            return;
        }
        PipelineExecution parent = stateManager.get(child.getParentExecutionId());
        if (parent.getStatus() != ExecutionStatus.AWAITING_SUBFLOW) {
            log.warn("Child execution {} completed but parent {} is in status {}",
                    childExecutionId, parent.getId(), parent.getStatus());
            return;
        }
        FlowDescriptor parentFlow = flowRegistry.require(parent.getFlowId());
        StepDescriptor step = parentFlow.step(child.getParentStepIndex());
        if (step.type() != StepType.SUB_FLOW) {
            throw new IllegalStateException("Parent step " + child.getParentStepIndex() + " of flow '"
                    + parentFlow.id() + "' is not a SUB_FLOW step");
        }
        for (Map.Entry<String, String> mapping : step.subFlow().outputMapping().entrySet()) {
            var childArtifact = artifactStore.getLatest(child.getId(), mapping.getKey())
                    .orElseThrow(() -> new AgentExecutionException("Sub-flow '" + child.getFlowId()
                            + "' completed without producing mapped output artifact '" + mapping.getKey() + "'"));
            artifactStore.put(parent.getId(), mapping.getValue(), childArtifact.getContent(),
                    childArtifact.getContentType(), "subflow:" + child.getId());
        }
        auditLog.record(parent.getId(), AuditEventType.SUBFLOW_RETURNED, step.stepId(),
                Map.of("childExecutionId", child.getId().toString()));
        if (!stateManager.advanceStep(parent.getId(), child.getParentStepIndex(),
                ExecutionStatus.AWAITING_SUBFLOW)) {
            return;
        }
        stateManager.scheduleRetry(parent.getId(), 0, null);
    }

    /**
     * Recovers parents whose child completed but whose hand-off was lost (process
     * death between the child's COMPLETED transition and the parent resume).
     * Invoked periodically by the poller; onChildCompleted is safe to repeat —
     * the guarded advance refuses duplicates.
     */
    @Transactional
    public void reconcileCompletedChildren() {
        for (PipelineExecution child : stateManager.findCompletedChildrenWithWaitingParent()) {
            log.info("Recovering lost sub-flow completion: child {} of parent {}",
                    child.getId(), child.getParentExecutionId());
            onChildCompleted(child.getId());
        }
    }

    /**
     * Escalates parents whose child terminated without completing (rejected at a
     * gate, failed, cancelled). Invoked periodically by the poller so the check is
     * restart-safe and independent of where the child's terminal transition
     * happened.
     */
    @Transactional
    public void escalateParentsOfTerminatedChildren() {
        for (PipelineExecution child : stateManager.findTerminatedChildren()) {
            PipelineExecution parent = stateManager.get(child.getParentExecutionId());
            if (parent.getStatus() != ExecutionStatus.AWAITING_SUBFLOW) {
                continue;
            }
            // Surface the dead-end in the review inbox: approving the escalation
            // resets the SUB_FLOW step and re-invokes the sub-flow. openEscalationReview
            // performs the FAILED_ESCALATED transition and inserts the review atomically;
            // a null return means the parent resolved first, so record nothing.
            StepDescriptor subFlowStep = flowRegistry.require(parent.getFlowId())
                    .step(child.getParentStepIndex());
            HitlReview review = hitlGateOpener.openEscalationReview(parent, subFlowStep,
                    "Sub-flow '" + child.getFlowId() + "' (execution " + child.getId()
                            + ") terminated with status " + child.getStatus(), 1);
            if (review != null) {
                auditLog.record(parent.getId(), AuditEventType.ESCALATED, null,
                        Map.of("reason", "sub-flow terminated without completing",
                                "childExecutionId", child.getId().toString(),
                                "childStatus", child.getStatus().name()));
            }
        }
    }
}
