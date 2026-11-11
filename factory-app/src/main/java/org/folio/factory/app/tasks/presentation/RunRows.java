package org.folio.factory.app.tasks.presentation;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskRoutingReason;
import org.folio.factory.app.web.PageTime;

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
import static org.folio.factory.app.tasks.presentation.TaskLabels.costSoFar;
import static org.folio.factory.app.tasks.presentation.TaskLabels.incompleteCostNote;
import static org.folio.factory.app.tasks.presentation.TaskLabels.minutes;
import static org.folio.factory.app.tasks.presentation.TaskLabels.queued;
import static org.folio.factory.app.tasks.presentation.TaskLabels.triggeredDetail;
import static org.folio.factory.app.tasks.presentation.TaskLabels.working;

/** The Runs rows, the in-progress strip and the "cannot run here" lines of My tasks. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class RunRows {

    /** {@code standing}: where PR feedback stands on the run of the task's pull request. */
    public static TriggeredRow row(TaskView view, FeedbackStanding standing) {
        var latest = view.attempts().getLast();
        var status = AttemptStatus.of(view, view.standingAttempt().orElse(latest), standing);
        var when = latest.startedAt() != null ? latest.startedAt() : latest.createdAt();
        var action = standing == FeedbackStanding.WAITS_FOR_YOU ? RunAction.PR_WAITING : action(view, latest);
        var costTitle = working(view) ? null : costNoteTitle(incompleteCostNote(view.usage()));
        var detail = feedbackDetail(standing).orElseGet(() -> detail(view));
        return new TriggeredRow(view.taskId(), view.displayKey(), view.jiraUrl(), view.title(),
                status.chipLabel(), status.chipClass(), status.chipUrl(),
                detail == null ? null : AttemptFailure.firstSentence(detail), detail, cost(view, latest), costTitle,
                ago(when),
                PageTime.timestamp(when), action,
                actionUrl(view, latest, action), action == RunAction.MARK_STOPPED ? latest.attemptId() : null);
    }

    /** The task's total, or the cost so far of the attempt that works now ($0.00 before it reports usage). */
    private static String cost(TaskView view, TaskView.AttemptHistoryEntry latest) {
        if (!working(view)) {
            return costLabel(view.usage());
        }
        var soFar = costSoFar(latest.usage());
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

    /**
     * A row has a button only for what waits for the developer, so a task that works or has a pull request gets
     * none here. A failed task offers Retry on its task page, where the status card says why it failed.
     */
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
        if (latest.executionId() != null && working(view)) {
            return RunAction.OPEN_RUN;
        }
        return view.allowedActions().retry() ? RunAction.RETRY : RunAction.NONE;
    }

    private static String actionUrl(TaskView view, TaskView.AttemptHistoryEntry latest, RunAction action) {
        return switch (action) {
            case PR_FEEDBACK, PR_WAITING -> "/executions/" + latest.executionId() + "#pr-feedback";
            case OPEN_RUN -> "/executions/" + latest.executionId();
            case ANSWER -> "/tasks/" + view.taskId() + "#answer";
            case RETRY -> "/tasks/" + view.taskId() + "#retry";
            case MARK_STOPPED, NONE -> null;
        };
    }

    private static Optional<String> feedbackDetail(FeedbackStanding standing) {
        return switch (standing) {
            case WAITS_FOR_YOU -> Optional.of(TaskRows.FEEDBACK_WAITS);
            case WORKING -> Optional.of(TaskRows.FEEDBACK_WORKS);
            default -> Optional.empty();
        };
    }

    private static boolean delivered(TaskView view) {
        return view.state() == TaskView.TaskState.PR_READY && view.pullRequestUrl() != null;
    }

    /** A running task needs no detail: its chip says it; a queued one says its place in line. */
    private static String detail(TaskView view) {
        return working(view) && !queued(view) ? null : triggeredDetail(view);
    }

    private static String cannotRunReason(AvailableRow row) {
        if (row.problem() == TaskRoutingReason.Problem.NO_REPOSITORY) {
            return "Project " + row.projectKey() + " has no repository in " + TaskRoutingReason.CATALOG_FILE + ".";
        }
        if (row.problem() == TaskRoutingReason.Problem.SEVERAL_REPOSITORIES) {
            return "Project " + row.projectKey() + " maps to more than one repository in "
                    + TaskRoutingReason.CATALOG_FILE + ".";
        }
        return AttemptFailure.reason(row.problemReason());
    }
}
