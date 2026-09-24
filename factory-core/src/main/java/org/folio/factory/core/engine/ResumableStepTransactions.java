package org.folio.factory.core.engine;

import org.folio.factory.core.agent.*;
import org.folio.factory.core.domain.*;
import org.folio.factory.core.registry.model.*;
import org.folio.factory.core.repository.*;
import org.folio.factory.core.service.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/** Short, fenced transactions for explicitly opted-in invocations only. */
@Service
public class ResumableStepTransactions {
    private final PipelineExecutionRepository executions;
    private final HitlReviewRepository reviews;
    private final StateManager state;
    private final ArtifactStore artifacts;
    private final AuditLog audit;
    private final HitlGateOpener gates;
    private final List<StepPostProcessor> processors;
    private final JsonMapper json;

    public ResumableStepTransactions(PipelineExecutionRepository executions, HitlReviewRepository reviews,
            StateManager state, ArtifactStore artifacts, AuditLog audit, HitlGateOpener gates,
            List<StepPostProcessor> processors, JsonMapper json) {
        this.executions = executions; this.reviews = reviews; this.state = state; this.artifacts = artifacts;
        this.audit = audit; this.gates = gates; this.processors = processors; this.json = json;
    }

    private PipelineExecution owned(UUID id, int step, long version) {
        var execution = executions.lockById(id).orElseThrow();
        if (execution.getStatus() != ExecutionStatus.RUNNING || execution.getCurrentStepIndex() != step
                || execution.getVersion() != version) throw new StaleInvocation();
        return execution;
    }

    @Transactional
    public boolean start(UUID id, int index, long version, StepDescriptor step, UUID invocation,
                         Map<String, ArtifactContent> inputs) {
        owned(id, index, version);
        Map<String, Integer> versions = new TreeMap<>();
        inputs.forEach((name, value) -> versions.put(name, value.version()));
        String inputKey = ArtifactStore.sha256(json.writeValueAsString(versions));
        boolean replay = audit.forExecution(id).stream()
                .filter(e -> e.getEventType() == AuditEventType.STEP_STARTED && step.stepId().equals(e.getStepId()))
                .anyMatch(e -> inputKey.equals(json.readTree(e.getDetail()).path("inputKey").asString("")));
        audit.record(id, AuditEventType.STEP_STARTED, step.stepId(), Map.of("invocationId", invocation.toString(),
                "inputKey", inputKey, "inputVersions", versions, "workerId", step.workerId(), "replay", replay));
        return replay;
    }

    @Transactional
    public void check(UUID id, int index, long version) { owned(id, index, version); }

    @Transactional
    public void progress(UUID id, int index, long version, String step, Map<String, Object> detail) {
        owned(id, index, version);
        audit.record(id, AuditEventType.RUNTIME_PROGRESS, step, detail);
    }

    @Transactional
    public boolean complete(UUID id, int index, long version, UUID invocation, FlowDescriptor flow,
                            StepDescriptor step, AgentResult result) {
        var execution = owned(id, index, version);
        if (!new HashSet<>(step.outputs()).equals(result.outputs().keySet()))
            throw new AgentExecutionException("Resumable result must contain exactly the declared outputs");
        for (var processor : processors) processor.process(flow, step, result.outputs());
        // A post-processor may write state in this transaction; never publish after it changed ownership.
        if (execution.getStatus() != ExecutionStatus.RUNNING || execution.getCurrentStepIndex() != index)
            throw new StaleInvocation();
        for (var output : result.outputs().entrySet())
            artifacts.putMarkdown(id, output.getKey(), output.getValue(), step.stepId());
        state.resetRetry(id, step.stepId());
        var detail = new LinkedHashMap<String, Object>(result.metrics());
        detail.put("invocationId", invocation.toString());
        detail.put("disposition", result.disposition().name());
        audit.record(id, AuditEventType.STEP_COMPLETED, step.stepId(), detail);
        switch (result.disposition()) {
            case ADVANCE -> state.advanceStep(id, index, ExecutionStatus.RUNNING);
            case CONTINUE -> state.scheduleRetry(id, 0, null);
            case WAIT -> {
                var request = result.waitRequest();
                if (!step.outputs().contains(request.stateArtifact()))
                    throw new IllegalArgumentException("Wait state must be a declared output");
                var artifact = artifacts.getLatest(id, request.stateArtifact()).orElseThrow();
                var pkg = new LinkedHashMap<String, Object>();
                pkg.put("gateId", request.gate().gateId());
                pkg.put("title", request.gate().title());
                pkg.put("instructions", request.gate().reviewInstructions());
                pkg.put("flowId", execution.getFlowId());
                pkg.put("resumeSameStep", true);
                pkg.put("stateArtifact", artifact.getName());
                pkg.put("stateVersion", artifact.getVersion());
                pkg.put("invocationId", invocation.toString());
                pkg.put("artifacts", List.of());
                state.transition(id, ExecutionStatus.AWAITING_HITL, Map.of("gateId", request.gate().gateId()));
                var review = reviews.save(new HitlReview(id, request.gate().gateId(), index, json.writeValueAsString(pkg)));
                audit.record(id, AuditEventType.HITL_REQUESTED, step.stepId(),
                        Map.of("gateId", request.gate().gateId(), "reviewId", review.getId().toString()));
            }
        }
        return result.disposition() == AgentResult.Disposition.ADVANCE;
    }

    @Transactional
    public void fail(UUID id, int index, long version, UUID invocation, FlowDescriptor flow,
                     StepDescriptor step, Exception failure) {
        var execution = owned(id, index, version);
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        audit.record(id, AuditEventType.STEP_FAILED, step.stepId(),
                Map.of("invocationId", invocation.toString(), "error", message));
        int attempts = state.incrementRetry(id, step.stepId());
        if (attempts < flow.retryPolicy().maxAttempts()) {
            long delay = flow.retryPolicy().backoffFor(attempts);
            state.scheduleRetry(id, delay, message);
            audit.record(id, AuditEventType.RETRY_SCHEDULED, step.stepId(), Map.of("attempt", attempts));
        } else gates.openEscalationReview(execution, step, message, attempts);
    }

    public static class StaleInvocation extends IllegalStateException {
        public StaleInvocation() { super("Invocation no longer owns this execution"); }
    }
}
