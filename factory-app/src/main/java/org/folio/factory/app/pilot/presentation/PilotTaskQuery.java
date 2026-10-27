package org.folio.factory.app.pilot.presentation;

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
public class PilotTaskQuery {

    private final PilotTaskRepository tasks;
    private final PilotAttemptRepository attempts;
    private final PilotInstallationRepository installations;
    private final PipelineExecutionRepository executions;
    private final AuditLog auditLog;
    private final JiraProperties jira;
    private final PilotJiraInstance jiraInstance;
    private final JsonMapper json = JsonMapper.builder().build();

    public PilotTaskQuery(PilotTaskRepository tasks, PilotAttemptRepository attempts,
                          PilotInstallationRepository installations, PipelineExecutionRepository executions,
                          AuditLog auditLog, JiraProperties jira, PilotJiraInstance jiraInstance) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.installations = installations;
        this.executions = executions;
        this.auditLog = auditLog;
        this.jira = jira;
        this.jiraInstance = jiraInstance;
    }

    /** Available: in My tasks and never started; Triggered: started at least once, newest first. */
    public record MyTasks(List<PilotTaskView> available, Page<PilotTaskView> triggered) {
    }

    @Transactional(readOnly = true)
    public MyTasks myTasks(Pageable triggeredPage) {
        Map<UUID, Integer> queue = queuePositions();
        List<PilotTaskView> available = tasks.findAvailable(jiraInstance.current(), PilotAttempt.State.WITHDRAWN).stream()
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
        List<PilotAttempt> history = attempts.findByTaskIdOrderByOrdinalAsc(task.getId());
        Map<UUID, PipelineExecution> executionsById = executions.findAllById(
                        history.stream().map(PilotAttempt::getExecutionId).filter(Objects::nonNull).toList())
                .stream()
                .collect(Collectors.toMap(PipelineExecution::getId, Function.identity()));
        PilotInstallation installation = installations.findById(1).orElse(null);
        Instant now = Instant.now();

        List<PilotTaskView.AttemptHistoryEntry> entries = new ArrayList<>(history.size());
        List<AttemptUsageSummary.Totals> contributions = new ArrayList<>(history.size());
        boolean usageMissingAfterPaidStart = false;
        String liveStage = null;
        Instant liveStageStartedAt = null;
        for (PilotAttempt attempt : history) {
            PipelineExecution execution = attempt.getExecutionId() == null
                    ? null : executionsById.get(attempt.getExecutionId());
            List<AuditEvent> events = execution == null
                    ? List.of() : auditLog.forExecution(execution.getId());
            boolean live = execution != null && execution.getStatus() == ExecutionStatus.RUNNING;
            AttemptUsageSummary.Totals totals = AttemptUsageSummary.totals(events, live);
            contributions.add(totals);
            boolean paidStartWithoutReportedUsage = attempt.getPaidStartAt() != null
                    && totals.tokens() == 0 && !totals.tokensReported();
            usageMissingAfterPaidStart |= paidStartWithoutReportedUsage;
            AuditEvent stageStart = live ? liveStageStart(events) : null;
            String stage = live ? (stageStart == null ? null : stageStart.getStepId())
                    : PilotOutcomeService.stageOf(attempt.getResultReason(), null);
            if (live) {
                liveStage = stage;
                liveStageStartedAt = stageStart == null ? null : stageStart.getOccurredAt();
            }
            entries.add(new PilotTaskView.AttemptHistoryEntry(
                    attempt.getId(),
                    attempt.getOrdinal(),
                    attempt.getKind().name(),
                    attempt.getAdmittedVia().name(),
                    attempt.getState().name(),
                    stage,
                    attempt.getResultReason(),
                    attempt.getState() == PilotAttempt.State.PR_READY
                            ? attempt.getPublicationIdentity() : null,
                    attempt.getExecutionId(),
                    execution == null ? null : execution.getStatus().name(),
                    attempt.getCreatedAt(),
                    attempt.getStartedAt(),
                    attempt.getDeadlineAt(),
                    attempt.getPaidStartAt() != null,
                    AttemptUsageSummary.activeSeconds(events, now),
                    PilotTaskUsage.aggregate(List.of(totals), paidStartWithoutReportedUsage),
                    attempt.getRunSettings()));
        }

        // A withdrawal after real work (left by the removed automatic mode) does not
        // stand for the task: it shows the last attempt that actually ran.
        PilotAttempt latest = history.stream()
                .filter(attempt -> attempt.getState() != PilotAttempt.State.WITHDRAWN)
                .reduce((first, second) -> second)
                .orElse(history.isEmpty() ? null : history.getLast());
        boolean allWithdrawn = !history.isEmpty() && history.stream()
                .allMatch(attempt -> attempt.getState() == PilotAttempt.State.WITHDRAWN);
        boolean working = history.stream().anyMatch(attempt -> attempt.getState().isLive()
                && attempt.getState() != PilotAttempt.State.NEEDS_INSPECTION);
        PilotTaskView.TaskState state = projectState(task, latest, installation, working, allWithdrawn);
        String reason = state == PilotTaskView.TaskState.UNSUPPORTED ? task.getRoutingReason()
                : state == PilotTaskView.TaskState.AWAITING_SELECTION ? null
                : latest == null ? null : latest.getResultReason();
        String stage = state == PilotTaskView.TaskState.WORKING ? liveStage
                : PilotOutcomeService.stageOf(reason, null);
        boolean eligible = task.getEligibility() == PilotTask.Eligibility.ELIGIBLE;
        boolean selectable = eligible && task.isInScope() && (history.isEmpty() || allWithdrawn);
        boolean retriable = eligible && latest != null
                && (latest.getState() == PilotAttempt.State.STOPPED
                        || latest.getState() == PilotAttempt.State.WITHDRAWN && !allWithdrawn);
        return new PilotTaskView(
                task.getId(),
                task.getDisplayKey(),
                jiraLink(task.getDisplayKey()),
                task.getTitle(),
                task.getProjectKey(),
                jiraStatus(task),
                state,
                latest == null ? null : queue.get(latest.getId()),
                reason,
                stage,
                state == PilotTaskView.TaskState.WORKING ? liveStageStartedAt : null,
                PilotRoutingReason.eligibleRepositoryKey(task.getRoutingReason()),
                state == PilotTaskView.TaskState.PR_READY && latest != null
                        ? latest.getPublicationIdentity() : null,
                task.isInScope(),
                task.getEligibility().name(),
                task.getRoutingReason(),
                task.getVersion(),
                PilotAdmissionService.inputFingerprint(task),
                PilotAdmissionService.destinationFingerprint(task),
                task.getJiraCreatedAt(),
                task.getUpdatedAt(),
                List.copyOf(entries),
                PilotTaskUsage.aggregate(contributions, usageMissingAfterPaidStart),
                new PilotTaskView.AllowedActions(selectable, retriable));
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
}
