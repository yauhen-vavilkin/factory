package org.folio.factory.app.web;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Server-rendered execution views: a run list and a per-execution timeline of
 * artifacts and audit events. Read-only — actions live behind the REST API.
 */
@Controller
public class ExecutionUiController {

    private static final DateTimeFormatter AUDIT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final PipelineExecutionRepository executions;
    private final ArtifactStore artifactStore;
    private final AuditLog auditLog;

    public ExecutionUiController(PipelineExecutionRepository executions, ArtifactStore artifactStore,
                                 AuditLog auditLog) {
        this.executions = executions;
        this.artifactStore = artifactStore;
        this.auditLog = auditLog;
    }

    @GetMapping("/executions")
    public String executions(Model model) {
        model.addAttribute("executions", executions.findAllByOrderByCreatedAtDesc());
        return "executions";
    }

    @GetMapping("/executions/{id}")
    public String execution(@PathVariable("id") UUID id, Model model) {
        var execution = executions.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No execution " + id));
        model.addAttribute("execution", execution);
        model.addAttribute("artifacts", artifactStore.allForExecution(id));
        model.addAttribute("events", auditLog.forExecution(id).stream().map(this::auditRow).toList());
        return "execution";
    }

    private Map<String, Object> auditRow(AuditEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("occurredAt", event.getOccurredAt() == null ? "" : AUDIT_TIMESTAMP.format(event.getOccurredAt()));
        row.put("eventType", event.getEventType());
        row.put("stepId", event.getStepId());
        row.put("actor", event.getActor());
        row.put("detail", event.getDetail());
        return row;
    }
}
