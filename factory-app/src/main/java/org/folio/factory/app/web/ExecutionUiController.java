package org.folio.factory.app.web;

import org.folio.factory.app.developer.CodingTranscriptStore;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.presentation.PilotTaskQuery;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

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
    private final PilotAttemptRepository pilotAttempts;
    private final PilotTaskQuery pilotTasks;
    private final CodingTranscriptStore transcripts;

    public ExecutionUiController(
            PipelineExecutionRepository executions,
            ArtifactStore artifactStore,
            AuditLog auditLog,
            FlowRegistry flowRegistry,
            HitlReviewRepository reviews,
            JsonMapper jsonMapper,
            DeveloperCostEstimator costEstimator,
            FeedbackUi feedbackUi,
            PilotAttemptRepository pilotAttempts,
            PilotTaskQuery pilotTasks,
            CodingTranscriptStore transcripts) {
        this.executions = executions;
        this.artifactStore = artifactStore;
        this.auditLog = auditLog;
        this.flowRegistry = flowRegistry;
        this.reviews = reviews;
        this.jsonMapper = jsonMapper;
        this.costEstimator = costEstimator;
        this.feedbackUi = feedbackUi;
        this.developerView = new DeveloperExecutionView(jsonMapper);
        this.pilotAttempts = pilotAttempts;
        this.pilotTasks = pilotTasks;
        this.transcripts = transcripts;
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

    @GetMapping("/executions/{id}")
    public String execution(@PathVariable("id") UUID id, Model model) {
        var execution = findExecution(id);
        var terminal = execution.getStatus().isTerminal();
        var events = auditLog.forExecution(id);
        var developerFlow = "dev-factory".equals(execution.getFlowId());
        var usage = StepUsageSummary.from(events, developerFlow, jsonMapper);
        var detail = new ExecutionDetail(id, execution, artifactStore.allForExecution(id), events);
        model.addAttribute("displayStatus",
                developerFlow ? developerView.productStatus(execution, detail.artifacts()) : execution.getStatus());
        var steps = stepStates(execution, usage);
        if (developerFlow) {
            populateDeveloperProgress(model, detail, steps);
        } else {
            model.addAttribute("progressSteps", steps);
        }
        populateUsage(model, detail, usage, developerFlow);
        model.addAttribute("auditCount", events.size());
        model.addAttribute("artifactCount", detail.artifacts().size());
        populateDetail(model, detail, steps);
        model.addAttribute("pollUrl", terminal ? null : "/api/executions/" + id);
        return "execution";
    }

    @GetMapping("/executions/{id}/transcript")
    public String transcript(@PathVariable("id") UUID id, Model model) {
        var execution = findExecution(id);
        model.addAttribute("execution", execution);
        model.addAttribute("pilotRun", pilotRun(id));
        model.addAttribute("runs", DeveloperTranscriptView.runs(transcripts.read(id)));
        return "transcript";
    }

    private PipelineExecution findExecution(UUID id) {
        return executions.findById(id).orElseThrow(() -> new NoSuchElementException("No execution " + id));
    }

    private Map<String, Object> pilotRun(UUID id) {
        return pilotAttempts.findByExecutionId(id)
                .map(attempt -> PilotTaskUiController.runContext(pilotTasks.detail(attempt.getTaskId()), id))
                .orElse(null);
    }

    private void populateDeveloperProgress(Model model, ExecutionDetail detail, List<Map<String, Object>> steps) {
        var execution = detail.execution();
        var now = Instant.now();
        populateFactoryTime(model, detail, now);
        var developer = developerView.build(execution, detail.artifacts(), detail.events(), now);
        model.addAttribute("developer", developer);
        model.addAttribute("pilotRun", pilotRun(detail.id()));
        model.addAttribute("progressSteps", developer.get("phases"));
        flowRegistry.find(execution.getFlowId())
                .ifPresent(flow -> labelTechnicalSteps(steps, flow, detail, now));
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

    private static void labelTechnicalSteps(List<Map<String, Object>> steps, FlowDescriptor flow,
                                            ExecutionDetail detail, Instant now) {
        var execution = detail.execution();
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            var stepId = flow.agentChain().get(i).stepId();
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

    private void populateUsage(Model model, ExecutionDetail detail, StepUsageSummary usage, boolean developerFlow) {
        var execution = detail.execution();
        if (developerFlow && FeedbackArtifacts.VERSION.equals(execution.getFlowVersion())) {
            feedbackUi.populate(execution, detail.artifacts(), detail.events(), model);
        } else {
            populateLegacyUsage(model, usage, developerFlow);
        }
    }

    private void populateDetail(Model model, ExecutionDetail detail, List<Map<String, Object>> steps) {
        var execution = detail.execution();
        model.addAttribute("execution", execution);
        model.addAttribute("steps", steps);
        model.addAttribute("artifactGroups", artifactGroups(detail.artifacts()));
        model.addAttribute("children", childRows(execution, detail.id()));
        model.addAttribute("pendingReview", pendingReview(execution, detail.id()));
        model.addAttribute("events",
                detail.events().stream().map(event -> UiFormat.auditRow(event, jsonMapper)).toList());
    }

    /** Compatibility for Test Factory and Developer Flow artifacts without attempt identities. */
    private void populateLegacyUsage(Model model, StepUsageSummary usage, boolean developerFlow) {
        model.addAttribute("estimatedCost", developerFlow ? usage.estimate(costEstimator) : null);
        model.addAttribute("usageIncomplete", usage.incomplete());
        model.addAttribute("tokenTotals", usage.tokenTotals(developerFlow));
    }

    private ExecutionRow executionRow(PipelineExecution execution) {
        var status = "dev-factory".equals(execution.getFlowId())
                ? developerView.productStatus(execution, artifactStore.allForExecution(execution.getId()))
                : execution.getStatus().name();
        var parentId = execution.getParentExecutionId();
        return new ExecutionRow(execution.getId(), UiFormat.abbreviate(execution.getId().toString()),
                execution.getFlowId(), status, execution.getCurrentStepIndex(),
                UiFormat.format(execution.getCreatedAt()), UiFormat.format(execution.getUpdatedAt()),
                parentId, parentId == null ? null : UiFormat.abbreviate(parentId.toString()));
    }

    private List<Map<String, Object>> stepStates(
            PipelineExecution execution, StepUsageSummary usage) {
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
            row.put("tokens", usage.stepTokens(step.stepId()));
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

    /** Panel ids count across all groups in artifact order, so every version has its own DOM id. */
    private List<Map<String, Object>> artifactGroups(List<Artifact> artifacts) {
        Map<String, List<Map<String, Object>>> byName = new LinkedHashMap<>();
        var panel = 0;
        for (var artifact : artifacts) {
            var version = artifactVersion(artifact, "artifact-" + panel++);
            byName.computeIfAbsent(artifact.getName(), n -> new ArrayList<>()).add(version);
        }
        List<Map<String, Object>> groups = new ArrayList<>(byName.size());
        byName.forEach((name, versions) -> {
            versions.getLast().put("latest", true);
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("name", name);
            group.put("versions", versions);
            groups.add(group);
        });
        return groups;
    }

    private static Map<String, Object> artifactVersion(Artifact artifact, String panelId) {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("id", artifact.getId());
        version.put("version", artifact.getVersion());
        version.put("content", artifact.getContent());
        version.put("createdBy", artifact.getCreatedBy());
        version.put("label", "v" + artifact.getVersion() + " · " + artifact.getCreatedBy()
                + " · " + UiFormat.format(artifact.getCreatedAt()));
        version.put("panelId", panelId);
        version.put("latest", false);
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

    private record ExecutionDetail(UUID id, PipelineExecution execution, List<Artifact> artifacts,
                                   List<AuditEvent> events) {
    }
}
