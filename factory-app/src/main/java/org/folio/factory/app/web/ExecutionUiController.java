package org.folio.factory.app.web;

import org.folio.factory.app.EngineRecords;
import org.folio.factory.app.tasks.presentation.TaskQuery;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEvent;
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
import org.folio.factory.devfactory.feedback.FeedbackArtifacts;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.folio.factory.devfactory.DevFactoryConfiguration;

/**
 * Server-rendered execution views: a filterable, paged run list and a per-execution detail page
 * (step progress, artifact versions, child runs, audit timeline). All view state is computed here
 * so the templates stay dumb.
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
    private final FeedbackUi feedbackUi;
    private final DeveloperExecutionView developerView;
    private final TaskQuery taskQuery;

    public ExecutionUiController(
            EngineRecords engine,
            AuditLog auditLog,
            HitlReviewRepository reviews,
            JsonMapper jsonMapper,
            DeveloperCostEstimator costEstimator,
            FeedbackUi feedbackUi,
            TaskQuery taskQuery) {
        this.executions = engine.executions();
        this.artifactStore = engine.artifacts();
        this.auditLog = auditLog;
        this.flowRegistry = engine.flows();
        this.reviews = reviews;
        this.jsonMapper = jsonMapper;
        this.costEstimator = costEstimator;
        this.feedbackUi = feedbackUi;
        this.developerView = new DeveloperExecutionView(jsonMapper);
        this.taskQuery = taskQuery;
    }

    @GetMapping("/executions")
    public String executions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "flow", required = false) String flow,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Model model) {
        var clampedSize = PageValidation.clampSize(size, DEFAULT_SIZE);
        var pageable = PageRequest.of(Math.max(page, 0), clampedSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        var parsedStatus = UiFormat.enumOrNull(ExecutionStatus.class, status);
        var selectedStatus = parsedStatus != null ? parsedStatus.name() : "";
        var selectedFlow = flow != null && !flow.isBlank() ? flow : "";
        var result = executions.search(parsedStatus, flow, pageable);
        model.addAttribute("executions", result.getContent().stream().map(this::executionRow).toList());
        model.addAttribute("page", result);
        model.addAttribute("statuses", ExecutionStatus.values());
        model.addAttribute("flows", flowRegistry.all().stream().map(FlowDescriptor::id).toList());
        model.addAttribute("selectedStatus", selectedStatus);
        model.addAttribute("selectedFlow", selectedFlow);
        model.addAttribute("filtersActive", parsedStatus != null || !selectedFlow.isEmpty());
        model.addAttribute("baseUrl", "/executions?status=" + UiFormat.encode(selectedStatus)
                + "&flow=" + UiFormat.encode(selectedFlow) + "&size=" + clampedSize);
        return "executions";
    }

    /** The template renders a Developer Flow run and any other run on two branches; each gets only its data. */
    @GetMapping("/executions/{id}")
    public String execution(@PathVariable("id") UUID id, Model model) {
        var execution = findExecution(id);
        var events = auditLog.forExecution(id);
        var chain = flowRegistry.find(execution.getFlowId()).map(FlowDescriptor::agentChain).orElse(List.of());
        var detail = new ExecutionDetail(id, execution, artifactStore.allForExecution(id), events, chain);
        var steps = stepStates(execution, chain);
        if (DevFactoryConfiguration.FLOW_ID.equals(execution.getFlowId())) {
            populateDeveloperRun(model, detail, steps);
        } else {
            populateEngineRun(model, detail, steps);
        }
        model.addAttribute("auditCount", events.size());
        model.addAttribute("artifactCount", detail.artifacts().size());
        populateDetail(model, detail, steps);
        model.addAttribute("pollUrl", execution.getStatus().isTerminal() ? null : "/api/executions/" + id);
        return "execution";
    }

    private PipelineExecution findExecution(UUID id) {
        return executions.findById(id).orElseThrow(() -> new NoSuchElementException("No execution " + id));
    }

    private void populateDeveloperRun(Model model, ExecutionDetail detail, List<Map<String, Object>> steps) {
        var execution = detail.execution();
        var now = Instant.now();
        populateFactoryTime(model, detail, now);
        var developer = developerView.build(execution, detail.artifacts(), detail.events(), now);
        model.addAttribute("developer", developer);
        model.addAttribute("displayStatus", developer.get("productStatus"));
        model.addAttribute("taskRun", taskQuery.runContext(detail.id()).orElse(null));
        model.addAttribute("artifactGroups", artifactLinks(detail.artifacts()));
        model.addAttribute("tokenCategories", AttemptCharge.Category.LABELS);
        labelTechnicalSteps(steps, detail, now);
        populateDeveloperUsage(model, detail);
    }

    private void populateEngineRun(Model model, ExecutionDetail detail, List<Map<String, Object>> steps) {
        var usage = StepUsageSummary.from(detail.events(), false, jsonMapper);
        addStepTokens(steps, detail.chain(), usage);
        model.addAttribute("displayStatus", detail.execution().getStatus());
        model.addAttribute("progressSteps", steps);
        populateLegacyUsage(model, usage, false);
        model.addAttribute("artifactGroups", artifactGroups(detail.artifacts()));
        model.addAttribute("events",
                detail.events().stream().map(event -> UiFormat.auditRow(event, jsonMapper)).toList());
    }

    private static void populateFactoryTime(Model model, ExecutionDetail detail, Instant now) {
        var execution = detail.execution();
        var end = execution.getStatus().isFinal() && execution.getCompletedAt() != null
                ? execution.getCompletedAt()
                : now;
        var workSeconds = AttemptUsageSummary.activeSeconds(detail.events(), end);
        model.addAttribute("factoryTime", UiFormat.duration(workSeconds));
        model.addAttribute("factoryTimeStart",
                execution.getStatus() == ExecutionStatus.RUNNING ? now.minusSeconds(workSeconds) : null);
    }

    private static void labelTechnicalSteps(List<Map<String, Object>> steps, ExecutionDetail detail, Instant now) {
        var execution = detail.execution();
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            var stepId = detail.chain().get(i).stepId();
            step.put("label", DeveloperExecutionView.technicalStepLabel(stepId));
            var durationSeconds = DeveloperExecutionView.technicalStepDurationSeconds(
                    stepId, detail.events(), execution, now);
            step.put("sublabel", durationSeconds == null ? "Not started" : UiFormat.duration(durationSeconds));
            if (i == execution.getCurrentStepIndex()
                    && execution.getStatus() == ExecutionStatus.RUNNING
                    && durationSeconds != null) {
                step.put("elapsedStart", now.minusSeconds(durationSeconds));
            }
        }
    }

    private void populateDeveloperUsage(Model model, ExecutionDetail detail) {
        var execution = detail.execution();
        if (FeedbackArtifacts.VERSION.equals(execution.getFlowVersion())) {
            feedbackUi.populate(execution, detail.artifacts(), detail.events(), model);
        } else {
            populateLegacyUsage(model, StepUsageSummary.from(detail.events(), true, jsonMapper), true);
        }
    }

    private void populateDetail(Model model, ExecutionDetail detail, List<Map<String, Object>> steps) {
        var execution = detail.execution();
        model.addAttribute("execution", execution);
        model.addAttribute("steps", steps);
        model.addAttribute("children", childRows(execution, detail.id()));
        model.addAttribute("pendingReview", pendingReview(execution, detail.id()));
    }

    /** Compatibility for Test Factory and Developer Flow artifacts without attempt identities. */
    private void populateLegacyUsage(Model model, StepUsageSummary usage, boolean developerFlow) {
        model.addAttribute("estimatedCost", developerFlow ? usage.estimate(costEstimator) : null);
        model.addAttribute("usageIncomplete", usage.incomplete());
        model.addAttribute("tokenTotals", usage.tokenTotals(developerFlow));
    }

    private ExecutionRow executionRow(PipelineExecution execution) {
        var status = DevFactoryConfiguration.FLOW_ID.equals(execution.getFlowId())
                ? developerView.productStatus(execution, name -> artifactStore.getLatest(execution.getId(), name))
                : execution.getStatus().name();
        var parentId = execution.getParentExecutionId();
        return new ExecutionRow(execution.getId(), UiFormat.abbreviate(execution.getId().toString()),
                execution.getFlowId(), status, execution.getCurrentStepIndex(),
                UiFormat.format(execution.getCreatedAt()), UiFormat.format(execution.getUpdatedAt()),
                parentId, parentId == null ? null : UiFormat.abbreviate(parentId.toString()));
    }

    private List<Map<String, Object>> stepStates(PipelineExecution execution, List<StepDescriptor> chain) {
        if (chain.isEmpty()) {
            return List.of();
        }
        JsonNode retryCounts = jsonMapper.readTree(execution.getRetryCounts());
        List<Map<String, Object>> steps = new ArrayList<>(chain.size());
        for (int i = 0; i < chain.size(); i++) {
            StepDescriptor step = chain.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", step.type().name());
            row.put("label", UiFormat.stepLabel(step));
            row.put("sublabel", step.stepId() + " · " + step.type());
            row.put("state", stepState(i, execution));
            row.put("attempts", retryCounts.path(step.stepId()).asInt(0));
            steps.add(row);
        }
        return steps;
    }

    private static void addStepTokens(List<Map<String, Object>> steps, List<StepDescriptor> chain,
                                      StepUsageSummary usage) {
        for (int i = 0; i < steps.size(); i++) {
            steps.get(i).put("tokens", usage.stepTokens(chain.get(i).stepId()));
        }
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

    /** Panel ids count across all groups in artifact order, so every version has its own DOM id. */
    private static List<Map<String, Object>> artifactGroups(List<Artifact> artifacts) {
        Map<String, Artifact> latest = new HashMap<>();
        artifacts.forEach(artifact -> latest.put(artifact.getName(), artifact));
        var panel = new AtomicInteger();
        return groupByName(artifacts, artifact -> artifactVersion(artifact, "artifact-" + panel.getAndIncrement(),
                latest.get(artifact.getName()) == artifact));
    }

    /** A Developer Flow run page links each artifact version and shows no content. */
    private static List<Map<String, Object>> artifactLinks(List<Artifact> artifacts) {
        return groupByName(artifacts, artifact -> Map.of("id", artifact.getId(), "version", artifact.getVersion()));
    }

    /** One group per artifact name, in the order the names first appear; {@code version} maps each version. */
    private static List<Map<String, Object>> groupByName(List<Artifact> artifacts,
                                                         Function<Artifact, Map<String, Object>> version) {
        Map<String, List<Map<String, Object>>> byName = new LinkedHashMap<>();
        for (var artifact : artifacts) {
            byName.computeIfAbsent(artifact.getName(), name -> new ArrayList<>()).add(version.apply(artifact));
        }
        List<Map<String, Object>> groups = new ArrayList<>(byName.size());
        byName.forEach((name, versions) -> groups.add(Map.of("name", name, "versions", versions)));
        return groups;
    }

    private static Map<String, Object> artifactVersion(Artifact artifact, String panelId, boolean latest) {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("id", artifact.getId());
        version.put("version", artifact.getVersion());
        version.put("content", artifact.getContent());
        version.put("label", "v" + artifact.getVersion() + " · " + artifact.getCreatedBy()
                + " · " + UiFormat.format(artifact.getCreatedAt()));
        version.put("panelId", panelId);
        version.put("latest", latest);
        return version;
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

    /** One row of the run list; {@code status} is the product status for Developer Flow runs. */
    public record ExecutionRow(UUID id, String idShort, String flowId, String status, int currentStepIndex,
                               String createdAt, String updatedAt, UUID parentExecutionId, String parentShort) {
    }

    /** {@code chain} is the flow's step list, empty when the flow is no longer registered. */
    private record ExecutionDetail(UUID id, PipelineExecution execution, List<Artifact> artifacts,
                                   List<AuditEvent> events, List<StepDescriptor> chain) {
    }
}
