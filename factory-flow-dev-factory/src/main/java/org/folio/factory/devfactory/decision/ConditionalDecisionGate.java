package org.folio.factory.devfactory.decision;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.engine.HitlGateOpener;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.HitlGateSpec;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.StateManager;

import java.util.List;
import java.util.Map;

/**
 * Opens a normal Factory review for the AGENT step that is currently running.
 *
 * <p>{@link HitlGateOpener} parks the execution in AWAITING_HITL at the current
 * step index. When the worker returns, the engine's guarded advance (which expects
 * RUNNING) refuses to move, so the execution waits. The existing
 * {@code HitlDecisionService} then advances past this step on APPROVE/AMEND, or
 * rejects the execution. The calling worker must declare no outputs: anything it
 * returned would be written after the review package was built.</p>
 */
public class ConditionalDecisionGate {

    private final StateManager stateManager;
    private final FlowRegistry flowRegistry;
    private final HitlGateOpener gateOpener;
    private final HitlReviewRepository reviews;

    public ConditionalDecisionGate(StateManager stateManager,
                                   FlowRegistry flowRegistry, HitlGateOpener gateOpener,
                                   HitlReviewRepository reviews) {
        this.stateManager = stateManager;
        this.flowRegistry = flowRegistry;
        this.gateOpener = gateOpener;
        this.reviews = reviews;
    }

    /**
     * @return true when this call opened the review; false when a review for this
     *         step already exists or the execution is no longer running here
     */
    public boolean open(AgentContext context, HitlGateSpec gate) {
        PipelineExecution execution = stateManager.get(context.executionId());
        int stepIndex = execution.getCurrentStepIndex();
        boolean reviewExists = reviews.findByExecutionIdOrderByCreatedAtAsc(execution.getId()).stream()
                .anyMatch(r -> r.getStepIndex() == stepIndex && r.getGateId().equals(gate.gateId())
                        && r.getStatus() == HitlReviewStatus.PENDING);
        if (reviewExists || execution.getStatus() != ExecutionStatus.RUNNING) {
            return false;
        }
        StepDescriptor current = flowRegistry.require(execution.getFlowId()).step(stepIndex);
        if (current.type() != StepType.AGENT || !current.stepId().equals(context.stepId())) {
            throw new AgentExecutionException("Decision gate called from step '" + context.stepId()
                    + "' but the execution is at step '" + current.stepId() + "'");
        }
        StepDescriptor gateStep = new StepDescriptor(current.stepId(), StepType.HITL_GATE, null, gate, null,
                current.inputs(), List.of(), Map.of());
        return gateOpener.openGate(execution, gateStep) != null;
    }
}
