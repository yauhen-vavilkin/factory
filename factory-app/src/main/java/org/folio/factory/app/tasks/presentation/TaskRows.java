package org.folio.factory.app.tasks.presentation;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskRoutingReason;
import org.folio.factory.app.tasks.source.TaskJiraInstance;
import org.folio.factory.app.tasks.source.TaskSourceProperties;
import org.folio.factory.app.web.FeedbackUi;
import org.folio.factory.app.web.PageTime;
import org.folio.factory.app.web.UiFormat;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.folio.factory.app.tasks.presentation.TaskLabels.ago;
import static org.folio.factory.app.tasks.presentation.TaskLabels.attemptResult;
import static org.folio.factory.app.tasks.presentation.TaskLabels.finishedCost;
import static org.folio.factory.app.tasks.presentation.TaskLabels.costNoteTitle;
import static org.folio.factory.app.tasks.presentation.TaskLabels.costSoFar;
import static org.folio.factory.app.tasks.presentation.TaskLabels.incompleteCostNote;
import static org.folio.factory.app.tasks.presentation.TaskLabels.minutes;
import static org.folio.factory.app.tasks.presentation.TaskLabels.pullRequestLabel;
import static org.folio.factory.app.tasks.presentation.TaskLabels.pullRequestNote;
import static org.folio.factory.app.tasks.presentation.TaskLabels.notStartedSentence;
import static org.folio.factory.app.tasks.presentation.TaskLabels.usageNote;
import static org.folio.factory.app.tasks.presentation.TaskLabels.working;
import static org.folio.factory.app.web.UiFormat.COST_UNKNOWN;
import static org.folio.factory.app.web.UiFormat.SO_FAR;

/**
 * Page rows of My tasks, the task summary and the run page header, built from the
 * task projection.
 */
@Component
@RequiredArgsConstructor
public class TaskRows {

    static final String FEEDBACK_WAITS = "PR feedback waits for you";
    static final String FEEDBACK_WORKS = "The agent works on PR feedback";
    private static final int QUOTE = 80;

    private final TaskSourceProperties source;
    private final TaskJiraInstance jira;
    private final DevFactoryProperties repositories;
    private final RunLimits limits;
    private final FeedbackUi feedback;

    /** A row under Started tasks. */
    public TriggeredRow triggeredRow(TaskView view) {
        return RunRows.row(view, feedback.standing(view));
    }

    public SourceCard sourceCard(TaskInstallation installation) {
        String base = jira.baseUrl();
        boolean ready = source.assignee() != null && base != null;
        return new SourceCard(source.assignee(), base == null ? null : jira.current(),
                ready ? source.effectiveQuery() : null, ready ? source.searchUrl(base) : null,
                ago(installation.getLastSuccessfulSyncAt()), installation.getSyncProblem());
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
        var latest = view.attempts().isEmpty() ? null : view.attempts().getLast();
        var failure = failure(view);
        var reason = failure.map(AttemptFailure::reason).orElse(null);
        var pullRequest = view.pullRequestUrl();
        var standing = feedback.standing(view);
        var feedbackWaits = standing == FeedbackStanding.WAITS_FOR_YOU;
        var note = failure.map(found -> pullRequestNote(view.reason())).orElse(feedbackNote(standing));
        return new TaskDetail(view.taskId(), view.displayKey(), view.jiraUrl(), view.title(),
                repositoryLine(view.destinationRepository()), view.version(), view.inputFingerprint(),
                view.destinationFingerprint(), status(view, standing), reason,
                note, AttemptFailure.limitsUrl(reason),
                view.draftReasons(), spent(view), spentNote(view), usageNote(view.usage()), clock(view),
                view.allowedActions().retry(), view.attempts().size() + 1,
                runLink(view, pullRequest != null, feedbackWaits),
                pullRequest, pullRequest == null ? null : "Open p" + pullRequestLabel(pullRequest).substring(1),
                latest == null ? null : markStoppedAttemptId(latest),
                turn(view, failure.isPresent(), standing));
    }

    /** PR feedback decides first: it waits for the developer or a round works now. */
    private static Turn turn(TaskView view, boolean failed, FeedbackStanding standing) {
        if (standing == FeedbackStanding.WAITS_FOR_YOU) {
            return Turn.YOURS;
        }
        if (standing == FeedbackStanding.WORKING) {
            return Turn.WORKING;
        }
        return turn(view, failed);
    }

    /**
     * The developer's turn when the agent asked, when the latest attempt failed and the next one can start, or
     * when Factory could not confirm how a run ended.
     */
    private static Turn turn(TaskView view, boolean failed) {
        var latest = view.attempts().isEmpty() ? null : view.attempts().getLast();
        var retry = view.allowedActions().retry() && (failed || view.needsAnswer());
        if (retry || latest != null && markStoppedAttemptId(latest) != null) {
            return Turn.YOURS;
        }
        if (TaskLabels.queued(view)) {
            return Turn.QUEUED;
        }
        return view.state() == TaskView.TaskState.WORKING ? Turn.WORKING : Turn.NONE;
    }

    private static String feedbackNote(FeedbackStanding standing) {
        return switch (standing) {
            case WAITS_FOR_YOU -> FEEDBACK_WAITS + ".";
            case WORKING -> FEEDBACK_WORKS + ".";
            default -> null;
        };
    }

    public static AttemptRow attemptRow(TaskView view, TaskView.AttemptHistoryEntry attempt) {
        var status = AttemptStatus.of(view, attempt);
        var costNote = attemptCostNote(attempt);
        var cost = SO_FAR.equals(costNote) ? costSoFar(attempt.usage()) : finishedCost(attempt);
        var started = PageTime.day(attempt.startedAt());
        return new AttemptRow(attempt.ordinal(), attemptKind(attempt), attemptMeta(attempt),
                TaskRunSettings.label(attempt.runSettings()), started, attemptClock(attempt),
                started == null ? "not started" : null, quote(attempt), cost, costNoteTitle(costNote),
                costText(cost, costNote), status, attemptResult(attempt, status), attempt.executionId());
    }

    /**
     * "mgr-applications → owner/mgr-applications · master": the repository the task is checked out from, then
     * where its pull request goes; only the name when no delivery is set, null for a task with no repository.
     */
    public String repositoryLine(String repositoryKey) {
        var repository = repositoryKey == null ? null : repositories.repositories().get(repositoryKey);
        var name = repositoryKey == null ? null : repositoryName(repositoryKey);
        var target = repository == null ? null : repository.delivery();
        return name == null || target == null || target.repository() == null ? name
                : name + " → " + target.repository() + " · " + target.baseBranch();
    }

    /** The chip and headline of the attempt the task stands for; the task's own words before its first attempt. */
    private static AttemptStatus status(TaskView view, FeedbackStanding standing) {
        var attempt = view.standingAttempt();
        if (attempt.isPresent()) {
            return AttemptStatus.of(view, attempt.get(), standing);
        }
        var label = view.state() == TaskView.TaskState.UNSUPPORTED ? "Not eligible" : "Not started";
        return new AttemptStatus(label, "chip chip-neutral", null, notStartedSentence(view));
    }

    /** The time of the attempt that works now, as the run page shows it; null while no attempt works. */
    private AttemptClock clock(TaskView view) {
        if (!working(view) || view.pullRequestUrl() != null) {
            return null;
        }
        var latest = view.attempts().getLast();
        var facts = new AttemptClock.Facts(latest.activeSeconds(), view.stage(), view.stageStartedAt(),
                !TaskLabels.RUN_ENDED.equals(view.stage()), Instant.now());
        return AttemptClock.of(facts, limits.limits());
    }

    /** The run page, at its PR feedback part once the attempt has a pull request. */
    private static TaskDetail.RunLink runLink(TaskView view, boolean pullRequest, boolean feedbackWaits) {
        var url = runUrl(view);
        if (url == null) {
            return null;
        }
        return feedbackWaits ? new TaskDetail.RunLink("Open PR feedback", url + "#pr-feedback", true)
                : new TaskDetail.RunLink("Open run", pullRequest ? url + "#pr-feedback" : url, false);
    }

    /** The notice that the queue waits on the attempt of task {@code key}, which needs inspection. */
    public static String queueWaiting(String key) {
        return key + " needs inspection: Factory could not confirm how its run ended. It rechecks every minute.";
    }

    /** The failure of the latest attempt, when the task stopped without a question. */
    private static Optional<AttemptFailure> failure(TaskView view) {
        if (view.state() != TaskView.TaskState.STOPPED || view.needsAnswer() || view.attempts().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(AttemptFailure.of(view.attempts().getLast().ordinal(), view.reason()));
    }

    /** Same notes as Triggered; a queued attempt already says "not started" in Started. */
    private static String attemptCostNote(TaskView.AttemptHistoryEntry attempt) {
        return switch (attempt.state()) {
            case RESERVED -> null;
            case QUEUED, RUNNING -> SO_FAR;
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
            return costNote == null || SO_FAR.equals(costNote) ? "—" : costNote;
        }
        return SO_FAR.equals(costNote) ? cost + " " + SO_FAR : cost;
    }

    /** What the task cost; "$0.00" while its first attempt works and has not reported usage yet. */
    private static String spent(TaskView view) {
        return working(view) ? costSoFar(view.usage()) : finishedCost(view);
    }

    /**
     * "spent in 2 attempts" beside the amount, or "cost unknown" when the attempts that started have no cost
     * figure; null before that. Why the amount may be too low is its title.
     */
    private static String spentNote(TaskView view) {
        int count = view.attempts().size();
        if (spent(view) == null) {
            return view.attempts().stream().anyMatch(attempt -> attempt.startedAt() != null) ? COST_UNKNOWN : null;
        }
        return "spent in " + count + (count == 1 ? " attempt" : " attempts");
    }

    /** The run of the latest attempt that has one, in every state. */
    static String runUrl(TaskView view) {
        return view.attempts().reversed().stream()
                .map(TaskView.AttemptHistoryEntry::executionId)
                .filter(Objects::nonNull)
                .findFirst()
                .map(executionId -> "/executions/" + executionId)
                .orElse(null);
    }

    /** "first run", "retry", "retry with your instructions" or "answer to the agent's question". */
    private static String attemptKind(TaskView.AttemptHistoryEntry attempt) {
        if (attempt.kind() != TaskAttempt.Kind.RETRY) {
            return "first run";
        }
        var instructions = TaskRunSettings.instructions(attempt.runSettings());
        if (instructions == null) {
            return "retry";
        }
        return TaskAnswers.isAnswer(instructions) ? "answer to the agent's question" : "retry with your instructions";
    }

    /**
     * The meta line for its title, without the start time, which the line shows in local time:
     * "glm-5.3 · max · 13 min · "first line"".
     */
    private static String attemptMeta(TaskView.AttemptHistoryEntry attempt) {
        var clock = attemptClock(attempt);
        return UiFormat.joinNonNull(TaskRunSettings.label(attempt.runSettings()),
                attempt.startedAt() == null ? "not started" : null,
                clock == null || clock.start() != null ? null : clock.time(), quote(attempt));
    }

    /** The time the attempt worked: ticking while it is in flight, null before its paid start. */
    private static AttemptClock attemptClock(TaskView.AttemptHistoryEntry attempt) {
        if (!attempt.paidStart()) {
            return null;
        }
        if (!attempt.state().isInFlight() || attempt.pullRequestUrl() != null
                || TaskLabels.RUN_ENDED.equals(attempt.stage())) {
            return new AttemptClock(minutes(attempt.activeSeconds()), null, null, null);
        }
        var facts = new AttemptClock.Facts(attempt.activeSeconds(), null, null, true, Instant.now());
        return AttemptClock.of(facts, new AttemptClock.Limits(0, Duration.ZERO));
    }

    /** The first line of the developer's instructions in quotes, null when the attempt has none. */
    private static String quote(TaskView.AttemptHistoryEntry attempt) {
        var instructions = TaskRunSettings.instructions(attempt.runSettings());
        return instructions == null ? null : "\"" + firstLine(instructions) + "\"";
    }

    private static String firstLine(String text) {
        var line = text.strip().lines().findFirst().orElse("");
        return line.length() <= QUOTE ? line : line.substring(0, QUOTE - 1) + "…";
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

}
