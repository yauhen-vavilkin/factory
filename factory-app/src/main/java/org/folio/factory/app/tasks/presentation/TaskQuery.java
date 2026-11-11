package org.folio.factory.app.tasks.presentation;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.admission.TaskAdmissionChecks;
import org.folio.factory.app.tasks.execution.TaskOutcomeService;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.TaskRoutingReason;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.app.tasks.source.TaskJiraInstance;
import org.folio.factory.app.web.AttemptUsageSummary;
import org.folio.factory.connectors.jira.JiraProperties;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only projection of logical tasks for My tasks and the task
 * summary. Each task is projected from its persisted attempt history, the bound
 * executions' audit accounting and the installation record: one distinct
 * current state per outcome, the queue position of waiting work, complete
 * attempt history whatever happens to the task in Jira, and task tokenomics
 * aggregated from typed per-execution totals. Nothing here mutates task or
 * engine state.
 */
@Service
@RequiredArgsConstructor
public class TaskQuery {

    private final TaskRepository tasks;
    private final TaskAttemptRepository attempts;
    private final TaskInstallationRepository installations;
    private final PipelineExecutionRepository executions;
    private final AuditLog auditLog;
    private final JiraProperties jira;
    private final TaskJiraInstance jiraInstance;
    private final AttemptPullRequests pullRequests;
    private final JsonMapper json = JsonMapper.builder().build();

    /** Available: in My tasks and never started; Triggered: started at least once, newest first. */
    public record MyTasks(List<TaskView> available, Page<TaskView> triggered) {
    }

    @Transactional(readOnly = true)
    public MyTasks myTasks(Pageable triggeredPage) {
        Map<UUID, Integer> queue = queuePositions();
        TaskInstallation installation = installation();
        List<TaskView> available = tasks.findAvailable(jiraInstance.current(), TaskAttempt.State.WITHDRAWN)
                .stream()
                .map(task -> project(task, queue, installation))
                .toList();
        Page<UUID> triggeredIds = attempts.findTriggeredTaskIds(TaskAttempt.State.WITHDRAWN, triggeredPage);
        Map<UUID, Task> byId = tasks.findAllById(triggeredIds.getContent()).stream()
                .collect(Collectors.toMap(Task::getId, Function.identity()));
        return new MyTasks(available, triggeredIds.map(id -> project(byId.get(id), queue, installation)));
    }

    /** The task behind a run page, when a task attempt started that execution; empty for any other run. */
    @Transactional(readOnly = true)
    public Optional<RunContext> runContext(UUID executionId) {
        return attempts.findByExecutionId(executionId)
                .flatMap(attempt -> RunContext.of(detail(attempt.getTaskId()), executionId));
    }

    @Transactional(readOnly = true)
    public TaskView detail(UUID taskId) {
        return project(tasks.findById(taskId)
                .orElseThrow(() -> new NoSuchElementException("No task " + taskId)), queuePositions(), installation());
    }

    /** The Jira key of a task, for messages that name it; empty for an unknown task. */
    public Optional<String> displayKey(UUID taskId) {
        return tasks.findById(taskId).map(Task::getDisplayKey);
    }

    private TaskInstallation installation() {
        return installations.findById(1).orElse(null);
    }

    /** 1-based place of each waiting attempt in the order the dispatcher starts them. */
    private Map<UUID, Integer> queuePositions() {
        List<TaskAttempt> waiting = attempts.findDispatchable(TaskAttempt.State.RESERVED, PageRequest.of(0, 200));
        Map<UUID, Integer> positions = new HashMap<>();
        for (int i = 0; i < waiting.size(); i++) {
            positions.put(waiting.get(i).getId(), i + 1);
        }
        return positions;
    }

    private TaskView project(Task task, Map<UUID, Integer> queue, TaskInstallation installation) {
        var history = attempts.findByTaskIdOrderByOrdinalAsc(task.getId());
        var executionsById = executionsById(history);
        var progress = attemptProgress(history, executionsById, Instant.now());
        var outcome = outcome(task, history, installation, progress);
        return toView(task, queue, outcome, progress);
    }

    private Map<UUID, PipelineExecution> executionsById(List<TaskAttempt> history) {
        var ids = history.stream().map(TaskAttempt::getExecutionId).filter(Objects::nonNull).toList();
        return executions.findAllById(ids).stream()
                .collect(Collectors.toMap(PipelineExecution::getId, Function.identity()));
    }

    private AttemptProgress attemptProgress(List<TaskAttempt> history,
                                            Map<UUID, PipelineExecution> executionsById, Instant now) {
        var rows = history.stream().map(attempt -> attemptRow(attempt, executionsById, now)).toList();
        var usageMissingAfterPaidStart = rows.stream().anyMatch(AttemptRow::usageMissingAfterPaidStart);
        var usage = TaskUsage.aggregate(rows.stream().map(AttemptRow::totals).toList(),
                usageMissingAfterPaidStart);
        var lastLive = rows.stream().filter(AttemptRow::live).reduce((first, second) -> second);
        return new AttemptProgress(rows.stream().map(AttemptRow::entry).toList(), usage,
                lastLive.map(row -> row.entry().stage()).orElse(null),
                lastLive.map(AttemptRow::stageStartedAt).orElse(null));
    }

    private AttemptRow attemptRow(TaskAttempt attempt, Map<UUID, PipelineExecution> executionsById, Instant now) {
        var execution = attempt.getExecutionId() == null ? null : executionsById.get(attempt.getExecutionId());
        List<AuditEvent> events = execution == null ? List.of() : auditLog.forExecution(execution.getId());
        var totals = AttemptUsageSummary.totals(events,
                execution != null && execution.getStatus() == ExecutionStatus.RUNNING);
        var usageMissingAfterPaidStart = attempt.getPaidStartAt() != null
                && totals.tokens() == 0 && !totals.tokensReported();
        // An attempt in flight names the step its run started last, also while the run waits, so its page never
        // goes back to "Starting the run" once a step has started.
        var inFlight = execution != null && attempt.getState().isInFlight();
        var stageStart = inFlight ? AttemptClock.stepStart(events).orElse(null) : null;
        var stage = inFlight ? workingStage(execution, stageStart)
                : TaskOutcomeService.stageOf(attempt.getResultReason());
        var entry = historyEntry(attempt, stage, AttemptUsageSummary.activeSeconds(events, now),
                TaskUsage.aggregate(List.of(totals), usageMissingAfterPaidStart));
        return new AttemptRow(entry, totals, usageMissingAfterPaidStart, inFlight,
                stageStart == null ? null : stageStart.getOccurredAt());
    }

    /** The step the run works on, or that its run ended before the dispatcher closed the attempt. */
    private static String workingStage(PipelineExecution execution, AuditEvent stageStart) {
        if (execution.getStatus().isTerminal()) {
            return TaskLabels.RUN_ENDED;
        }
        return stageStart == null ? null : stageStart.getStepId();
    }

    private TaskView.AttemptHistoryEntry historyEntry(TaskAttempt attempt, String stage, long activeSeconds,
                                                      TaskUsage usage) {
        var pullRequest = pullRequests.of(attempt);
        return new TaskView.AttemptHistoryEntry(attempt.getId(), attempt.getOrdinal(), attempt.getKind(),
                attempt.getAdmittedVia(), attempt.getState(), stage, attempt.getResultReason(),
                pullRequest.url(), pullRequest.draftReasons(), attempt.getExecutionId(), attempt.getCreatedAt(),
                attempt.getStartedAt(),
                attempt.getPaidStartAt() != null, activeSeconds, usage, attempt.getRunSettings());
    }

    private static TaskOutcome outcome(Task task, List<TaskAttempt> history, TaskInstallation installation,
                                       AttemptProgress progress) {
        // A withdrawal after real work (left by the removed automatic mode) does not
        // stand for the task: it shows the last attempt that actually ran.
        var latest = history.stream()
                .filter(attempt -> attempt.getState() != TaskAttempt.State.WITHDRAWN)
                .reduce((first, second) -> second)
                .orElse(history.isEmpty() ? null : history.getLast());
        var allWithdrawn = !history.isEmpty() && history.stream()
                .allMatch(attempt -> attempt.getState() == TaskAttempt.State.WITHDRAWN);
        var inFlight = history.stream().anyMatch(attempt -> attempt.getState().isInFlight());
        var state = projectState(task, latest, installation, inFlight, allWithdrawn);
        var reason = taskReason(task, state, latest);
        var working = state == TaskView.TaskState.WORKING;
        var stage = working ? progress.liveStage() : TaskOutcomeService.stageOf(reason);
        return new TaskOutcome(latest, state, reason, stage, working ? progress.liveStageStartedAt() : null,
                allowedActions(task, history, latest, allWithdrawn));
    }

    private static String taskReason(Task task, TaskView.TaskState state, TaskAttempt latest) {
        if (state == TaskView.TaskState.UNSUPPORTED) {
            return task.getRoutingReason();
        }
        if (state == TaskView.TaskState.AWAITING_SELECTION || latest == null) {
            return null;
        }
        return latest.getResultReason();
    }

    private static TaskView.AllowedActions allowedActions(Task task, List<TaskAttempt> history,
                                                               TaskAttempt latest, boolean allWithdrawn) {
        var eligible = task.getEligibility() == Task.Eligibility.ELIGIBLE;
        var selectable = eligible && task.isInScope() && (history.isEmpty() || allWithdrawn);
        var retriable = eligible && latest != null
                && (latest.getState() == TaskAttempt.State.STOPPED
                        || latest.getState() == TaskAttempt.State.WITHDRAWN && !allWithdrawn);
        return new TaskView.AllowedActions(selectable, retriable);
    }

    /** The task's pull request is the one of the attempt it stands for, also while that attempt is in flight. */
    private TaskView toView(Task task, Map<UUID, Integer> queue, TaskOutcome outcome,
                                 AttemptProgress progress) {
        var latest = outcome.latest();
        var pullRequest = latest == null ? null : progress.entries().stream()
                .filter(entry -> entry.attemptId().equals(latest.getId())).findFirst().orElse(null);
        return new TaskView(task.getId(), task.getDisplayKey(), jiraLink(task.getDisplayKey()), task.getTitle(),
                task.getProjectKey(), jiraStatus(task), outcome.state(),
                latest == null ? null : queue.get(latest.getId()),
                outcome.reason(), outcome.stage(), outcome.stageStartedAt(),
                TaskRoutingReason.eligibleRepositoryKey(task.getRoutingReason()),
                pullRequest == null ? null : pullRequest.pullRequestUrl(),
                pullRequest == null ? null : pullRequest.draftReasons(),
                task.isInScope(), task.isAddedByKey(), task.getVersion(),
                TaskAdmissionChecks.inputFingerprint(task), TaskAdmissionChecks.destinationFingerprint(task),
                progress.entries(), progress.usage(), outcome.allowedActions());
    }

    private static TaskView.TaskState projectState(
            Task task, TaskAttempt latest, TaskInstallation installation,
            boolean working, boolean allWithdrawn) {
        if (working) {
            return TaskView.TaskState.WORKING;
        }
        if (latest == null) {
            return task.getEligibility() == Task.Eligibility.INELIGIBLE
                    ? TaskView.TaskState.UNSUPPORTED
                    : TaskView.TaskState.AWAITING_SELECTION;
        }
        if (allWithdrawn) {
            return TaskView.TaskState.WITHDRAWN;
        }
        return switch (latest.getState()) {
            case NEEDS_INSPECTION -> installation != null
                    && latest.getId().equals(installation.getActiveAttemptId())
                    ? TaskView.TaskState.PROCESSING_PAUSED
                    : TaskView.TaskState.UNCERTAIN;
            case STOPPED -> TaskView.TaskState.STOPPED;
            case PR_READY -> TaskView.TaskState.PR_READY;
            case WITHDRAWN -> TaskView.TaskState.WITHDRAWN;
            default -> TaskView.TaskState.WORKING;
        };
    }

    /** The task's status in Jira at the last scan or run by key, read from its snapshot. */
    private String jiraStatus(Task task) {
        try {
            JsonNode snapshot = json.readTree(task.getIssueSnapshot());
            String status = snapshot.path("status").asString("");
            return status.isBlank() ? null : status;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String jiraLink(String displayKey) {
        String base = jira.baseUrl();
        if (base == null || !base.strip().matches("https?://[A-Za-z0-9.-]+(:[0-9]+)?")) {
            return null;
        }
        if (displayKey == null || !displayKey.matches("[A-Z][A-Z0-9]*-[0-9]+")) {
            return null;
        }
        return base.strip() + "/browse/" + displayKey;
    }

    private record AttemptRow(TaskView.AttemptHistoryEntry entry, AttemptUsageSummary.Totals totals,
                              boolean usageMissingAfterPaidStart, boolean live, Instant stageStartedAt) {
    }

    /** The history entries, the task usage, and the stage of the last running attempt. */
    private record AttemptProgress(List<TaskView.AttemptHistoryEntry> entries, TaskUsage usage,
                                   String liveStage, Instant liveStageStartedAt) {
    }

    private record TaskOutcome(TaskAttempt latest, TaskView.TaskState state, String reason, String stage,
                               Instant stageStartedAt, TaskView.AllowedActions allowedActions) {
    }
}
