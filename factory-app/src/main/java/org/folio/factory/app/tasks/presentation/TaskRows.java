package org.folio.factory.app.tasks.presentation;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskRoutingReason;
import org.folio.factory.app.tasks.source.TaskJiraInstance;
import org.folio.factory.app.tasks.source.TaskSourceProperties;
import org.folio.factory.app.web.PageTime;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.folio.factory.app.tasks.presentation.TaskLabels.ago;
import static org.folio.factory.app.tasks.presentation.TaskLabels.attemptResult;
import static org.folio.factory.app.tasks.presentation.TaskLabels.costLabel;
import static org.folio.factory.app.tasks.presentation.TaskLabels.costNoteTitle;
import static org.folio.factory.app.tasks.presentation.TaskLabels.incompleteCostNote;
import static org.folio.factory.app.tasks.presentation.TaskLabels.minutes;
import static org.folio.factory.app.tasks.presentation.TaskLabels.pullRequestLabel;
import static org.folio.factory.app.tasks.presentation.TaskLabels.pullRequestNote;
import static org.folio.factory.app.tasks.presentation.TaskLabels.notStartedSentence;
import static org.folio.factory.app.tasks.presentation.TaskLabels.usageNote;
import static org.folio.factory.app.tasks.presentation.TaskLabels.working;

/**
 * Page rows of My tasks, the task summary and the run page header, built from the
 * task projection.
 */
@Component
@RequiredArgsConstructor
public class TaskRows {

    private static final int QUOTE = 80;

    private final TaskSourceProperties source;
    private final TaskJiraInstance jira;
    private final DevFactoryProperties repositories;
    private final RunLimits limits;

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
        return new TaskDetail(view.taskId(), view.displayKey(), view.jiraUrl(), view.title(),
                repositoryLine(view.destinationRepository()), view.version(), view.inputFingerprint(),
                view.destinationFingerprint(), status(view), reason,
                failure.map(found -> pullRequestNote(view.reason())).orElse(null), AttemptFailure.limitsUrl(reason),
                view.draftReasons(), costLabel(view.usage()), spentNote(view), usageNote(view.usage()), clock(view),
                view.allowedActions().retry(), view.attempts().size() + 1, runLink(view, pullRequest != null),
                pullRequest, pullRequest == null ? null : "Open p" + pullRequestLabel(pullRequest).substring(1),
                latest == null ? null : markStoppedAttemptId(latest));
    }

    public static AttemptRow attemptRow(TaskView view, TaskView.AttemptHistoryEntry attempt) {
        var status = AttemptStatus.of(view, attempt);
        var costNote = attemptCostNote(attempt);
        var started = PageTime.day(attempt.startedAt());
        return new AttemptRow(attempt.ordinal(), attemptKind(attempt), attemptMeta(attempt),
                TaskRunSettings.label(attempt.runSettings()), started, attemptClock(attempt),
                started == null ? "not started" : null, quote(attempt),
                costLabel(attempt.usage()), costNote, costNoteTitle(costNote),
                costText(costLabel(attempt.usage()), costNote), status, attemptResult(attempt, status),
                attempt.executionId());
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
    private static AttemptStatus status(TaskView view) {
        var attempt = view.standingAttempt();
        if (attempt.isPresent()) {
            return AttemptStatus.of(view, attempt.get());
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

    /** "PR feedback" on the run page once the attempt has a pull request, "Open run" before. */
    private static TaskDetail.RunLink runLink(TaskView view, boolean pullRequest) {
        var url = runUrl(view);
        if (url == null) {
            return null;
        }
        return pullRequest ? new TaskDetail.RunLink("PR feedback", url + "#pr-feedback")
                : new TaskDetail.RunLink("Open run", url);
    }

    /** The notice that the queue waits on an attempt that needs inspection. */
    public static Map<String, Object> queueWaiting(String key, TaskAttempt attempt) {
        Map<String, Object> waiting = new LinkedHashMap<>();
        waiting.put("key", key);
        waiting.put("taskId", attempt.getTaskId());
        waiting.put("attemptId", attempt.getId());
        return waiting;
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

    /** "spent in 2 attempts"; null before the first attempt. Why the amount may be too low is its title. */
    private static String spentNote(TaskView view) {
        int count = view.attempts().size();
        if (count == 0) {
            return null;
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
        return Stream.of(TaskRunSettings.label(attempt.runSettings()), attempt.startedAt() == null ? "not started"
                        : null, clock == null || clock.start() != null ? null : clock.time(), quote(attempt))
                .filter(Objects::nonNull).collect(Collectors.joining(" · "));
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
