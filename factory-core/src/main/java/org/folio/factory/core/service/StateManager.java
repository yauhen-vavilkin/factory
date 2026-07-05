package org.folio.factory.core.service;

import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Owns every pipeline execution state transition. All mutating operations load the
 * execution inside the current transaction, so callers can safely pass ids from
 * any context. Each transition is persisted in the same transaction as its audit
 * event.
 */
@Service
public class StateManager {

    private static final TypeReference<Map<String, Integer>> RETRY_COUNTS_TYPE = new TypeReference<>() {
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

    @Transactional(readOnly = true)
    public PipelineExecution get(UUID executionId) {
        return load(executionId);
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

    @Transactional
    public void advanceStep(UUID executionId) {
        PipelineExecution execution = load(executionId);
        execution.setCurrentStepIndex(execution.getCurrentStepIndex() + 1);
        executions.save(execution);
    }

    private PipelineExecution load(UUID executionId) {
        return executions.findById(executionId)
                .orElseThrow(() -> new NoSuchElementException("No pipeline execution " + executionId));
    }

    private Map<String, Integer> readRetryCounts(PipelineExecution execution) {
        String json = execution.getRetryCounts();
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        return new HashMap<>(jsonMapper.readValue(json, RETRY_COUNTS_TYPE));
    }
}
