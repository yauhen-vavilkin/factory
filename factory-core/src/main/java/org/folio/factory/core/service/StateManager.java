package org.folio.factory.core.service;

import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns every pipeline execution state transition. All mutating operations load the
 * execution inside the current transaction, so callers can safely pass ids from
 * any context. Each transition is persisted in the same transaction as its audit
 * event.
 */
@Service
public class StateManager {

    private static final Logger log = LoggerFactory.getLogger(StateManager.class);

    private static final TypeReference<Map<String, Integer>> STRING_INT_MAP_TYPE = new TypeReference<>() {
    };

    private final PipelineExecutionRepository executions;
    private final AuditLog auditLog;
    private final JsonMapper jsonMapper;

    public StateManager(PipelineExecutionRepository executions, AuditLog auditLog, JsonMapper jsonMapper) {
        this.executions = executions;
        this.auditLog = auditLog;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public PipelineExecution createExecution(String flowId, String flowVersion, String triggerPayloadJson) {
        PipelineExecution execution = new PipelineExecution(flowId, flowVersion, triggerPayloadJson);
        PipelineExecution saved = executions.save(execution);
        auditLog.record(saved.getId(), AuditEventType.EXECUTION_STARTED, null,
                Map.of("flowId", flowId, "flowVersion", flowVersion));
        return saved;
    }

    @Transactional
    public PipelineExecution createChildExecution(String flowId, String flowVersion, String triggerPayloadJson,
                                                  UUID parentExecutionId, int parentStepIndex) {
        PipelineExecution execution = new PipelineExecution(flowId, flowVersion, triggerPayloadJson);
        execution.setParentExecutionId(parentExecutionId);
        execution.setParentStepIndex(parentStepIndex);
        PipelineExecution saved = executions.save(execution);
        auditLog.record(saved.getId(), AuditEventType.EXECUTION_STARTED, null,
                Map.of("flowId", flowId, "flowVersion", flowVersion,
                        "parentExecutionId", parentExecutionId.toString()));
        return saved;
    }

    @Transactional
    public PipelineExecution createAdmittedExecution(String flowId, String flowVersion,
                                                     String triggerPayloadJson, String admissionKey) {
        PipelineExecution execution = new PipelineExecution(flowId, flowVersion, triggerPayloadJson);
        execution.setAdmissionKey(admissionKey);
        PipelineExecution saved = executions.saveAndFlush(execution);
        auditLog.record(saved.getId(), AuditEventType.EXECUTION_STARTED, null,
                Map.of("flowId", flowId, "flowVersion", flowVersion, "admissionKey", admissionKey));
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<PipelineExecution> findAdmitted(String flowId, String admissionKey) {
        return executions.findByFlowIdAndAdmissionKey(flowId, admissionKey);
    }

    @Transactional(readOnly = true)
    public PipelineExecution get(UUID executionId) {
        return load(executionId);
    }

    /**
     * Children that ended without completing while their parent still waits.
     * FAILED_ESCALATED is deliberately NOT terminal here: such a child has a
     * pending escalation review and can still be resumed, so the parent keeps
     * waiting — per the design, a parent waits through its sub-flow's reviews.
     */
    @Transactional(readOnly = true)
    public List<PipelineExecution> findTerminatedChildren() {
        return executions.findChildrenWithWaitingParent(
                List.of(ExecutionStatus.REJECTED, ExecutionStatus.CANCELLED),
                ExecutionStatus.AWAITING_SUBFLOW);
    }

    /**
     * Children that completed but whose parent is still waiting — happens when the
     * process died between the child's COMPLETED transition and the parent
     * hand-off.
     */
    @Transactional(readOnly = true)
    public List<PipelineExecution> findCompletedChildrenWithWaitingParent() {
        return executions.findChildrenWithWaitingParent(
                List.of(ExecutionStatus.COMPLETED), ExecutionStatus.AWAITING_SUBFLOW);
    }

    @Transactional
    public PipelineExecution transition(UUID executionId, ExecutionStatus newStatus, Map<String, ?> auditDetail) {
        PipelineExecution execution = load(executionId);
        ExecutionStatus previous = execution.getStatus();
        execution.setStatus(newStatus);
        PipelineExecution saved = executions.save(execution);
        Map<String, Object> detail = new HashMap<>();
        detail.put("from", previous.name());
        detail.put("to", newStatus.name());
        if (auditDetail != null) {
            detail.putAll(auditDetail);
        }
        auditLog.record(saved.getId(), AuditEventType.STATE_TRANSITION, null, detail);
        if (newStatus == ExecutionStatus.COMPLETED) {
            auditLog.record(saved.getId(), AuditEventType.EXECUTION_COMPLETED, null, null);
        }
        return saved;
    }

    /**
     * Increments the retry count for a step and returns the new count.
     */
    @Transactional
    public int incrementRetry(UUID executionId, String stepId) {
        PipelineExecution execution = load(executionId);
        Map<String, Integer> counts = readRetryCounts(execution);
        int next = counts.merge(stepId, 1, Integer::sum);
        execution.setRetryCounts(jsonMapper.writeValueAsString(counts));
        executions.save(execution);
        return next;
    }

    @Transactional
    public void resetRetry(UUID executionId, String stepId) {
        PipelineExecution execution = load(executionId);
        Map<String, Integer> counts = readRetryCounts(execution);
        counts.remove(stepId);
        execution.setRetryCounts(jsonMapper.writeValueAsString(counts));
        executions.save(execution);
    }

    /**
     * Allocates the durable attempt ordinal for a step: returns 1 on the first
     * call and ascends monotonically on every subsequent call. Independent of
     * the retry budget — {@link #resetRetry} clears only the budget — so an
     * allocated ordinal is never reissued (a crash mid-attempt burns it).
     */
    @Transactional
    public int nextAttempt(UUID executionId, String stepId) {
        PipelineExecution execution = load(executionId);
        Map<String, Integer> counts = readIntMap(execution.getAttemptCounts());
        int next = counts.merge(stepId, 1, Integer::sum);
        execution.setAttemptCounts(jsonMapper.writeValueAsString(counts));
        executions.save(execution);
        return next;
    }

    @Transactional(readOnly = true)
    public int retryCount(UUID executionId, String stepId) {
        return readRetryCounts(load(executionId)).getOrDefault(stepId, 0);
    }

    @Transactional
    public void scheduleRetry(UUID executionId, long backoffSeconds, String errorMessage) {
        PipelineExecution execution = load(executionId);
        execution.setStatus(ExecutionStatus.PENDING);
        execution.setNextRunAt(Instant.now().plusSeconds(backoffSeconds));
        execution.setErrorMessage(errorMessage);
        executions.save(execution);
    }

    /**
     * Advances the step cursor only when the execution is still where the caller
     * believes it is. Protects against duplicate drivers (a lease-reaped run
     * finishing late, a stale HITL decision) silently skipping steps.
     *
     * @return false when the execution has moved on and the caller must stop
     */
    @Transactional
    public boolean advanceStep(UUID executionId, int expectedStepIndex, ExecutionStatus expectedStatus) {
        PipelineExecution execution = load(executionId);
        if (execution.getStatus() != expectedStatus
                || execution.getCurrentStepIndex() != expectedStepIndex) {
            log.warn("Refusing stale step advance for execution {}: expected step {} in {}, found step {} in {}",
                    executionId, expectedStepIndex, expectedStatus,
                    execution.getCurrentStepIndex(), execution.getStatus());
            return false;
        }
        execution.setCurrentStepIndex(expectedStepIndex + 1);
        executions.save(execution);
        return true;
    }

    /**
     * Marks the execution as alive so the lease reaper does not re-queue it while
     * a long-running step (LLM call, test execution) is still in flight.
     */
    @Transactional
    public void heartbeat(UUID executionId) {
        PipelineExecution execution = load(executionId);
        execution.touchUpdatedAt();
        executions.save(execution);
    }

    private PipelineExecution load(UUID executionId) {
        return executions.findById(executionId)
                .orElseThrow(() -> new NoSuchElementException("No pipeline execution " + executionId));
    }

    private Map<String, Integer> readRetryCounts(PipelineExecution execution) {
        return readIntMap(execution.getRetryCounts());
    }

    private Map<String, Integer> readIntMap(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        return new HashMap<>(jsonMapper.readValue(json, STRING_INT_MAP_TYPE));
    }
}
