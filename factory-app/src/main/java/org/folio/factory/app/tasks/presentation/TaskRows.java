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
import java.util.Optional;
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
import static org.folio.factory.app.tasks.presentation.TaskLabels.pullRequestNote;
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
        var failure = failure(view);
        return new TaskDetail(view.taskId(), view.displayKey(), view.jiraUrl(), view.title(), repositoryLine(view),
                view.version(), view.inputFingerprint(), view.destinationFingerprint(), stateLabel,
                stateClass(label), failure.map(AttemptFailure::headline).orElseGet(() -> headline(view)),
                failure.map(AttemptFailure::reason).orElse(null),
                failure.map(found -> pullRequestNote(view.reason())).orElse(null),
                view.draftReasons(), costLabel(view.usage()), spentNote(view), usageNote(view.usage()),
                view.allowedActions().retry(), view.attempts().size() + 1, runUrl(view),
                view.pullRequestUrl(), view.pullRequestUrl() == null ? null
                        : "Open p" + pullRequestLabel(view.pullRequestUrl()).substring(1),
                latest == null ? null : markStoppedAttemptId(latest));
    }

    public static AttemptRow attemptRow(TaskView.AttemptHistoryEntry attempt) {
        var label = attemptLabel(attempt);
        var costNote = attemptCostNote(attempt);
        return new AttemptRow(attempt.ordinal(), attemptKind(attempt), attemptMeta(attempt),
                costLabel(attempt.usage()), costNote, costNoteTitle(costNote),
                costText(costLabel(attempt.usage()), costNote), label, stateClass(label), attemptResult(attempt),
                attempt.executionId());
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

    private static String headline(TaskView view) {
        return view.needsAnswer() ? "The agent stopped with a question" : resultSentence(view);
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
    private static String runUrl(TaskView view) {
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

    /** "mgr-applications → owner/mgr-applications · master", or only the name when no delivery is set. */
    private String repositoryLine(TaskView view) {
        var name = repository(view);
        var repository = view.destinationRepository() == null ? null
                : repositories.repositories().get(view.destinationRepository());
        var target = repository == null ? null : repository.delivery();
        return name == null || target == null || target.repository() == null ? name
                : name + " → " + target.repository() + " · " + target.baseBranch();
    }
}
