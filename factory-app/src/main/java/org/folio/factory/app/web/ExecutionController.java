package org.folio.factory.app.web;

import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.ExecutionActionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/executions")
public class ExecutionController {

    private final PipelineExecutionRepository executions;
    private final ArtifactStore artifactStore;
    private final AuditLog auditLog;
    private final ExecutionActionService actionService;

    public ExecutionController(PipelineExecutionRepository executions, ArtifactStore artifactStore,
                               AuditLog auditLog, ExecutionActionService actionService) {
        this.executions = executions;
        this.artifactStore = artifactStore;
        this.auditLog = auditLog;
        this.actionService = actionService;
    }

    public record ExecutionSummary(UUID id, String flowId, String flowVersion, ExecutionStatus status,
                                   int currentStepIndex, UUID parentExecutionId, Instant createdAt,
                                   Instant updatedAt, Instant completedAt) {
    }

    public record ArtifactSummary(String name, int version, String contentType, String createdBy,
                                  Instant createdAt, String content) {
    }

    public record AuditEntry(String eventType, String stepId, String actor, String detail, Instant occurredAt) {
    }

    public record ExecutionDetail(ExecutionSummary execution, String errorMessage,
                                  List<ArtifactSummary> artifacts, List<AuditEntry> auditTrail) {
    }

    public record CancelRequest(String actor, String reason) {
    }

    public record RerunRequest(String actor) {
    }

    @GetMapping
    public PageResponse<ExecutionSummary> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "flowId", required = false) String flowId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        Pageable pageable = PageValidation.pageable(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        boolean hasStatus = status != null && !status.isBlank();
        boolean hasFlow = flowId != null && !flowId.isBlank();

        Page<PipelineExecution> result;
        if (hasStatus && hasFlow) {
            result = executions.findByStatusAndFlowId(parseStatus(status), flowId, pageable);
        } else if (hasStatus) {
            result = executions.findByStatus(parseStatus(status), pageable);
        } else if (hasFlow) {
            result = executions.findByFlowId(flowId, pageable);
        } else {
            result = executions.findAll(pageable);
        }
        return PageResponse.of(result, this::toSummary);
    }

    @GetMapping("/{id}")
    public ExecutionDetail get(@PathVariable("id") UUID id) {
        PipelineExecution execution = executions.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No execution " + id));
        List<ArtifactSummary> artifacts = artifactStore.allForExecution(id).stream()
                .map(this::toArtifactSummary).toList();
        List<AuditEntry> audit = auditLog.forExecution(id).stream().map(this::toAuditEntry).toList();
        return new ExecutionDetail(toSummary(execution), execution.getErrorMessage(), artifacts, audit);
    }

    @PostMapping("/{id}/cancel")
    public ExecutionSummary cancel(@PathVariable("id") UUID id, @RequestBody CancelRequest request) {
        return toSummary(actionService.cancel(id, request.actor(), request.reason()));
    }

    @PostMapping("/{id}/rerun")
    public ResponseEntity<Map<String, String>> rerun(@PathVariable("id") UUID id, @RequestBody RerunRequest request) {
        UUID newId = actionService.rerun(id, request.actor());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("executionId", newId.toString()));
    }

    private ExecutionStatus parseStatus(String status) {
        try {
            return ExecutionStatus.valueOf(status.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown execution status '" + status + "'");
        }
    }

    private ExecutionSummary toSummary(PipelineExecution execution) {
        return new ExecutionSummary(execution.getId(), execution.getFlowId(), execution.getFlowVersion(),
                execution.getStatus(), execution.getCurrentStepIndex(), execution.getParentExecutionId(),
                execution.getCreatedAt(), execution.getUpdatedAt(), execution.getCompletedAt());
    }

    private ArtifactSummary toArtifactSummary(Artifact artifact) {
        return new ArtifactSummary(artifact.getName(), artifact.getVersion(), artifact.getContentType(),
                artifact.getCreatedBy(), artifact.getCreatedAt(), artifact.getContent());
    }

    private AuditEntry toAuditEntry(AuditEvent event) {
        return new AuditEntry(event.getEventType().name(), event.getStepId(), event.getActor(),
                event.getDetail(), event.getOccurredAt());
    }
}
