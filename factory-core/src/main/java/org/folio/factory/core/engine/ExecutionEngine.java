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
 *
 * <p>Outcome layers are deliberately separate and must not be conflated:
 * (1) <em>engine termination</em> — this state machine's {@code COMPLETED}
 * status only means every step of the flow ran and terminated normally;
 * (2) <em>model outcome</em> — what the coding harness's own run ended with
 * (e.g. steps/time budget exhausted vs. model stopped with a final report);
 * (3) <em>validated task outcome</em> — whether the run's result is a useful
 * outcome for the filed task contract. A {@code COMPLETED} execution may
 * carry a FAILED model outcome or a FAILED validated task outcome in its
 * artifacts (e.g. report.md {@code task_outcome}); consumers of run results
 * must read that field rather than infer success from engine termination.</p>
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
    private final List<StepRecoveryStore> recoveryStores;
    private final JsonMapper jsonMapper;

    public ExecutionEngine(FlowRegistry flowRegistry,
                           AgentWorkerRegistry workerRegistry,
                           StateManager stateManager,
                           ArtifactStore artifactStore,
                           AuditLog auditLog,
                           HitlGateOpener hitlGateOpener,
                           SubFlowInvoker subFlowInvoker,
                           List<StepPostProcessor> postProcessors,
                           JsonMapper jsonMapper,
                           List<StepRecoveryStore> recoveryStores) {
        this.flowRegistry = flowRegistry;
        this.workerRegistry = workerRegistry;
        this.stateManager = stateManager;
        this.artifactStore = artifactStore;
        this.auditLog = auditLog;
        this.hitlGateOpener = hitlGateOpener;
        this.subFlowInvoker = subFlowInvoker;
        this.postProcessors = postProcessors;
        this.jsonMapper = jsonMapper;
        this.recoveryStores = recoveryStores;
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
        // Durable attempt ordinal (T25): monotonic per (executionId, stepId),
        // allocated from the persisted attempt_counts map and committed here —
        // strictly before buildContext/worker.execute — so it is the SOLE
        // attempt identity: a crash mid-attempt burns the ordinal and no
        // requeue (ordinary retry or escalation-approved, whose budget reset
        // deliberately does not touch it) can ever reissue a prior one.
        // Computed once and reused for the audit, the worker context (T25
        // recovery-bundle key and workspace owner), and the bundle discard.
        int attempt = stateManager.nextAttempt(executionId, step.stepId());
        auditLog.record(executionId, AuditEventType.STEP_STARTED, step.stepId(),
                Map.of("workerId", step.workerId(), "attempt", attempt));
        try {
            AgentWorker worker = workerRegistry.require(step.workerId());
            AgentContext context = buildContext(execution, step, attempt);
            AgentResult result = worker.execute(context);
            requireDeclaredOutputs(step, result);
            for (StepPostProcessor postProcessor : postProcessors) {
                postProcessor.process(flow, step, result.outputs());
            }
            persistOutputs(executionId, step, result);
            // Only a fully persisted attempt is acknowledged: every declared
            // output reached the artifact store, so the worker-published
            // recovery bundle for exactly this attempt is disposable. A
            // discard failure must never fail the step — a leaked bundle is
            // the safe direction, destruction-before-ack is not.
            for (StepRecoveryStore recoveryStore : recoveryStores) {
                try {
                    recoveryStore.discardAcknowledged(executionId, step.stepId(), attempt);
                } catch (Exception discardFailure) {
                    log.warn("Could not discard acknowledged recovery bundle of execution {} step '{}' attempt {}: {}",
                            executionId, step.stepId(), attempt, discardFailure.getMessage(), discardFailure);
                }
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

    /**
     * Persists every produced output; a failure rethrows as an
     * {@link AgentExecutionException} carrying the worker-published
     * {@code recovery_locator} so the existing step-failure channel (STEP_FAILED
     * audit, retry/escalation errorMessage) points at the preserved bundle.
     * The recovery bundle is deliberately NOT discarded on this path. The
     * same channel already carries worker-side terminal failures: a worker
     * whose run died after coding work exists publishes its INCOMPLETE
     * bundle (failure_reason {@code TERMINAL_EXCEPTION}, workspace snapshot
     * attached) before any teardown, so its surfaced failure points at the
     * preserved copy — or, when publication itself failed, is the worker's
     * fail-closed retention error naming both retained locations.
     */
    private void persistOutputs(UUID executionId, StepDescriptor step, AgentResult result) {
        String persisting = null;
        try {
            for (Map.Entry<String, String> output : result.outputs().entrySet()) {
                persisting = output.getKey();
                artifactStore.putMarkdown(executionId, output.getKey(), output.getValue(), step.stepId());
            }
        } catch (Exception e) {
            Object locator = result.metrics() == null ? null : result.metrics().get("recovery_locator");
            StringBuilder message = new StringBuilder("Persisting outputs of step '").append(step.stepId())
                    .append("' failed");
            if (persisting != null) {
                message.append(" at artifact '").append(persisting).append("'");
            }
            if (locator != null) {
                message.append("; worker-published recovery bundle: ").append(locator);
            }
            throw new AgentExecutionException(message.toString(), e);
        }
    }

    private AgentContext buildContext(PipelineExecution execution, StepDescriptor step, int attempt) {
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
                step.config(), step.outputs(), attempt);
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
