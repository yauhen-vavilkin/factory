package org.folio.factory.app.web;

import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/executions")
public class ExecutionController {

    private final PipelineExecutionRepository executions;
    private final ArtifactStore artifactStore;
    private final AuditLog auditLog;

    public ExecutionController(PipelineExecutionRepository executions, ArtifactStore artifactStore,
                               AuditLog auditLog) {
        this.executions = executions;
        this.artifactStore = artifactStore;
        this.auditLog = auditLog;
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

    @GetMapping
    public List<ExecutionSummary> list() {
        return executions.findAllByOrderByCreatedAtDesc().stream().map(this::toSummary).toList();
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
