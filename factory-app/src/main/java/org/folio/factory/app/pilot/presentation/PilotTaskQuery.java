package org.folio.factory.app.pilot.presentation;

import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotRoutingReason;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
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
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only projection of logical pilot tasks for the task screens. Each task
 * is projected from its persisted attempt history, the bound executions' audit
 * accounting and the installation record: one distinct current state per
 * outcome, complete attempt history even after the task leaves the Jira
 * filter, and task tokenomics aggregated from typed per-execution totals.
 * Nothing here mutates pilot or engine state.
 */
@Service
public class PilotTaskQuery {

    private final PilotTaskRepository tasks;
    private final PilotAttemptRepository attempts;
    private final PilotInstallationRepository installations;
    private final PipelineExecutionRepository executions;
    private final AuditLog auditLog;
    private final JiraProperties jira;

    public PilotTaskQuery(PilotTaskRepository tasks, PilotAttemptRepository attempts,
                          PilotInstallationRepository installations, PipelineExecutionRepository executions,
                          AuditLog auditLog, JiraProperties jira) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.installations = installations;
        this.executions = executions;
        this.auditLog = auditLog;
        this.jira = jira;
    }

    @Transactional(readOnly = true)
    public Page<PilotTaskView> list(Pageable page) {
        return tasks.findAll(sorted(page)).map(this::project);
    }

    @Transactional(readOnly = true)
    public PilotTaskView detail(UUID taskId) {
        return project(tasks.findById(taskId)
                .orElseThrow(() -> new NoSuchElementException("No pilot task " + taskId)));
    }

    /** The queue's stable order unless the caller asked for a specific sort. */
    private static Pageable sorted(Pageable page) {
        return page.getSort().isSorted() ? page
                : PageRequest.of(page.getPageNumber(), page.getPageSize(),
                        Sort.by(Sort.Direction.ASC, "jiraCreatedAt", "displayKey"));
    }

    private PilotTaskView project(PilotTask task) {
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
            entries.add(new PilotTaskView.AttemptHistoryEntry(
                    attempt.getId(),
                    attempt.getOrdinal(),
                    attempt.getKind().name(),
                    attempt.getAdmittedVia().name(),
                    attempt.getState().name(),
                    stageOf(attempt.getResultReason()),
                    attempt.getResultReason(),
                    attempt.getState() == PilotAttempt.State.PR_READY
                            ? attempt.getPublicationIdentity() : null,
                    attempt.getExecutionId(),
                    execution == null ? null : execution.getStatus().name(),
                    attempt.getStartedAt(),
                    attempt.getDeadlineAt(),
                    attempt.getPaidStartAt() != null,
                    AttemptUsageSummary.activeSeconds(events, now),
                    PilotTaskUsage.aggregate(List.of(totals), paidStartWithoutReportedUsage)));
        }

        PilotAttempt latest = history.isEmpty() ? null : history.getLast();
        boolean allWithdrawn = !history.isEmpty() && history.stream()
                .allMatch(attempt -> attempt.getState() == PilotAttempt.State.WITHDRAWN);
        boolean working = history.stream().anyMatch(attempt -> attempt.getState().isLive()
                && attempt.getState() != PilotAttempt.State.NEEDS_INSPECTION);
        PilotTaskView.TaskState state = projectState(task, latest, installation, working, allWithdrawn);
        String reason = state == PilotTaskView.TaskState.UNSUPPORTED ? task.getRoutingReason()
                : state == PilotTaskView.TaskState.AWAITING_SELECTION ? null
                : latest == null ? null : latest.getResultReason();
        boolean eligible = task.getEligibility() == PilotTask.Eligibility.ELIGIBLE;
        boolean selectable = eligible && task.isInScope() && (history.isEmpty() || allWithdrawn);
        boolean retriable = eligible && task.isInScope() && latest != null
                && (latest.getState() == PilotAttempt.State.STOPPED
                        || latest.getState() == PilotAttempt.State.WITHDRAWN && !allWithdrawn);
        return new PilotTaskView(
                task.getId(),
                task.getDisplayKey(),
                jiraLink(task.getDisplayKey()),
                task.getTitle(),
                task.getProjectKey(),
                state,
                reason,
                stageOf(reason),
                PilotRoutingReason.eligibleRepositoryKey(task.getRoutingReason()),
                state == PilotTaskView.TaskState.PR_READY && latest != null
                        ? latest.getPublicationIdentity() : null,
                task.isInScope(),
                task.getEligibility().name(),
                task.getRoutingReason(),
                task.getJiraCreatedAt(),
                task.getUpdatedAt(),
                List.copyOf(entries),
                PilotTaskUsage.aggregate(contributions, usageMissingAfterPaidStart),
                new PilotTaskView.AllowedActions(selectable, retriable,
                        automaticAdmissionAvailable(installation)));
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

    private static boolean automaticAdmissionAvailable(PilotInstallation installation) {
        return installation != null
                && installation.getMode() == PilotInstallation.Mode.REVIEW_BEFORE_RUN
                && installation.isSyncComplete()
                && installation.getSyncProblem() == null
                && installation.getObservedAuthorityFingerprint() != null;
    }

    /** The leading {@code stage: reason} label the outcome service composes, when present. */
    private static String stageOf(String reason) {
        if (reason == null) {
            return null;
        }
        int boundary = reason.indexOf(": ");
        return boundary > 0 ? reason.substring(0, boundary) : null;
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
