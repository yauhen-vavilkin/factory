package org.folio.factory.app.pilot.presentation;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.execution.PilotOutcomeService;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotRoutingReason;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.app.pilot.source.PilotJiraInstance;
import org.folio.factory.app.web.AttemptUsageSummary;
import org.folio.factory.connectors.jira.JiraProperties;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only projection of logical pilot tasks for My tasks and the task
 * summary. Each task is projected from its persisted attempt history, the bound
 * executions' audit accounting and the installation record: one distinct
 * current state per outcome, the queue position of waiting work, complete
 * attempt history whatever happens to the task in Jira, and task tokenomics
 * aggregated from typed per-execution totals. Nothing here mutates pilot or
 * engine state.
 */
@Service
@RequiredArgsConstructor
public class PilotTaskQuery {

    private final PilotTaskRepository tasks;
    private final PilotAttemptRepository attempts;
    private final PilotInstallationRepository installations;
    private final PipelineExecutionRepository executions;
    private final AuditLog auditLog;
    private final JiraProperties jira;
    private final PilotJiraInstance jiraInstance;
    private final JsonMapper json = JsonMapper.builder().build();

    /** Available: in My tasks and never started; Triggered: started at least once, newest first. */
    public record MyTasks(List<PilotTaskView> available, Page<PilotTaskView> triggered) {
    }

    @Transactional(readOnly = true)
    public MyTasks myTasks(Pageable triggeredPage) {
        Map<UUID, Integer> queue = queuePositions();
        List<PilotTaskView> available = tasks.findAvailable(jiraInstance.current(), PilotAttempt.State.WITHDRAWN)
                .stream()
                .map(task -> project(task, queue))
                .toList();
        Page<UUID> triggeredIds = attempts.findTriggeredTaskIds(PilotAttempt.State.WITHDRAWN, triggeredPage);
        Map<UUID, PilotTask> byId = tasks.findAllById(triggeredIds.getContent()).stream()
                .collect(Collectors.toMap(PilotTask::getId, Function.identity()));
        return new MyTasks(available, triggeredIds.map(id -> project(byId.get(id), queue)));
    }

    @Transactional(readOnly = true)
    public PilotTaskView detail(UUID taskId) {
        return project(tasks.findById(taskId)
                .orElseThrow(() -> new NoSuchElementException("No pilot task " + taskId)), queuePositions());
    }

    /** 1-based place of each waiting attempt in the order the dispatcher starts them. */
    private Map<UUID, Integer> queuePositions() {
        List<PilotAttempt> waiting = attempts.findDispatchable(PilotAttempt.State.RESERVED, PageRequest.of(0, 200));
        Map<UUID, Integer> positions = new HashMap<>();
        for (int i = 0; i < waiting.size(); i++) {
            positions.put(waiting.get(i).getId(), i + 1);
        }
        return positions;
    }

    private PilotTaskView project(PilotTask task, Map<UUID, Integer> queue) {
        var history = attempts.findByTaskIdOrderByOrdinalAsc(task.getId());
        var executionsById = executionsById(history);
        var installation = installations.findById(1).orElse(null);
        var progress = attemptProgress(history, executionsById, Instant.now());
        var outcome = outcome(task, history, installation, progress);
        return toView(task, queue, outcome, progress);
    }

    private Map<UUID, PipelineExecution> executionsById(List<PilotAttempt> history) {
        var ids = history.stream().map(PilotAttempt::getExecutionId).filter(Objects::nonNull).toList();
        return executions.findAllById(ids).stream()
                .collect(Collectors.toMap(PipelineExecution::getId, Function.identity()));
    }

    private AttemptProgress attemptProgress(List<PilotAttempt> history,
                                            Map<UUID, PipelineExecution> executionsById, Instant now) {
        var rows = history.stream().map(attempt -> attemptRow(attempt, executionsById, now)).toList();
        var usageMissingAfterPaidStart = rows.stream().anyMatch(AttemptRow::usageMissingAfterPaidStart);
        var usage = PilotTaskUsage.aggregate(rows.stream().map(AttemptRow::totals).toList(),
                usageMissingAfterPaidStart);
        var lastLive = rows.stream().filter(AttemptRow::live).reduce((first, second) -> second);
        return new AttemptProgress(rows.stream().map(AttemptRow::entry).toList(), usage,
                lastLive.map(row -> row.entry().stage()).orElse(null),
                lastLive.map(AttemptRow::stageStartedAt).orElse(null));
    }

    private AttemptRow attemptRow(PilotAttempt attempt, Map<UUID, PipelineExecution> executionsById, Instant now) {
        var execution = attempt.getExecutionId() == null ? null : executionsById.get(attempt.getExecutionId());
        List<AuditEvent> events = execution == null ? List.of() : auditLog.forExecution(execution.getId());
        var live = execution != null && execution.getStatus() == ExecutionStatus.RUNNING;
        var totals = AttemptUsageSummary.totals(events, live);
        var usageMissingAfterPaidStart = attempt.getPaidStartAt() != null
                && totals.tokens() == 0 && !totals.tokensReported();
        var stageStart = live ? liveStageStart(events) : null;
        var stage = live ? (stageStart == null ? null : stageStart.getStepId())
                : PilotOutcomeService.stageOf(attempt.getResultReason(), null);
        var entry = historyEntry(attempt, execution, stage, AttemptUsageSummary.activeSeconds(events, now),
                PilotTaskUsage.aggregate(List.of(totals), usageMissingAfterPaidStart));
        return new AttemptRow(entry, totals, usageMissingAfterPaidStart, live,
                stageStart == null ? null : stageStart.getOccurredAt());
    }

    private PilotTaskView.AttemptHistoryEntry historyEntry(PilotAttempt attempt, PipelineExecution execution,
                                                           String stage, long activeSeconds, PilotTaskUsage usage) {
        var prReady = attempt.getState() == PilotAttempt.State.PR_READY;
        return new PilotTaskView.AttemptHistoryEntry(attempt.getId(), attempt.getOrdinal(),
                attempt.getKind().name(), attempt.getAdmittedVia().name(), attempt.getState().name(), stage,
                attempt.getResultReason(),
                prReady ? attempt.getPublicationIdentity() : null,
                prReady ? draftReasons(attempt) : null,
                attempt.getExecutionId(), execution == null ? null : execution.getStatus().name(),
                attempt.getCreatedAt(), attempt.getStartedAt(), attempt.getDeadlineAt(),
                attempt.getPaidStartAt() != null, activeSeconds, usage, attempt.getRunSettings());
    }

    private static TaskOutcome outcome(PilotTask task, List<PilotAttempt> history, PilotInstallation installation,
                                       AttemptProgress progress) {
        // A withdrawal after real work (left by the removed automatic mode) does not
        // stand for the task: it shows the last attempt that actually ran.
        var latest = history.stream()
                .filter(attempt -> attempt.getState() != PilotAttempt.State.WITHDRAWN)
                .reduce((first, second) -> second)
                .orElse(history.isEmpty() ? null : history.getLast());
        var allWithdrawn = !history.isEmpty() && history.stream()
                .allMatch(attempt -> attempt.getState() == PilotAttempt.State.WITHDRAWN);
        var inFlight = history.stream().anyMatch(attempt -> attempt.getState().isLive()
                && attempt.getState() != PilotAttempt.State.NEEDS_INSPECTION);
        var state = projectState(task, latest, installation, inFlight, allWithdrawn);
        var reason = taskReason(task, state, latest);
        var working = state == PilotTaskView.TaskState.WORKING;
        var stage = working ? progress.liveStage() : PilotOutcomeService.stageOf(reason, null);
        return new TaskOutcome(latest, state, reason, stage, working ? progress.liveStageStartedAt() : null,
                allowedActions(task, history, latest, allWithdrawn));
    }

    private static String taskReason(PilotTask task, PilotTaskView.TaskState state, PilotAttempt latest) {
        if (state == PilotTaskView.TaskState.UNSUPPORTED) {
            return task.getRoutingReason();
        }
        if (state == PilotTaskView.TaskState.AWAITING_SELECTION || latest == null) {
            return null;
        }
        return latest.getResultReason();
    }

    private static PilotTaskView.AllowedActions allowedActions(PilotTask task, List<PilotAttempt> history,
                                                               PilotAttempt latest, boolean allWithdrawn) {
        var eligible = task.getEligibility() == PilotTask.Eligibility.ELIGIBLE;
        var selectable = eligible && task.isInScope() && (history.isEmpty() || allWithdrawn);
        var retriable = eligible && latest != null
                && (latest.getState() == PilotAttempt.State.STOPPED
                        || latest.getState() == PilotAttempt.State.WITHDRAWN && !allWithdrawn);
        return new PilotTaskView.AllowedActions(selectable, retriable);
    }

    private PilotTaskView toView(PilotTask task, Map<UUID, Integer> queue, TaskOutcome outcome,
                                 AttemptProgress progress) {
        var latest = outcome.latest();
        var prReady = outcome.state() == PilotTaskView.TaskState.PR_READY && latest != null;
        return new PilotTaskView(task.getId(), task.getDisplayKey(), jiraLink(task.getDisplayKey()), task.getTitle(),
                task.getProjectKey(), jiraStatus(task), outcome.state(),
                latest == null ? null : queue.get(latest.getId()),
                outcome.reason(), outcome.stage(), outcome.stageStartedAt(),
                PilotRoutingReason.eligibleRepositoryKey(task.getRoutingReason()),
                prReady ? latest.getPublicationIdentity() : null,
                prReady ? draftReasons(latest) : null,
                task.isInScope(), task.getEligibility().name(), task.getRoutingReason(), task.getVersion(),
                PilotAdmissionService.inputFingerprint(task), PilotAdmissionService.destinationFingerprint(task),
                task.getJiraCreatedAt(), task.getUpdatedAt(), progress.entries(), progress.usage(),
                outcome.allowedActions());
    }

    private static PilotTaskView.TaskState projectState(
            PilotTask task, PilotAttempt latest, PilotInstallation installation,
            boolean working, boolean allWithdrawn) {
        if (working) {
            return PilotTaskView.TaskState.WORKING;
        }
        if (latest == null) {
            return task.getEligibility() == PilotTask.Eligibility.INELIGIBLE
                    ? PilotTaskView.TaskState.UNSUPPORTED
                    : PilotTaskView.TaskState.AWAITING_SELECTION;
        }
        if (allWithdrawn) {
            return PilotTaskView.TaskState.WITHDRAWN;
        }
        return switch (latest.getState()) {
            case NEEDS_INSPECTION -> installation != null
                    && latest.getId().equals(installation.getActiveAttemptId())
                    ? PilotTaskView.TaskState.PROCESSING_PAUSED
                    : PilotTaskView.TaskState.UNCERTAIN;
            case STOPPED -> PilotTaskView.TaskState.STOPPED;
            case PR_READY -> PilotTaskView.TaskState.PR_READY;
            case WITHDRAWN -> PilotTaskView.TaskState.WITHDRAWN;
            default -> PilotTaskView.TaskState.WORKING;
        };
    }

    /** Why the attempt's pull request is a draft: null when it is ready for review, empty when unreadable. */
    private List<String> draftReasons(PilotAttempt attempt) {
        if (attempt.getDraftReasons() == null) {
            return null;
        }
        try {
            List<String> reasons = new ArrayList<>();
            json.readTree(attempt.getDraftReasons()).forEach(reason -> reasons.add(reason.asString("")));
            return List.copyOf(reasons);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** The task's status in Jira at the last scan or run by key, read from its snapshot. */
    private String jiraStatus(PilotTask task) {
        try {
            JsonNode snapshot = json.readTree(task.getIssueSnapshot());
            String status = snapshot.path("status").asString("");
            return status.isBlank() ? null : status;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The step a live execution is on: the latest STEP_STARTED audit event of that execution. */
    private static AuditEvent liveStageStart(List<AuditEvent> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            AuditEvent event = events.get(i);
            if (event.getEventType() == AuditEventType.STEP_STARTED) {
                return event;
            }
        }
        return null;
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

    private record AttemptRow(PilotTaskView.AttemptHistoryEntry entry, AttemptUsageSummary.Totals totals,
                              boolean usageMissingAfterPaidStart, boolean live, Instant stageStartedAt) {
    }

    /** The history entries, the task usage, and the stage of the last running attempt. */
    private record AttemptProgress(List<PilotTaskView.AttemptHistoryEntry> entries, PilotTaskUsage usage,
                                   String liveStage, Instant liveStageStartedAt) {
    }

    private record TaskOutcome(PilotAttempt latest, PilotTaskView.TaskState state, String reason, String stage,
                               Instant stageStartedAt, PilotTaskView.AllowedActions allowedActions) {
    }
}
