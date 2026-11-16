package org.folio.factory.app.tasks.presentation;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskRoutingReason;
import org.folio.factory.app.web.PageTime;
import org.folio.factory.app.web.UiFormat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static org.folio.factory.app.tasks.presentation.TaskLabels.ago;
import static org.folio.factory.app.tasks.presentation.TaskLabels.finishedCost;
import static org.folio.factory.app.tasks.presentation.TaskLabels.costNoteTitle;
import static org.folio.factory.app.tasks.presentation.TaskLabels.costSoFar;
import static org.folio.factory.app.tasks.presentation.TaskLabels.incompleteCostNote;
import static org.folio.factory.app.tasks.presentation.TaskLabels.minutes;
import static org.folio.factory.app.tasks.presentation.TaskLabels.triggeredDetail;
import static org.folio.factory.app.tasks.presentation.TaskLabels.working;

/** The Started tasks rows, the in-progress strip and the Cannot run here rows of My tasks. */
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
                status.chipLabel(), status.chipClass(), status.chipUrl(), facts(view, latest),
                detail == null ? null : AttemptFailure.firstSentence(detail), detail, cost(view, latest), costTitle,
                ago(when), PageTime.timestamp(when), action, button(view, action),
                action == RunAction.MARK_STOPPED ? latest.attemptId() : null);
    }

    /** The model and effort of the latest attempt, the attempts, and the time the agent worked. */
    private static List<String> facts(TaskView view, TaskView.AttemptHistoryEntry latest) {
        return Stream.of(TaskRunSettings.label(latest.runSettings()), attemptsFact(view, latest), agentTime(view))
                .filter(Objects::nonNull)
                .toList();
    }

    /** "run 2" while it waits in line or works, "2 runs" once the task rests. */
    private static String attemptsFact(TaskView view, TaskView.AttemptHistoryEntry latest) {
        if (view.state() == TaskView.TaskState.WORKING) {
            return "run " + latest.ordinal();
        }
        int count = view.attempts().size();
        return count + (count == 1 ? " run" : " runs");
    }

    /**
     * The time the agent worked in all attempts together, as the Total of Runs on the task page adds it up. Null
     * while the task is queued or works, and when no attempt reached the agent.
     */
    private static String agentTime(TaskView view) {
        var paid = view.attempts().stream().filter(TaskView.AttemptHistoryEntry::paidStart).toList();
        if (view.state() == TaskView.TaskState.WORKING || paid.isEmpty()) {
            return null;
        }
        return TaskLabels.addedMinutes(paid);
    }

    /** The page where the developer acts: the task page at the answer or the next attempt, or PR feedback. */
    private static TriggeredRow.Button button(TaskView view, RunAction action) {
        var taskPage = "/tasks/" + view.taskId();
        return switch (action) {
            case ANSWER -> new TriggeredRow.Button("Answer", taskPage + "#answer");
            case RETRY -> new TriggeredRow.Button("Start run " + (view.attempts().size() + 1),
                    taskPage + "#retry");
            case PR_WAITING -> new TriggeredRow.Button("Open PR feedback", TaskRows.runUrl(view) + "#pr-feedback");
            default -> null;
        };
    }

    /** The task's total, or the cost so far of the attempt that works now ($0.00 before it reports usage). */
    private static String cost(TaskView view, TaskView.AttemptHistoryEntry latest) {
        if (!working(view)) {
            return finishedCost(view);
        }
        var soFar = costSoFar(latest.usage());
        return soFar == null ? null : soFar + " " + UiFormat.SO_FAR;
    }

    /** The running task, when one runs: the strip above Ready to run is a shortcut to its run. */
    public static Optional<InProgressStrip> strip(List<TaskView> triggered) {
        return triggered.stream()
                .filter(view -> working(view) && view.attempts().getLast().executionId() != null)
                .findFirst()
                .map(RunRows::toStrip);
    }

    /** Tasks that cannot run, one row each, in the order of the list. */
    public static List<CannotRunRow> cannotRun(List<AvailableRow> available) {
        return available.stream().filter(row -> !row.selectable()).map(RunRows::toCannotRunRow).toList();
    }

    /** Set up repositories is offered only when a repository would let a task run. */
    public static boolean needsRepository(List<CannotRunRow> rows) {
        return rows.stream().anyMatch(CannotRunRow::needsRepository);
    }

    private static CannotRunRow toCannotRunRow(AvailableRow row) {
        return new CannotRunRow(row.key(), row.jiraUrl(), row.title(), row.jiraStatus(), cannotRunChip(row),
                cannotRunReason(row), row.problem() != null);
    }

    private static String cannotRunChip(AvailableRow row) {
        if (row.problem() == TaskRoutingReason.Problem.NO_REPOSITORY) {
            return "No repository for " + row.projectKey();
        }
        if (row.problem() == TaskRoutingReason.Problem.SEVERAL_REPOSITORIES) {
            return "Several repositories for " + row.projectKey();
        }
        return "Cannot run";
    }

    private static InProgressStrip toStrip(TaskView view) {
        var latest = view.attempts().getLast();
        var started = latest.startedAt() != null ? latest.startedAt() : latest.createdAt();
        var elapsed = minutes(Duration.between(started, Instant.now()).getSeconds());
        var run = UiFormat.joinNonNull(TaskRunSettings.label(latest.runSettings()), elapsed);
        return new InProgressStrip(view.displayKey(), view.jiraUrl(), view.title(), run,
                "/executions/" + latest.executionId());
    }

    /**
     * A row has a button only for what waits for the developer, so a task that works or has a pull request gets
     * none here. The button of a failed task opens its task page at the next attempt, where the status card says
     * why it failed.
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

    /** PR feedback that waits for the developer needs no words: the row says Your turn and has the button. */
    private static Optional<String> feedbackDetail(FeedbackStanding standing) {
        return switch (standing) {
            case WORKING -> Optional.of(TaskRows.FEEDBACK_WORKS);
            default -> Optional.empty();
        };
    }

    private static boolean delivered(TaskView view) {
        return view.state() == TaskView.TaskState.PR_READY && view.pullRequestUrl() != null;
    }

    /** A running task needs no detail: its chip says it; a queued one says its place in line. */
    private static String detail(TaskView view) {
        return working(view) ? null : triggeredDetail(view);
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
