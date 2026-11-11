package org.folio.factory.app.web;

import org.folio.factory.app.tasks.presentation.TaskQuery;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.devfactory.feedback.FeedbackArtifacts;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The run page of a Developer Flow attempt. It has its own template and its own data, so the execution page of
 * every other flow keeps its engine view.
 */
@Component
class DeveloperRunPage {

    static final String VIEW = "developer-run";

    private final JsonMapper jsonMapper;
    private final DeveloperCostEstimator costEstimator;
    private final FeedbackUi feedbackUi;
    private final TaskQuery taskQuery;
    private final DeveloperExecutionView developerView;

    DeveloperRunPage(JsonMapper jsonMapper, DeveloperCostEstimator costEstimator, FeedbackUi feedbackUi,
                     TaskQuery taskQuery) {
        this.jsonMapper = jsonMapper;
        this.costEstimator = costEstimator;
        this.feedbackUi = feedbackUi;
        this.taskQuery = taskQuery;
        this.developerView = new DeveloperExecutionView(jsonMapper);
    }

    String render(ExecutionDetail detail, List<Map<String, Object>> steps, Model model) {
        var now = Instant.now();
        populateFactoryTime(model, detail, now);
        var developer = developerView.build(detail.execution(), detail.artifacts(), detail.events(), now);
        model.addAttribute("developer", developer);
        model.addAttribute("displayStatus", developer.get("productStatus"));
        model.addAttribute("taskRun", taskQuery.runContext(detail.id()).orElse(null));
        model.addAttribute("artifactGroups", artifactLinks(detail.artifacts()));
        model.addAttribute("tokenCategories", AttemptCharge.Category.LABELS);
        labelTechnicalSteps(steps, detail, now);
        populateUsage(model, detail);
        return VIEW;
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

    private void populateUsage(Model model, ExecutionDetail detail) {
        var execution = detail.execution();
        if (FeedbackArtifacts.VERSION.equals(execution.getFlowVersion())) {
            feedbackUi.populate(execution, detail.artifacts(), detail.events(), model);
            return;
        }
        // Runs of older flow versions have no attempt identities, so their usage is summed per step.
        var usage = StepUsageSummary.from(detail.events(), true, jsonMapper);
        model.addAttribute("estimatedCost", usage.estimate(costEstimator));
        model.addAttribute("usageIncomplete", usage.incomplete());
        model.addAttribute("tokenTotals", usage.tokenTotals(true));
    }

    /** One group per artifact name, in the order the names first appear, with a link per version. */
    private static List<Map<String, Object>> artifactLinks(List<Artifact> artifacts) {
        Map<String, List<Map<String, Object>>> byName = new LinkedHashMap<>();
        for (var artifact : artifacts) {
            byName.computeIfAbsent(artifact.getName(), name -> new ArrayList<>())
                    .add(Map.of("id", artifact.getId(), "version", artifact.getVersion()));
        }
        List<Map<String, Object>> groups = new ArrayList<>(byName.size());
        byName.forEach((name, versions) -> groups.add(Map.of("name", name, "versions", versions)));
        return groups;
    }
}
