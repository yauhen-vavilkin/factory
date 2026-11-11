package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.persistence.TaskAttempt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.folio.factory.app.tasks.presentation.TaskLabels.pullRequestNote;

/**
 * The task behind a run page: its key and title, where it is checked out and where its pull request goes, where
 * this attempt stands among the task's attempts, and the status, failure and next step the run page shows for it,
 * from the same {@link AttemptStatus} as the task page. {@code failure} is null unless this attempt failed;
 * {@code answerUrl}, {@code retryUrl} and {@code standing} are null when the page offers no such step;
 * {@code repositoryKey} is null when the task has no repository; {@code draftReasons} is null unless the pull
 * request is a draft.
 */
public record RunContext(UUID taskId, String key, String title, String jiraUrl, int ordinal, String settings,
                         AttemptStatus status, AttemptFailure failure, String failureNote, String answerUrl,
                         String retryLabel, String retryUrl, Standing standing, boolean inFlight,
                         String repositoryKey, List<String> draftReasons) {

    /** "Attempt 1 of 3. Latest: attempt 3, Draft PR #14.", the line an older attempt's run page shows. */
    public record Standing(String lead, String latestText, String latestUrl, String tail) {
    }

    /** The context of the attempt that started this execution; empty when the task has no such attempt. */
    public static Optional<RunContext> of(TaskView view, UUID executionId) {
        return view.attempts().stream()
                .filter(attempt -> executionId.equals(attempt.executionId()))
                .findFirst()
                .map(attempt -> of(view, attempt));
    }

    private static RunContext of(TaskView view, TaskView.AttemptHistoryEntry attempt) {
        var latest = attempt == view.attempts().getLast();
        var failed = failed(attempt);
        var retry = latest && failed && view.allowedActions().retry();
        return new RunContext(view.taskId(), view.displayKey(), view.title(), view.jiraUrl(), attempt.ordinal(),
                TaskRunSettings.label(attempt.runSettings()), AttemptStatus.of(view, attempt),
                failed ? AttemptFailure.of(attempt.ordinal(), attempt.reason()) : null,
                failed ? pullRequestNote(attempt.reason()) : null, answerUrl(view, attempt, latest),
                retry ? "Start attempt " + (view.attempts().size() + 1) + " on the task page" : null,
                retry ? "/tasks/" + view.taskId() + "#retry" : null,
                latest ? null : standing(view, attempt), inFlight(attempt), view.destinationRepository(),
                attempt.pullRequestUrl() == null ? null : attempt.draftReasons());
    }

    /** "Attempt 2", the last part of the breadcrumb. */
    public String crumb() {
        return "Attempt " + ordinal;
    }

    /** "MGRAPPS-64 · attempt 2", the browser tab title. */
    public String pageTitle() {
        return key + " · attempt " + ordinal;
    }

    /** The task page form, while the task still waits for the answer to this attempt's question. */
    private static String answerUrl(TaskView view, TaskView.AttemptHistoryEntry attempt, boolean latest) {
        return attempt.asked() && latest && view.needsAnswer() && view.allowedActions().retry()
                ? "/tasks/" + view.taskId() + "#answer" : null;
    }

    private static Standing standing(TaskView view, TaskView.AttemptHistoryEntry attempt) {
        var latest = view.attempts().getLast();
        var latestLabel = AttemptStatus.of(view, latest).chipLabel();
        var state = latest.pullRequestUrl() != null ? latestLabel
                : Character.toLowerCase(latestLabel.charAt(0)) + latestLabel.substring(1);
        return new Standing("Attempt " + attempt.ordinal() + " of " + view.attempts().size() + ". Latest: ",
                "attempt " + latest.ordinal(),
                latest.executionId() == null ? null : "/executions/" + latest.executionId(), ", " + state + ".");
    }

    private static boolean failed(TaskView.AttemptHistoryEntry attempt) {
        return attempt.state() == TaskAttempt.State.STOPPED && !attempt.asked();
    }

    private static boolean inFlight(TaskView.AttemptHistoryEntry attempt) {
        return switch (attempt.state()) {
            case RESERVED, QUEUED, RUNNING -> true;
            default -> false;
        };
    }
}
