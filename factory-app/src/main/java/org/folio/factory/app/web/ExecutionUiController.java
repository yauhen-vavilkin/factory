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
    private final DeveloperCostEstimator costEstimator;

    public ExecutionUiController(PipelineExecutionRepository executions, ArtifactStore artifactStore,
                                 AuditLog auditLog, FlowRegistry flowRegistry, HitlReviewRepository reviews,
                                 JsonMapper jsonMapper, DeveloperCostEstimator costEstimator) {
        this.executions = executions;
        this.artifactStore = artifactStore;
        this.auditLog = auditLog;
        this.flowRegistry = flowRegistry;
        this.reviews = reviews;
        this.jsonMapper = jsonMapper;
        this.costEstimator = costEstimator;
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
        boolean developerFlow = "dev-factory".equals(execution.getFlowId());
        Map<String, ReportedUsage> usageByStep = usageByStep(events, developerFlow);
        List<Artifact> artifacts = artifactStore.allForExecution(id);
        model.addAttribute("displayStatus", developerFlow
                ? new DeveloperExecutionView(jsonMapper).productStatus(execution, artifacts) : execution.getStatus());
        var steps = stepStates(execution, usageByStep);
        model.addAttribute("progressSteps", steps);
        if (developerFlow) {
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
                Long durationSeconds = DeveloperExecutionView.technicalStepDurationSeconds(
                        stepId, events, execution, now);
                if (i == execution.getCurrentStepIndex() && execution.getStatus() == ExecutionStatus.RUNNING
                        && durationSeconds != null)
                    steps.get(i).put("elapsedStart", now.minusSeconds(durationSeconds));
            }
        }
        boolean hasLegacyOnlyUsage = usageByStep.values().stream()
                .anyMatch(usage -> usage.factoryTokensPresent() && !usage.codingTokensPresent());
        DeveloperCostEstimator.Estimate estimate = developerFlow && !hasLegacyOnlyUsage
                ? costEstimator.estimate(usageByStep.values().stream()
                .filter(ReportedUsage::codingTokensPresent)
                .map(usage -> new DeveloperCostEstimator.Usage(usage.provider(), usage.model(), usage.input(),
                        usage.cacheRead(), usage.cacheWrite(), usage.output()))
                .toList()) : null;
        model.addAttribute("estimatedCost", estimate);
        model.addAttribute("auditCount", events.size());
        model.addAttribute("artifactCount", artifacts.size());

        model.addAttribute("execution", execution);
        model.addAttribute("steps", steps);
        model.addAttribute("tokenTotals", tokenTotals(usageByStep, developerFlow));
        model.addAttribute("artifactGroups", artifactGroups(artifacts));
        model.addAttribute("children", childRows(execution, id));
        model.addAttribute("pendingReview", pendingReview(execution, id));
        model.addAttribute("events", events.stream()
                .map(event -> UiFormat.auditRow(event, jsonMapper)).toList());
        model.addAttribute("pollUrl", terminal ? null : "/api/executions/" + id);
        return "execution";
    }

    /**
     * Usage per step from completed metrics, with the latest streamed coding-runtime
     * snapshot as a Developer Flow failure fallback. Completed metrics always win,
     * so a successful run never counts its streamed snapshots again.
     */
    private Map<String, ReportedUsage> usageByStep(List<AuditEvent> events, boolean includeCodingProgress) {
        Map<String, ReportedUsage> completed = new LinkedHashMap<>();
        Map<String, ReportedUsage> progress = new LinkedHashMap<>();
        Map<String, RuntimeIdentity> runtimeIdentity = new LinkedHashMap<>();
        for (AuditEvent event : events) {
            if (event.getStepId() == null) continue;
            JsonNode detail = jsonMapper.readTree(event.getDetail() == null ? "{}" : event.getDetail());
            boolean completedEvent = event.getEventType() == AuditEventType.STEP_COMPLETED;
            String activity = detail.path("activity").asString("");
            if (includeCodingProgress && event.getEventType() == AuditEventType.RUNTIME_PROGRESS
                    && ("pi_starting".equals(activity) || "coding_starting".equals(activity))) {
                runtimeIdentity.put(event.getStepId(), new RuntimeIdentity(
                        text(detail, "provider"), text(detail, "model")));
                continue;
            }
            boolean codingProgress = includeCodingProgress && event.getEventType() == AuditEventType.RUNTIME_PROGRESS
                    && ("pi_usage".equals(activity) || "coding_usage".equals(activity));
            if (!completedEvent && !codingProgress) continue;
            Long prompt = token(detail, "promptTokens");
            Long completion = token(detail, "completionTokens");
            Long input = token(detail, "inputTokens");
            Long cacheRead = token(detail, "cacheReadTokens");
            Long cacheWrite = token(detail, "cacheWriteTokens");
            Long output = token(detail, "outputTokens");
            if (prompt == null && completion == null && input == null && cacheRead == null
                    && cacheWrite == null && output == null) continue;
            RuntimeIdentity identity = codingProgress ? runtimeIdentity.get(event.getStepId()) : null;
            String provider = text(detail, "provider");
            String model = text(detail, "model");
            if (provider == null && identity != null) provider = identity.provider();
            if (model == null && identity != null) model = identity.model();
            var usage = new ReportedUsage(prompt, completion, input, cacheRead, cacheWrite, output,
                    provider, model);
            (codingProgress ? progress : completed).put(event.getStepId(), usage);
        }
        progress.forEach(completed::putIfAbsent);
        return completed;
    }

    private static Long token(JsonNode detail, String key) {
        return detail.path(key).isNumber() ? Math.max(0, detail.path(key).asLong(0)) : null;
    }

    private static String text(JsonNode detail, String key) {
        String value = detail.path(key).asString("").strip();
        return value.isEmpty() ? null : value;
    }

    private static Map<String, Object> tokenTotals(Map<String, ReportedUsage> usageByStep, boolean developerFlow) {
        if (developerFlow && usageByStep.values().stream().anyMatch(ReportedUsage::codingTokensPresent))
            return codingTokenTotals(usageByStep);
        long prompt = usageByStep.values().stream().mapToLong(usage -> value(usage.prompt())).sum();
        long completion = usageByStep.values().stream().mapToLong(usage -> value(usage.completion())).sum();
        Map<String, Object> totals = new LinkedHashMap<>();
        // A run with no LLM step renders nothing rather than a row of zeroes.
        totals.put("present", prompt + completion > 0);
        totals.put("prompt", UiFormat.count(prompt));
        totals.put("completion", UiFormat.count(completion));
        totals.put("total", UiFormat.count(prompt + completion));
        return totals;
    }

    private static Map<String, Object> codingTokenTotals(Map<String, ReportedUsage> usageByStep) {
        boolean inputPresent = usageByStep.values().stream().anyMatch(usage -> usage.input() != null);
        boolean cacheReadPresent = usageByStep.values().stream().anyMatch(usage -> usage.cacheRead() != null);
        boolean cacheWritePresent = usageByStep.values().stream().anyMatch(usage -> usage.cacheWrite() != null);
        boolean outputPresent = usageByStep.values().stream().anyMatch(usage -> usage.output() != null);
        long input = usageByStep.values().stream().mapToLong(usage -> value(usage.input())).sum();
        long cacheRead = usageByStep.values().stream().mapToLong(usage -> value(usage.cacheRead())).sum();
        long cacheWrite = usageByStep.values().stream().mapToLong(usage -> value(usage.cacheWrite())).sum();
        long output = usageByStep.values().stream().mapToLong(usage -> value(usage.output())).sum();
        long total = usageByStep.values().stream()
                .mapToLong(usage -> usage.inputTotal() + usage.outputTotal()).sum();
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("present", total > 0);
        totals.put("coding", true);
        totals.put("total", compactTokenCount(total));
        if (inputPresent) totals.put("input", compactTokenCount(input));
        if (cacheReadPresent) totals.put("cacheRead", compactTokenCount(cacheRead));
        if (cacheWritePresent) totals.put("cacheWrite", compactTokenCount(cacheWrite));
        if (outputPresent) totals.put("output", compactTokenCount(output));
        return totals;
    }

    private static long value(Long value) { return value == null ? 0 : value; }

    private static String compactTokenCount(long value) {
        long divisor = value >= 1_000_000 ? 1_000_000 : value >= 1_000 ? 1_000 : 1;
        if (divisor == 1) return UiFormat.count(value);
        String suffix = divisor == 1_000_000 ? "m" : "k";
        return java.math.BigDecimal.valueOf(value)
                .divide(java.math.BigDecimal.valueOf(divisor), 1, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + suffix;
    }

    private Map<String, Object> executionRow(PipelineExecution execution) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", execution.getId());
        row.put("idShort", UiFormat.abbreviate(execution.getId().toString()));
        row.put("flowId", execution.getFlowId());
        row.put("status", "dev-factory".equals(execution.getFlowId())
                ? new DeveloperExecutionView(jsonMapper).productStatus(execution, artifactStore.allForExecution(execution.getId()))
                : execution.getStatus());
        row.put("currentStepIndex", execution.getCurrentStepIndex());
        row.put("createdAt", UiFormat.format(execution.getCreatedAt()));
        row.put("updatedAt", UiFormat.format(execution.getUpdatedAt()));
        row.put("parentExecutionId", execution.getParentExecutionId());
        row.put("parentShort", execution.getParentExecutionId() == null ? null
                : UiFormat.abbreviate(execution.getParentExecutionId().toString()));
        return row;
    }

    private List<Map<String, Object>> stepStates(PipelineExecution execution,
                                                  Map<String, ReportedUsage> usageByStep) {
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
            ReportedUsage usage = usageByStep.get(step.stepId());
            if (usage == null) row.put("tokens", null);
            else if (usage.codingTokensPresent()) {
                long input = usage.inputTotal();
                long output = usage.outputTotal();
                row.put("tokens", UiFormat.count(input + output) + " tokens ("
                        + UiFormat.count(input) + " in / " + UiFormat.count(output) + " out)");
            } else if (usage.factoryTokensPresent())
                row.put("tokens", UiFormat.count(value(usage.prompt()) + value(usage.completion())) + " tokens ("
                        + UiFormat.count(value(usage.prompt())) + " in / "
                        + UiFormat.count(value(usage.completion())) + " out)");
            else row.put("tokens", null);
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
            version.put("id", artifact.getId());
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

    private record ReportedUsage(Long prompt, Long completion, Long input, Long cacheRead,
                                 Long cacheWrite, Long output,
                                 String provider, String model) {
        boolean factoryTokensPresent() { return prompt != null || completion != null; }
        boolean codingTokensPresent() {
            return input != null || cacheRead != null || cacheWrite != null || output != null;
        }
        long inputTotal() {
            return prompt != null ? prompt : value(input) + value(cacheRead) + value(cacheWrite);
        }
        long outputTotal() { return completion != null ? completion : value(output); }
    }

    private record RuntimeIdentity(String provider, String model) { }

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
