package org.folio.factory.app.tasks.presentation;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskRoutingReason;
import org.folio.factory.app.tasks.source.TaskJiraInstance;
import org.folio.factory.app.tasks.source.TaskSourceProperties;
import org.folio.factory.app.web.UiFormat;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.folio.factory.app.tasks.presentation.TaskLabels.ago;
import static org.folio.factory.app.tasks.presentation.TaskLabels.attemptLabel;
import static org.folio.factory.app.tasks.presentation.TaskLabels.attemptResult;
import static org.folio.factory.app.tasks.presentation.TaskLabels.costLabel;
import static org.folio.factory.app.tasks.presentation.TaskLabels.costNoteTitle;
import static org.folio.factory.app.tasks.presentation.TaskLabels.incompleteCostNote;
import static org.folio.factory.app.tasks.presentation.TaskLabels.minutes;
import static org.folio.factory.app.tasks.presentation.TaskLabels.pullRequestLabel;
import static org.folio.factory.app.tasks.presentation.TaskLabels.resultSentence;
import static org.folio.factory.app.tasks.presentation.TaskLabels.stateClass;
import static org.folio.factory.app.tasks.presentation.TaskLabels.stateLabel;
import static org.folio.factory.app.tasks.presentation.TaskLabels.usageNote;

/**
 * Page rows of My tasks, the task summary and the run page header, built from the
 * task projection.
 */
@Component
@RequiredArgsConstructor
public class TaskRows {

    private static final DateTimeFormatter STARTED =
            DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US).withZone(ZoneOffset.UTC);
    private static final int QUOTE = 80;

    private final TaskSourceProperties source;
    private final TaskJiraInstance jira;
    private final DevFactoryProperties repositories;

    public SourceCard sourceCard(TaskInstallation installation) {
        String base = jira.baseUrl();
        boolean ready = source.assignee() != null && base != null;
        return new SourceCard(source.assignee(), base == null ? null : jira.current(),
                ready ? source.effectiveQuery() : null, ready ? source.searchUrl(base) : null,
                ago(installation.getLastSuccessfulSyncAt()), UiFormat.format(installation.getLastSuccessfulSyncAt()),
                installation.getSyncProblem());
    }

    /** "PROJECT → repository" for each catalog entry, the routing a scan applies. */
    public String repositoryMap() {
        List<String> parts = new ArrayList<>();
        repositories.repositories().forEach((key, repository) -> repository.jiraProjects()
                .forEach(project -> parts.add(project + " → " + repositoryName(key))));
        return parts.size() + (parts.size() == 1 ? " Jira project" : " Jira projects") + " configured: "
                + String.join(" · ", parts);
    }

    public AvailableRow availableRow(TaskView view) {
        var reason = view.state() == TaskView.TaskState.UNSUPPORTED ? view.reason() : null;
        var problem = TaskRoutingReason.problem(reason).orElse(null);
        return new AvailableRow(view.taskId(), view.displayKey(), view.jiraUrl(), view.title(),
                view.jiraStatus() == null ? "Unknown" : view.jiraStatus(), view.projectKey(), repository(view),
                view.addedByKey(), view.allowedActions().select(), view.version(), view.inputFingerprint(),
                view.destinationFingerprint(), problem, reason);
    }

    public TaskDetail detail(TaskView view) {
        var label = view.attempts().isEmpty() ? null : stateLabel(view);
        var latest = view.attempts().isEmpty() ? null : view.attempts().getLast();
        var stateLabel = label != null ? label
                : view.state() == TaskView.TaskState.UNSUPPORTED ? "Not eligible" : "Not started";
        return new TaskDetail(view.taskId(), view.displayKey(), view.jiraUrl(), view.title(), repository(view),
                view.version(), view.inputFingerprint(), view.destinationFingerprint(), stateLabel,
                stateClass(label), headline(view), view.draftReasons(), costLabel(view.usage()), spentNote(view),
                view.allowedActions().retry(), view.attempts().size() + 1, runUrl(view, latest), runLabel(view),
                view.pullRequestUrl(), view.pullRequestUrl() == null ? null
                        : "Open p" + pullRequestLabel(view.pullRequestUrl()).substring(1),
                latest == null ? null : markStoppedAttemptId(latest), view.projectKey(),
                destination(view.destinationRepository()), usage(view.usage()));
    }

    public static AttemptRow attemptRow(TaskView.AttemptHistoryEntry attempt) {
        var label = attemptLabel(attempt);
        var costNote = attemptCostNote(attempt);
        return new AttemptRow(attempt.ordinal(), attemptKind(attempt), attemptMeta(attempt),
                UiFormat.format(attempt.startedAt()), costLabel(attempt.usage()), costNote, costNoteTitle(costNote),
                costText(costLabel(attempt.usage()), costNote), label, stateClass(label), attemptResult(attempt),
                attempt.executionId());
    }

    /** The run page's link back to its task: key, Jira link and the state of the attempt behind this run. */
    public static Map<String, Object> runContext(TaskView view, UUID executionId) {
        String label = view.attempts().stream()
                .filter(attempt -> executionId.equals(attempt.executionId()))
                .findFirst()
                .map(TaskLabels::attemptLabel)
                .orElseGet(() -> stateLabel(view));
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("taskId", view.taskId());
        run.put("key", view.displayKey());
        run.put("title", view.title());
        run.put("jiraUrl", view.jiraUrl());
        run.put("stateLabel", label);
        run.put("stateClass", stateClass(label));
        return run;
    }

    /** The notice that the queue waits on an attempt that needs inspection. */
    public static Map<String, Object> queueWaiting(String key, TaskAttempt attempt) {
        Map<String, Object> waiting = new LinkedHashMap<>();
        waiting.put("key", key);
        waiting.put("taskId", attempt.getTaskId());
        waiting.put("attemptId", attempt.getId());
        return waiting;
    }

    /** Same notes as Triggered; a queued attempt already says "not started" in Started. */
    private static String attemptCostNote(TaskView.AttemptHistoryEntry attempt) {
        return switch (attempt.state()) {
            case RESERVED -> null;
            case QUEUED, RUNNING -> "so far";
            default -> incompleteCostNote(attempt.usage());
        };
    }

    /** An attempt that needs inspection offers Mark as stopped. */
    private static UUID markStoppedAttemptId(TaskView.AttemptHistoryEntry attempt) {
        return attempt.state() == TaskAttempt.State.NEEDS_INSPECTION ? attempt.attemptId() : null;
    }

    /** "$0.02", "$0.02 so far", or the note when there is no cost figure; a new attempt shows "—". */
    private static String costText(String cost, String costNote) {
        if (cost == null) {
            return costNote == null || "so far".equals(costNote) ? "—" : costNote;
        }
        return "so far".equals(costNote) ? cost + " so far" : cost;
    }

    private static String headline(TaskView view) {
        return view.needsAnswer() ? "The agent stopped with a question" : resultSentence(view);
    }

    /** "spent in 2 attempts"; null before the first attempt. */
    private static String spentNote(TaskView view) {
        int count = view.attempts().size();
        if (count == 0) {
            return null;
        }
        return "spent in " + count + (count == 1 ? " attempt" : " attempts")
                + (view.usage().complete() ? "" : ", cost may be higher");
    }

    private static String runUrl(TaskView view, TaskView.AttemptHistoryEntry latest) {
        if (latest == null || latest.executionId() == null) {
            return null;
        }
        var run = "/executions/" + latest.executionId();
        return view.pullRequestUrl() != null ? run + "#pr-feedback" : run;
    }

    private static String runLabel(TaskView view) {
        if (view.pullRequestUrl() != null) {
            return "PR feedback";
        }
        return TaskLabels.working(view) ? "Open live run" : "Open last run";
    }

    /** "first run", "retry" or "retry with instructions", and "by key" for a task added by its key. */
    private static String attemptKind(TaskView.AttemptHistoryEntry attempt) {
        var instructed = TaskRunSettings.instructions(attempt.runSettings()) != null;
        var kind = attempt.kind() != TaskAttempt.Kind.RETRY ? "first run"
                : instructed ? "retry with instructions" : "retry";
        return attempt.admittedVia() == TaskAttempt.AdmittedVia.BY_KEY ? kind + " by key" : kind;
    }

    /** "glm-5.3 · max · Nov 10, 17:48 · 13 min · "first line of the instructions"". */
    private static String attemptMeta(TaskView.AttemptHistoryEntry attempt) {
        var instructions = TaskRunSettings.instructions(attempt.runSettings());
        return Stream.of(TaskRunSettings.label(attempt.runSettings()),
                        attempt.startedAt() == null ? "not started" : STARTED.format(attempt.startedAt()),
                        attempt.paidStart() ? minutes(attempt.activeSeconds()) : null,
                        instructions == null ? null : "\"" + firstLine(instructions) + "\"")
                .filter(Objects::nonNull).collect(Collectors.joining(" · "));
    }

    private static String firstLine(String text) {
        var line = text.strip().lines().findFirst().orElse("");
        return line.length() <= QUOTE ? line : line.substring(0, QUOTE - 1) + "…";
    }

    private static TaskDetail.Usage usage(TaskUsage usage) {
        return new TaskDetail.Usage(usage.tokens() > 0 || !usage.estimatesByCurrency().isEmpty(),
                UiFormat.count(usage.tokens()),
                usage.tokensByCategory().entrySet().stream()
                        .map(entry -> new TaskDetail.Figure(entry.getKey().label(), UiFormat.count(entry.getValue())))
                        .toList(),
                costLabel(usage), usageNote(usage));
    }

    private String repository(TaskView view) {
        return view.destinationRepository() == null ? null : repositoryName(view.destinationRepository());
    }

    private String repositoryName(String repositoryKey) {
        DevFactoryProperties.Repository repository = repositories.repositories().get(repositoryKey);
        if (repository == null) {
            return repositoryKey;
        }
        String sourceRepo = repository.sourceRepo();
        return sourceRepo.substring(sourceRepo.indexOf('/') + 1);
    }

    private String destination(String repositoryKey) {
        var repository = repositoryKey == null ? null : repositories.repositories().get(repositoryKey);
        var target = repository == null ? null : repository.delivery();
        return target == null || target.repository() == null ? null
                : target.repository() + " · base " + target.baseBranch();
    }
}
