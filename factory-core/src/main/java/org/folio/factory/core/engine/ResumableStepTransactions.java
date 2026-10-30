package org.folio.factory.core.engine;

import lombok.RequiredArgsConstructor;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Short, fenced transactions for explicitly opted-in invocations only. */
@Service
@RequiredArgsConstructor
public class ResumableStepTransactions {
    private final PipelineExecutionRepository executions;
    private final HitlReviewRepository reviews;
    private final StateManager state;
    private final ArtifactStore artifacts;
    private final AuditLog audit;
    private final HitlGateOpener gates;
    private final List<StepPostProcessor> processors;
    private final JsonMapper json;

    private PipelineExecution owned(UUID id, int step, long version) {
        var execution = executions.lockById(id).orElseThrow();
        if (execution.getStatus() != ExecutionStatus.RUNNING || execution.getCurrentStepIndex() != step
                || execution.getVersion() != version) {
            throw new StaleInvocation();
        }
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
    public void check(UUID id, int index, long version) {
        owned(id, index, version);
    }

    @Transactional
    public void progress(UUID id, int index, long version, String step, Map<String, Object> detail) {
        owned(id, index, version);
        audit.record(id, AuditEventType.RUNTIME_PROGRESS, step, detail);
    }

    @Transactional
    public boolean complete(UUID id, int index, long version, UUID invocation, FlowDescriptor flow,
                            StepDescriptor step, AgentResult result) {
        var execution = owned(id, index, version);
        if (!new HashSet<>(step.outputs()).equals(result.outputs().keySet())) {
            throw new AgentExecutionException("Resumable result must contain exactly the declared outputs");
        }
        publishOutputs(execution, index, flow, step, result.outputs());
        state.resetRetry(id, step.stepId());
        auditCompleted(id, step, invocation, result);
        switch (result.disposition()) {
            case ADVANCE -> state.advanceStep(id, index, ExecutionStatus.RUNNING);
            case CONTINUE -> state.scheduleRetry(id, 0, null);
            case WAIT -> openStateReview(execution, index, step, invocation, result.waitRequest());
        }
        return result.disposition() == AgentResult.Disposition.ADVANCE;
    }

    private void publishOutputs(PipelineExecution execution, int index, FlowDescriptor flow, StepDescriptor step,
                                Map<String, String> outputs) {
        for (var processor : processors) {
            processor.process(flow, step, outputs);
        }
        // A post-processor may write state in this transaction; never publish after it changed ownership.
        if (execution.getStatus() != ExecutionStatus.RUNNING || execution.getCurrentStepIndex() != index) {
            throw new StaleInvocation();
        }
        for (var output : outputs.entrySet()) {
            artifacts.putMarkdown(execution.getId(), output.getKey(), output.getValue(), step.stepId());
        }
    }

    private void auditCompleted(UUID id, StepDescriptor step, UUID invocation, AgentResult result) {
        var detail = new LinkedHashMap<String, Object>(result.metrics());
        detail.put("invocationId", invocation.toString());
        detail.put("disposition", result.disposition().name());
        audit.record(id, AuditEventType.STEP_COMPLETED, step.stepId(), detail);
    }

    /** Parks the execution at a review of its own state artifact; approval resumes the same step. */
    private void openStateReview(PipelineExecution execution, int index, StepDescriptor step, UUID invocation,
                                 AgentResult.WaitRequest request) {
        var id = execution.getId();
        if (!step.outputs().contains(request.stateArtifact())) {
            throw new IllegalArgumentException("Wait state must be a declared output");
        }
        var artifact = artifacts.getLatest(id, request.stateArtifact()).orElseThrow();
        var reviewPackage = stateReviewPackage(execution, request, artifact, invocation);
        state.transition(id, ExecutionStatus.AWAITING_HITL, Map.of("gateId", request.gate().gateId()));
        var review = reviews.save(
                new HitlReview(id, request.gate().gateId(), index, json.writeValueAsString(reviewPackage)));
        audit.record(id, AuditEventType.HITL_REQUESTED, step.stepId(),
                Map.of("gateId", request.gate().gateId(), "reviewId", review.getId().toString()));
    }

    private static Map<String, Object> stateReviewPackage(PipelineExecution execution, AgentResult.WaitRequest request,
                                                          Artifact artifact, UUID invocation) {
        var reviewPackage = new LinkedHashMap<String, Object>();
        reviewPackage.put("gateId", request.gate().gateId());
        reviewPackage.put("title", request.gate().title());
        reviewPackage.put("instructions", request.gate().reviewInstructions());
        reviewPackage.put("flowId", execution.getFlowId());
        reviewPackage.put("resumeSameStep", true);
        reviewPackage.put("stateArtifact", artifact.getName());
        reviewPackage.put("stateVersion", artifact.getVersion());
        reviewPackage.put("invocationId", invocation.toString());
        reviewPackage.put("artifacts", List.of());
        return reviewPackage;
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
        } else {
            gates.openEscalationReview(execution, step, message, attempts);
        }
    }

    public static class StaleInvocation extends IllegalStateException {
        public StaleInvocation() {
            super("Invocation no longer owns this execution");
        }
    }
}
