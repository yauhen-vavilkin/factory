package org.folio.factory.app.tasks.presentation;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskRoutingReason;
import org.folio.factory.app.web.UiFormat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.folio.factory.app.tasks.presentation.TaskLabels.ago;
import static org.folio.factory.app.tasks.presentation.TaskLabels.costLabel;
import static org.folio.factory.app.tasks.presentation.TaskLabels.costNoteTitle;
import static org.folio.factory.app.tasks.presentation.TaskLabels.failureText;
import static org.folio.factory.app.tasks.presentation.TaskLabels.incompleteCostNote;
import static org.folio.factory.app.tasks.presentation.TaskLabels.minutes;
import static org.folio.factory.app.tasks.presentation.TaskLabels.queued;
import static org.folio.factory.app.tasks.presentation.TaskLabels.shortPullRequestLabel;
import static org.folio.factory.app.tasks.presentation.TaskLabels.stateClass;
import static org.folio.factory.app.tasks.presentation.TaskLabels.stateLabel;
import static org.folio.factory.app.tasks.presentation.TaskLabels.triggeredDetail;
import static org.folio.factory.app.tasks.presentation.TaskLabels.working;

/** The Runs rows, the in-progress strip and the "cannot run here" lines of My tasks. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class RunRows {

    public static TriggeredRow row(TaskView view) {
        var latest = view.attempts().getLast();
        var label = stateLabel(view);
        var pullRequest = delivered(view);
        var when = latest.startedAt() != null ? latest.startedAt() : latest.createdAt();
        var action = action(view, latest);
        var costTitle = working(view) ? null : costNoteTitle(incompleteCostNote(view.usage()));
        return new TriggeredRow(view.taskId(), view.displayKey(), view.jiraUrl(), view.title(),
                pullRequest ? shortPullRequestLabel(view.pullRequestUrl(), view.draftReasons() != null) : label,
                pullRequest ? pullRequestClass(view) : stateClass(label), pullRequest ? view.pullRequestUrl() : null,
                detail(view), cost(view, latest), costTitle, ago(when),
                UiFormat.format(when), action,
                actionUrl(view, latest, action), action == RunAction.MARK_STOPPED ? latest.attemptId() : null);
    }

    /** The task's total, or the cost so far of the attempt that works now (none before it reports usage). */
    private static String cost(TaskView view, TaskView.AttemptHistoryEntry latest) {
        if (!working(view)) {
            return costLabel(view.usage());
        }
        var soFar = costLabel(latest.usage());
        return soFar == null ? null : soFar + " so far";
    }

    /** The running task, when one runs: the strip above Ready to run is a shortcut to its run. */
    public static Optional<InProgressStrip> strip(List<TaskView> triggered) {
        return triggered.stream()
                .filter(view -> working(view) && view.attempts().getLast().executionId() != null)
                .findFirst()
                .map(RunRows::toStrip);
    }

    /** Tasks that cannot run, one line per reason. */
    public static List<CannotRunGroup> cannotRun(List<AvailableRow> available) {
        var keysByReason = new LinkedHashMap<String, List<String>>();
        available.stream().filter(row -> !row.selectable())
                .forEach(row -> keysByReason.computeIfAbsent(cannotRunReason(row), reason -> new ArrayList<>())
                        .add(row.key()));
        return keysByReason.entrySet().stream()
                .map(entry -> new CannotRunGroup(List.copyOf(entry.getValue()), entry.getKey()))
                .toList();
    }

    private static InProgressStrip toStrip(TaskView view) {
        var latest = view.attempts().getLast();
        var started = latest.startedAt() != null ? latest.startedAt() : latest.createdAt();
        var elapsed = minutes(Duration.between(started, Instant.now()).getSeconds());
        var run = Stream.of(TaskRunSettings.label(latest.runSettings()), elapsed)
                .filter(part -> part != null).collect(Collectors.joining(" · "));
        return new InProgressStrip(view.displayKey(), view.jiraUrl(), view.title(), run, elapsed,
                "/executions/" + latest.executionId());
    }

    private static RunAction action(TaskView view, TaskView.AttemptHistoryEntry latest) {
        if (delivered(view) && latest.executionId() != null) {
            return RunAction.PR_FEEDBACK;
        }
        if (latest.state() == TaskAttempt.State.NEEDS_INSPECTION) {
            return RunAction.MARK_STOPPED;
        }
        if (view.needsAnswer() && view.allowedActions().retry()) {
            return RunAction.ANSWER;
        }
        if (view.allowedActions().retry()) {
            return RunAction.RETRY;
        }
        return working(view) && latest.executionId() != null ? RunAction.OPEN_RUN : RunAction.NONE;
    }

    private static String actionUrl(TaskView view, TaskView.AttemptHistoryEntry latest, RunAction action) {
        return switch (action) {
            case PR_FEEDBACK -> "/executions/" + latest.executionId() + "#pr-feedback";
            case OPEN_RUN -> "/executions/" + latest.executionId();
            case ANSWER -> "/tasks/" + view.taskId() + "#answer";
            case RETRY -> "/tasks/" + view.taskId() + "#retry";
            case MARK_STOPPED, NONE -> null;
        };
    }

    private static boolean delivered(TaskView view) {
        return view.state() == TaskView.TaskState.PR_READY && view.pullRequestUrl() != null;
    }

    private static String pullRequestClass(TaskView view) {
        return view.draftReasons() == null ? "chip chip-success" : "chip chip-neutral";
    }

    /** A running task needs no detail: its chip says it; a queued one says its place in line. */
    private static String detail(TaskView view) {
        return working(view) && !queued(view) ? null : triggeredDetail(view);
    }

    private static String cannotRunReason(AvailableRow row) {
        if (row.problem() == TaskRoutingReason.Problem.NO_REPOSITORY) {
            return "no repository is set for the project";
        }
        if (row.problem() == TaskRoutingReason.Problem.SEVERAL_REPOSITORIES) {
            return "the project maps to several repositories";
        }
        return row.problemReason() == null ? "not eligible" : failureText(row.problemReason());
    }
}
