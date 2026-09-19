package org.folio.factory.app.web;

import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Server-rendered execution views: a filterable, paged run list and a
 * per-execution detail page (step progress, artifact versions, child runs, audit
 * timeline). All view state is computed here so the templates stay dumb.
 */
@Controller
public class ExecutionUiController {

    private static final int DEFAULT_SIZE = 20;

    private final PipelineExecutionRepository executions;
    private final ArtifactStore artifactStore;
    private final AuditLog auditLog;
    private final FlowRegistry flowRegistry;
    private final HitlReviewRepository reviews;
    private final JsonMapper jsonMapper;

    public ExecutionUiController(PipelineExecutionRepository executions, ArtifactStore artifactStore,
                                 AuditLog auditLog, FlowRegistry flowRegistry, HitlReviewRepository reviews,
                                 JsonMapper jsonMapper) {
        this.executions = executions;
        this.artifactStore = artifactStore;
        this.auditLog = auditLog;
        this.flowRegistry = flowRegistry;
        this.reviews = reviews;
        this.jsonMapper = jsonMapper;
    }

    @GetMapping("/executions")
    public String executions(@RequestParam(name = "status", required = false) String status,
                             @RequestParam(name = "flow", required = false) String flow,
                             @RequestParam(name = "page", defaultValue = "0") int page,
                             @RequestParam(name = "size", defaultValue = "20") int size,
                             Model model) {
        int clampedSize = PageValidation.clampSize(size, DEFAULT_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampedSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        ExecutionStatus parsedStatus = UiFormat.enumOrNull(ExecutionStatus.class, status);
        boolean hasStatus = parsedStatus != null;
        boolean hasFlow = flow != null && !flow.isBlank();

        Page<PipelineExecution> result = executions.search(parsedStatus, flow, pageable);

        model.addAttribute("executions", result.getContent().stream().map(this::executionRow).toList());
        model.addAttribute("page", result);
        model.addAttribute("statuses", ExecutionStatus.values());
        model.addAttribute("flows", flowRegistry.all().stream().map(FlowDescriptor::id).toList());
        model.addAttribute("selectedStatus", hasStatus ? parsedStatus.name() : "");
        model.addAttribute("selectedFlow", hasFlow ? flow : "");
        model.addAttribute("filtersActive", hasStatus || hasFlow);
        model.addAttribute("baseUrl", "/executions?status=" + UiFormat.encode(hasStatus ? parsedStatus.name() : "")
                + "&flow=" + UiFormat.encode(hasFlow ? flow : "") + "&size=" + clampedSize);
        return "executions";
    }

    @GetMapping("/executions/{id}")
    public String execution(@PathVariable("id") UUID id, Model model) {
        PipelineExecution execution = executions.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No execution " + id));
        boolean terminal = execution.getStatus().isTerminal();

        List<AuditEvent> events = auditLog.forExecution(id);
        Map<String, long[]> tokensByStep = tokensByStep(events);
        List<Artifact> artifacts = artifactStore.allForExecution(id);
        var steps = stepStates(execution, tokensByStep);
        model.addAttribute("progressSteps", steps);
        if ("dev-factory".equals(execution.getFlowId())) {
            var now = java.time.Instant.now();
            var developer = new DeveloperExecutionView(jsonMapper).build(execution, artifacts, events, now);
            model.addAttribute("developer", developer);
            model.addAttribute("progressSteps", developer.get("phases"));
            var flow = flowRegistry.find(execution.getFlowId()).orElse(null);
            if (flow != null) for (int i = 0; i < steps.size(); i++) {
                String stepId = flow.agentChain().get(i).stepId();
                steps.get(i).put("label", DeveloperExecutionView.technicalStepLabel(stepId));
                String duration = DeveloperExecutionView.technicalStepDuration(stepId, events, execution, now);
                steps.get(i).put("sublabel", duration);
                if (i == execution.getCurrentStepIndex() && execution.getStatus() == ExecutionStatus.RUNNING && duration.endsWith("s"))
                    steps.get(i).put("elapsedStart", now.minusSeconds(Long.parseLong(duration.substring(0, duration.length() - 1))));
            }
        }
        java.math.BigDecimal cost = null;
        for (var event : events) if (event.getEventType() == AuditEventType.STEP_COMPLETED) {
            var value = jsonMapper.readTree(event.getDetail() == null ? "{}" : event.getDetail()).path("costUsd");
            if (value.isNumber()) cost = (cost == null ? java.math.BigDecimal.ZERO : cost).add(value.decimalValue());
        }
        model.addAttribute("reportedCost", cost == null ? null : cost.toPlainString());
        model.addAttribute("auditCount", events.size());
        model.addAttribute("artifactCount", artifacts.size());

        model.addAttribute("execution", execution);
        model.addAttribute("steps", steps);
        model.addAttribute("tokenTotals", tokenTotals(tokensByStep));
        model.addAttribute("artifactGroups", artifactGroups(artifacts));
        model.addAttribute("children", childRows(execution, id));
        model.addAttribute("pendingReview", pendingReview(execution, id));
        model.addAttribute("events", events.stream()
                .map(event -> UiFormat.auditRow(event, jsonMapper)).toList());
        model.addAttribute("pollUrl", terminal ? null : "/api/executions/" + id);
        return "execution";
    }

    /**
     * Token usage per step, read from the {@code STEP_COMPLETED} audit rows already
     * loaded for the timeline. A retried step emits one completion row, so counts
     * never double up; steps that reported nothing are simply absent from the map.
     */
    private Map<String, long[]> tokensByStep(List<AuditEvent> events) {
        Map<String, long[]> byStep = new LinkedHashMap<>();
        for (AuditEvent event : events) {
            if (event.getEventType() != AuditEventType.STEP_COMPLETED || event.getStepId() == null) {
                continue;
            }
            JsonNode detail = jsonMapper.readTree(event.getDetail() == null ? "{}" : event.getDetail());
            long prompt = detail.path("promptTokens").asLong(0);
            long completion = detail.path("completionTokens").asLong(0);
            if (prompt + completion > 0) {
                byStep.put(event.getStepId(), new long[]{prompt, completion});
            }
        }
        return byStep;
    }

    private static Map<String, Object> tokenTotals(Map<String, long[]> tokensByStep) {
        long prompt = tokensByStep.values().stream().mapToLong(t -> t[0]).sum();
        long completion = tokensByStep.values().stream().mapToLong(t -> t[1]).sum();
        Map<String, Object> totals = new LinkedHashMap<>();
        // A run with no LLM step renders nothing rather than a row of zeroes.
        totals.put("present", prompt + completion > 0);
        totals.put("prompt", UiFormat.count(prompt));
        totals.put("completion", UiFormat.count(completion));
        totals.put("total", UiFormat.count(prompt + completion));
        return totals;
    }

    private Map<String, Object> executionRow(PipelineExecution execution) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", execution.getId());
        row.put("idShort", UiFormat.abbreviate(execution.getId().toString()));
        row.put("flowId", execution.getFlowId());
        row.put("status", execution.getStatus());
        row.put("currentStepIndex", execution.getCurrentStepIndex());
        row.put("createdAt", UiFormat.format(execution.getCreatedAt()));
        row.put("updatedAt", UiFormat.format(execution.getUpdatedAt()));
        row.put("parentExecutionId", execution.getParentExecutionId());
        row.put("parentShort", execution.getParentExecutionId() == null ? null
                : UiFormat.abbreviate(execution.getParentExecutionId().toString()));
        return row;
    }

    private List<Map<String, Object>> stepStates(PipelineExecution execution, Map<String, long[]> tokensByStep) {
        FlowDescriptor flow = flowRegistry.find(execution.getFlowId()).orElse(null);
        if (flow == null) {
            return List.of();
        }
        JsonNode retryCounts = jsonMapper.readTree(execution.getRetryCounts());
        List<StepDescriptor> chain = flow.agentChain();
        List<Map<String, Object>> steps = new ArrayList<>(chain.size());
        for (int i = 0; i < chain.size(); i++) {
            StepDescriptor step = chain.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", step.type().name());
            row.put("label", UiFormat.stepLabel(step));
            row.put("sublabel", step.stepId() + " · " + step.type());
            row.put("state", stepState(i, execution));
            row.put("attempts", retryCounts.path(step.stepId()).asInt(0));
            long[] tokens = tokensByStep.get(step.stepId());
            row.put("tokens", tokens == null ? null
                    : UiFormat.count(tokens[0] + tokens[1]) + " tokens ("
                            + UiFormat.count(tokens[0]) + " in / " + UiFormat.count(tokens[1]) + " out)");
            steps.add(row);
        }
        return steps;
    }

    private static String stepState(int index, PipelineExecution execution) {
        int current = execution.getCurrentStepIndex();
        if (index < current) {
            return "done";
        }
        if (index > current) {
            return "pending";
        }
        return execution.getStatus() == ExecutionStatus.FAILED_ESCALATED ? "failed" : "current";
    }

    private List<Map<String, Object>> artifactGroups(List<Artifact> artifacts) {
        Map<String, List<Map<String, Object>>> byName = new LinkedHashMap<>();
        int panel = 0;
        for (Artifact artifact : artifacts) {
            Map<String, Object> version = new LinkedHashMap<>();
            version.put("version", artifact.getVersion());
            version.put("content", artifact.getContent());
            version.put("createdBy", artifact.getCreatedBy());
            version.put("label", "v" + artifact.getVersion() + " · " + artifact.getCreatedBy()
                    + " · " + UiFormat.format(artifact.getCreatedAt()));
            version.put("panelId", "artifact-" + panel++);
            version.put("latest", false);
            byName.computeIfAbsent(artifact.getName(), n -> new ArrayList<>()).add(version);
        }
        List<Map<String, Object>> groups = new ArrayList<>(byName.size());
        for (Map.Entry<String, List<Map<String, Object>>> entry : byName.entrySet()) {
            List<Map<String, Object>> versions = entry.getValue();
            versions.get(versions.size() - 1).put("latest", true);
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("name", entry.getKey());
            group.put("versions", versions);
            groups.add(group);
        }
        return groups;
    }

    private List<Map<String, Object>> childRows(PipelineExecution parent, UUID id) {
        FlowDescriptor flow = flowRegistry.find(parent.getFlowId()).orElse(null);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PipelineExecution child : executions.findByParentExecutionId(id)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", child.getId());
            row.put("idShort", UiFormat.abbreviate(child.getId().toString()));
            row.put("flowId", child.getFlowId());
            row.put("status", child.getStatus());
            row.put("stepLabel", childStepLabel(flow, child.getParentStepIndex()));
            rows.add(row);
        }
        return rows;
    }

    private static String childStepLabel(FlowDescriptor flow, Integer parentStepIndex) {
        if (flow == null || parentStepIndex == null || !flow.hasStep(parentStepIndex)) {
            return null;
        }
        return "step " + parentStepIndex + " · " + UiFormat.stepLabel(flow.step(parentStepIndex));
    }

    private HitlReview pendingReview(PipelineExecution execution, UUID id) {
        // Both an awaiting-gate run and an escalated run carry a decidable pending
        // review; surface it on the detail page in either state.
        if (execution.getStatus() != ExecutionStatus.AWAITING_HITL
                && execution.getStatus() != ExecutionStatus.FAILED_ESCALATED) {
            return null;
        }
        return reviews.findByExecutionIdOrderByCreatedAtAsc(id).stream()
                .filter(review -> review.getStatus() == HitlReviewStatus.PENDING)
                .findFirst()
                .orElse(null);
    }
}
