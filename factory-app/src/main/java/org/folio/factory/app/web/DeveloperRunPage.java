package org.folio.factory.app.web;

import org.folio.factory.app.tasks.execution.TaskExecutionProperties;
import org.folio.factory.app.tasks.presentation.RunContext;
import org.folio.factory.app.tasks.presentation.TaskQuery;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.devfactory.feedback.FeedbackArtifacts;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The run page of a Developer Flow attempt and the revision it polls. It has its own template and its own data,
 * so the execution page of every other flow keeps its engine view.
 */
@Component
class DeveloperRunPage {

    static final String VIEW = "developer-run";

    private final JsonMapper jsonMapper;
    private final DeveloperCostEstimator costEstimator;
    private final FeedbackUi feedbackUi;
    private final TaskQuery taskQuery;
    private final TaskExecutionProperties taskLimits;
    private final DeveloperExecutionView developerView;

    DeveloperRunPage(JsonMapper jsonMapper, DeveloperCostEstimator costEstimator, FeedbackUi feedbackUi,
                     TaskQuery taskQuery, TaskExecutionProperties taskLimits) {
        this.jsonMapper = jsonMapper;
        this.costEstimator = costEstimator;
        this.feedbackUi = feedbackUi;
        this.taskQuery = taskQuery;
        this.taskLimits = taskLimits;
        this.developerView = new DeveloperExecutionView(jsonMapper);
    }

    /** {@code review} is the pending review of a run that waits for one, or null. */
    String render(ExecutionDetail detail, HitlReview review, Model model) {
        var now = Instant.now();
        var execution = detail.execution();
        var facts = developerView.build(execution, detail.artifacts(), detail.events(), now);
        var context = taskQuery.runContext(detail.id());
        var projection = new AttemptUsageSummary.Projection(detail.events());
        var feedback = feedbackUi.populate(execution, detail.artifacts(), detail.events(), projection, model);
        var usage = usage(detail, projection, feedback);
        var input = new RunStatusCard.Input(facts, context, execution, feedback, usage, clock(detail, context, now),
                review);
        model.addAttribute("run", new DeveloperRunView(RunStatusCard.header(facts, context, execution),
                RunStatusCard.status(input), RunStatusCard.standing(context), facts.steps().cards(),
                facts.activity(), usage.column(), facts.technical(), links(detail, usage)));
        model.addAttribute("pollUrl", polls(execution, context) ? "/executions/" + detail.id() + "/poll" : null);
        model.addAttribute("pollBaseline", revision(execution, detail.events().size(), detail.artifacts().size(),
                context));
        return VIEW;
    }

    /** What the run page compares to reload itself; see {@link #revision(PipelineExecution, int, int, Optional)}. */
    String revision(PipelineExecution execution, int auditCount, int artifactCount) {
        return revision(execution, auditCount, artifactCount, taskQuery.runContext(execution.getId()));
    }

    /**
     * Changes when the run moves or records anything, and when its attempt changes state: the task dispatcher
     * records the end of an attempt after the run ended. Only discrete state goes in, never a time, so a page
     * that shows a ticking time does not reload for it. Free of commas, as poll.js needs.
     */
    private static String revision(PipelineExecution execution, int auditCount, int artifactCount,
                                   Optional<RunContext> context) {
        var shown = execution.getStatus() + "|" + execution.getCurrentStepIndex() + "|" + auditCount + "|"
                + artifactCount + "|" + context.map(RunContext::toString).orElse("");
        return Integer.toHexString(shown.hashCode());
    }

    /** A run polls while it works or waits, and while its attempt is still in flight after the run ended. */
    private static boolean polls(PipelineExecution execution, Optional<RunContext> context) {
        return !execution.getStatus().isTerminal() || context.map(RunContext::inFlight).orElse(false);
    }

    private RunUsage usage(ExecutionDetail detail, AttemptUsageSummary.Projection projection,
                           Optional<FeedbackUi.Summary> feedback) {
        var execution = detail.execution();
        if (!FeedbackArtifacts.VERSION.equals(execution.getFlowVersion())) {
            return RunUsage.steps(detail.events(), jsonMapper, costEstimator);
        }
        return RunUsage.attempts(projection, execution.getStatus() == ExecutionStatus.RUNNING,
                feedback.map(FeedbackUi.Summary::rounds).orElse(false));
    }

    /** Active time, and the attempt deadline as its limit for a run of a task. */
    private RunStatusCard.Clock clock(ExecutionDetail detail, Optional<RunContext> context, Instant now) {
        var execution = detail.execution();
        var end = execution.getStatus().isFinal() && execution.getCompletedAt() != null
                ? execution.getCompletedAt() : now;
        return new RunStatusCard.Clock(AttemptUsageSummary.activeSeconds(detail.events(), end), now,
                context.isPresent() ? (int) taskLimits.deadline().toMinutes() : null);
    }

    private static DeveloperRunView.Links links(ExecutionDetail detail, RunUsage usage) {
        var id = detail.id();
        return new DeveloperRunView.Links(usage.pricing(), "/executions/" + id + "/transcript",
                "/audit?executionId=" + id, detail.artifacts().size(), artifactGroups(detail.artifacts()));
    }

    /** One group per artifact name, in the order the names first appear, with a link per version. */
    private static List<DeveloperRunView.ArtifactGroup> artifactGroups(List<Artifact> artifacts) {
        Map<String, List<DeveloperRunView.ArtifactLink>> byName = new LinkedHashMap<>();
        for (var artifact : artifacts) {
            byName.computeIfAbsent(artifact.getName(), name -> new ArrayList<>()).add(new DeveloperRunView.ArtifactLink(
                    "v" + artifact.getVersion(), "/artifacts/" + artifact.getId()));
        }
        return byName.entrySet().stream()
                .map(entry -> new DeveloperRunView.ArtifactGroup(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }
}
