package org.folio.factory.core.engine;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.agent.AgentWorkerRegistry;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The control plane's state machine: advances a pipeline execution through its
 * flow descriptor's agent chain, one step at a time. Entirely data-driven — the
 * engine has no knowledge of any specific flow.
 *
 * <p>Deliberately NOT transactional as a whole: agent workers can run for
 * minutes, so each state mutation (artifact writes, step advancement, retry
 * bookkeeping) commits in its own short transaction via the core services. A
 * crash mid-step is recovered by the poller's lease reaper; re-running a step
 * writes new artifact versions rather than corrupting old ones.</p>
 */
@Component
public class ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    private final FlowRegistry flowRegistry;
    private final AgentWorkerRegistry workerRegistry;
    private final StateManager stateManager;
    private final ArtifactStore artifactStore;
    private final AuditLog auditLog;
    private final HitlGateOpener hitlGateOpener;
    private final SubFlowInvoker subFlowInvoker;
    private final List<StepPostProcessor> postProcessors;
    private final JsonMapper jsonMapper;

    public ExecutionEngine(FlowRegistry flowRegistry,
                           AgentWorkerRegistry workerRegistry,
                           StateManager stateManager,
                           ArtifactStore artifactStore,
                           AuditLog auditLog,
                           HitlGateOpener hitlGateOpener,
                           SubFlowInvoker subFlowInvoker,
                           List<StepPostProcessor> postProcessors,
                           JsonMapper jsonMapper) {
        this.flowRegistry = flowRegistry;
        this.workerRegistry = workerRegistry;
        this.stateManager = stateManager;
        this.artifactStore = artifactStore;
        this.auditLog = auditLog;
        this.hitlGateOpener = hitlGateOpener;
        this.subFlowInvoker = subFlowInvoker;
        this.postProcessors = postProcessors;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Advances the execution until it pauses (HITL gate, sub-flow, retry backoff),
     * fails, or completes. The execution must already be in RUNNING status.
     */
    public void advance(UUID executionId) {
        try {
            while (true) {
                PipelineExecution execution = stateManager.get(executionId);
                if (execution.getStatus() != ExecutionStatus.RUNNING) {
                    return;
                }
                FlowDescriptor flow = flowRegistry.require(execution.getFlowId());
                if (!flow.hasStep(execution.getCurrentStepIndex())) {
                    complete(execution);
                    return;
                }
                StepDescriptor step = flow.step(execution.getCurrentStepIndex());
                boolean advanced = switch (step.type()) {
                    case AGENT -> runAgentStep(execution, flow, step);
                    case HITL_GATE -> {
                        hitlGateOpener.openGate(execution, step);
                        yield false;
                    }
                    case SUB_FLOW -> {
                        subFlowInvoker.invoke(execution, step);
                        yield false;
                    }
                };
                if (!advanced) {
                    return;
                }
            }
        } catch (Exception e) {
            // Defensive net for engine bugs and unknown flows — never leave an
            // execution stuck in RUNNING.
            log.error("Engine failure while advancing execution {}", executionId, e);
            failTerminally(executionId, e);
        }
    }

    private boolean runAgentStep(PipelineExecution execution, FlowDescriptor flow, StepDescriptor step) {
        UUID executionId = execution.getId();
        stateManager.heartbeat(executionId);
        auditLog.record(executionId, AuditEventType.STEP_STARTED, step.stepId(),
                Map.of("workerId", step.workerId(), "attempt", stateManager.retryCount(executionId, step.stepId()) + 1));
        try {
            AgentWorker worker = workerRegistry.require(step.workerId());
            AgentContext context = buildContext(execution, step);
            AgentResult result = worker.execute(context);
            requireDeclaredOutputs(step, result);
            for (StepPostProcessor postProcessor : postProcessors) {
                postProcessor.process(flow, step, result.outputs());
            }
            for (Map.Entry<String, String> output : result.outputs().entrySet()) {
                artifactStore.putMarkdown(executionId, output.getKey(), output.getValue(), step.stepId());
            }
            auditLog.record(executionId, AuditEventType.STEP_COMPLETED, step.stepId(), result.metrics());
            // Guarded advance: if a duplicate driver (lease-reaped run) moved the
            // execution meanwhile, stop instead of double-advancing past a step.
            return stateManager.advanceStep(executionId, execution.getCurrentStepIndex(), ExecutionStatus.RUNNING);
        } catch (Exception e) {
            handleStepFailure(execution, flow, step, e);
            return false;
        }
    }

    private AgentContext buildContext(PipelineExecution execution, StepDescriptor step) {
        Map<String, ArtifactContent> inputs = new HashMap<>();
        for (String inputName : step.inputs()) {
            if (StepDescriptor.TRIGGER_INPUT.equals(inputName)) {
                continue;
            }
            var artifact = artifactStore.getLatest(execution.getId(), inputName)
                    .orElseThrow(() -> new AgentExecutionException(
                            "Step '" + step.stepId() + "' requires artifact '" + inputName + "' which does not exist"));
            inputs.put(inputName, new ArtifactContent(artifact.getName(), artifact.getVersion(),
                    artifact.getContentType(), artifact.getContent()));
        }
        JsonNode triggerPayload = null;
        if (step.wantsTriggerPayload() && execution.getTriggerPayload() != null) {
            triggerPayload = jsonMapper.readTree(execution.getTriggerPayload());
        }
        return new AgentContext(execution.getId(), step.stepId(), inputs, triggerPayload,
                step.config(), step.outputs());
    }

    private void requireDeclaredOutputs(StepDescriptor step, AgentResult result) {
        for (String declared : step.outputs()) {
            if (!result.outputs().containsKey(declared)) {
                throw new AgentExecutionException(
                        "Worker '" + step.workerId() + "' did not produce declared output artifact '" + declared + "'");
            }
        }
    }

    private void handleStepFailure(PipelineExecution execution, FlowDescriptor flow, StepDescriptor step, Exception e) {
        UUID executionId = execution.getId();
        String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        auditLog.record(executionId, AuditEventType.STEP_FAILED, step.stepId(), Map.of("error", error));
        int attempts = stateManager.incrementRetry(executionId, step.stepId());
        if (attempts < flow.retryPolicy().maxAttempts()) {
            long backoff = flow.retryPolicy().backoffFor(attempts);
            stateManager.scheduleRetry(executionId, backoff, error);
            auditLog.record(executionId, AuditEventType.RETRY_SCHEDULED, step.stepId(),
                    Map.of("attempt", attempts, "backoffSeconds", backoff));
            log.warn("Step '{}' of execution {} failed (attempt {}); retrying in {}s: {}",
                    step.stepId(), executionId, attempts, backoff, error);
        } else {
            stateManager.transition(executionId, ExecutionStatus.FAILED_ESCALATED, Map.of("stepId", step.stepId()));
            auditLog.record(executionId, AuditEventType.ESCALATED, step.stepId(),
                    Map.of("attempts", attempts, "error", error));
            hitlGateOpener.openEscalationReview(stateManager.get(executionId), step, error, attempts);
            log.error("Step '{}' of execution {} exhausted its retry budget after {} attempts; escalated to human review",
                    step.stepId(), executionId, attempts);
        }
    }

    private void complete(PipelineExecution execution) {
        stateManager.transition(execution.getId(), ExecutionStatus.COMPLETED, null);
        subFlowInvoker.onChildCompleted(execution.getId());
    }

    private void failTerminally(UUID executionId, Exception e) {
        try {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            stateManager.transition(executionId, ExecutionStatus.FAILED_ESCALATED, Map.of("engineError", error));
            auditLog.record(executionId, AuditEventType.ESCALATED, null, Map.of("engineError", error));
        } catch (Exception secondary) {
            log.error("Could not record terminal failure for execution {}", executionId, secondary);
        }
    }
}
