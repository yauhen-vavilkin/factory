package org.folio.factory.app.pilot.admission;

import org.folio.factory.app.pilot.execution.PilotDispatcher;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.app.pilot.source.PilotDiscoveryService;
import org.folio.factory.app.pilot.source.PilotJiraInstance;
import org.folio.factory.connectors.jira.JiraSourceModels.DiscoveredIssue;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * An explicit fresh Retry of a terminal unsuccessful attempt. Eligibility keys
 * off the task's attempt history — a STOPPED latest attempt can retry
 * (workload inactivity was already proven by the outcome service before that
 * state was written), and a WITHDRAWN latest attempt can retry once the history
 * holds a withdrawn retry or any earlier non-withdrawn attempt; only a history
 * of nothing but withdrawn admissions sends the task back to selection. Queued,
 * running, held and PR-ready work never restarts from here and nothing retries
 * automatically. A retry does not depend on whether the task is still assigned
 * to the developer in Jira, and it is refused up front when the GitHub token
 * cannot push to the delivery repository. The click re-reads the task from Jira
 * when it can, so a task added by key or not scanned since an answer was added
 * retries on what Jira says now. The new RETRY attempt freezes that bounded
 * issue input as its approval, reuses the serial dispatch path
 * for a new execution on a new owned branch, and keeps the previous attempts'
 * history and costs. The request id and the task's live-slot lock make
 * duplicate and stale clicks resolve without creating a second attempt.
 */
@Service
public class PilotRetryService {

    private final PilotTaskRepository tasks;
    private final PilotAttemptRepository attempts;
    private final PilotDispatcher dispatcher;
    private final PilotAdmissionService admission;
    private final PilotDiscoveryService discovery;
    private final PilotJiraInstance jiraInstance;
    private final TransactionTemplate transactions;

    public PilotRetryService(PilotTaskRepository tasks, PilotAttemptRepository attempts,
                             PilotDispatcher dispatcher, PilotAdmissionService admission,
                             PilotDiscoveryService discovery, PilotJiraInstance jiraInstance,
                             PlatformTransactionManager transactionManager) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.dispatcher = dispatcher;
        this.admission = admission;
        this.discovery = discovery;
        this.jiraInstance = jiraInstance;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * @param state    the state of this click's attempt or, when refused, of the task's latest attempt
     * @param refused  true when this click queued nothing, whatever state the task is in
     */
    public record RetryResult(UUID attemptId, UUID executionId, String state, String explanation,
                              boolean refused) {
        public RetryResult(UUID attemptId, UUID executionId, String state, String explanation) {
            this(attemptId, executionId, state, explanation, false);
        }
    }

    private record Reservation(UUID attemptId, String rejectionReason) {
    }

    public RetryResult retry(PilotCommands.Retry command) {
        String requestId = PilotAdmissionService.requireRequestId(command.requestId());
        String attemptRequestId = PilotAdmissionService.attemptRequestId(requestId, command.taskId());
        RetryResult stored = storedRetry(attemptRequestId);
        if (stored != null) {
            return stored;
        }
        PilotTask current = tasks.findById(command.taskId()).orElse(null);
        String pushProblem = current == null ? null : admission.pushProblem(current.getRoutingReason(), new HashMap<>());
        if (pushProblem != null) {
            return rejection(command.taskId(), pushProblem);
        }
        DiscoveredIssue fresh = current == null ? null : freshIssue(current);
        try {
            Reservation reservation =
                    transactions.execute(status -> reserve(requestId, attemptRequestId, command, fresh));
            if (reservation.rejectionReason() != null) {
                return rejection(command.taskId(), reservation.rejectionReason());
            }
            return dispatch(reservation.attemptId());
        } catch (DataIntegrityViolationException e) {
            // A concurrent retry won a unique constraint. When it holds our
            // request id, resolve to that stored attempt; otherwise the task's
            // live slot was taken concurrently.
            return resolveConcurrent(attemptRequestId, command.taskId(), "task already has a live attempt");
        } catch (OptimisticLockingFailureException e) {
            // A scan saved the task concurrently; this retry is stale, not lost.
            return resolveConcurrent(attemptRequestId, command.taskId(),
                    "task changed at the same moment; review the task and retry again");
        }
    }

    /** The task as Jira shows it now, or null when Jira cannot be read and the stored snapshot is used. */
    private DiscoveredIssue freshIssue(PilotTask task) {
        if (!task.getJiraInstance().equals(jiraInstance.current())) {
            return null;
        }
        try {
            DiscoveredIssue issue = discovery.fetch(jiraInstance.issueKey(task.getDisplayKey()));
            return issue != null && issue.id().equals(task.getIssueId()) ? issue : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Reservation reserve(String requestId, String attemptRequestId, PilotCommands.Retry command,
                                DiscoveredIssue fresh) {
        PilotTask task = tasks.lockById(command.taskId()).orElse(null);
        if (task == null) {
            return new Reservation(null, "task " + command.taskId() + " no longer exists");
        }
        String problem = retryProblem(task, command);
        if (problem != null) {
            return new Reservation(null, problem);
        }
        if (fresh != null) {
            String checkedDestination = PilotAdmissionService.destinationFingerprint(task);
            task = discovery.upsert(fresh, false);
            if (task.getEligibility() != PilotTask.Eligibility.ELIGIBLE) {
                return new Reservation(null, "task is not eligible: " + task.getRoutingReason());
            }
            if (!checkedDestination.equals(PilotAdmissionService.destinationFingerprint(task))) {
                return new Reservation(null, "the task now routes to another repository in Jira; review it and retry");
            }
        }
        PilotAttempt attempt = new PilotAttempt(task.getId(),
                PilotAdmissionService.nextOrdinal(attempts, task.getId()),
                PilotAttempt.Kind.RETRY, attemptRequestId, PilotAttempt.State.RESERVED);
        attempt.setInputFingerprint(PilotAdmissionService.inputFingerprint(task));
        attempt.setDestinationFingerprint(PilotAdmissionService.destinationFingerprint(task));
        attempt.setApprovedSnapshot(task.getIssueSnapshot());
        attempt.setAdmittedVia(PilotAttempt.AdmittedVia.MANUAL);
        attempt.setRunSettings(command.runSettings());
        attempts.save(attempt);
        return new Reservation(attempt.getId(), null);
    }

    private String retryProblem(PilotTask task, PilotCommands.Retry command) {
        if (task.getVersion() != command.expectedVersion()
                || !PilotAdmissionService.matches(command.inputFingerprint(), PilotAdmissionService.inputFingerprint(task))
                || !PilotAdmissionService.matches(command.destinationFingerprint(),
                        PilotAdmissionService.destinationFingerprint(task))) {
            return "task changed since the retry was requested; review the current result and retry again";
        }
        if (task.getEligibility() != PilotTask.Eligibility.ELIGIBLE) {
            return "task is not eligible: " + task.getRoutingReason();
        }
        List<PilotAttempt> history = attempts.findByTaskIdOrderByOrdinalAsc(task.getId());
        if (history.isEmpty()) {
            return "the task has never been attempted; select it for admission instead";
        }
        PilotAttempt.State latest = history.getLast().getState();
        if (latest == PilotAttempt.State.RESERVED || latest == PilotAttempt.State.QUEUED
                || latest == PilotAttempt.State.RUNNING) {
            return "the task already has a live attempt";
        }
        if (latest == PilotAttempt.State.NEEDS_INSPECTION) {
            return "the latest attempt needs inspection; retry stays disabled until the outcome is resolved";
        }
        if (latest == PilotAttempt.State.PR_READY) {
            return "the latest attempt delivered a pull request; retry does not reopen delivered work";
        }
        if (latest == PilotAttempt.State.WITHDRAWN && history.stream()
                .allMatch(attempt -> attempt.getState() == PilotAttempt.State.WITHDRAWN)) {
            return "the latest admission was withdrawn before start; select the task again";
        }
        // A withdrawn retry, or a withdrawal after earlier real work, is not the
        // terminal word: a fresh explicit Retry stays possible.
        return null;
    }

    private RetryResult dispatch(UUID attemptId) {
        if (!dispatcher.executionEnabled()) {
            // Nothing could reconcile a run launched now, so the retry waits like any queued task.
            return new RetryResult(attemptId, null, PilotAttempt.State.RESERVED.name(),
                    "the retry attempt is queued; pilot execution is off");
        }
        try {
            dispatcher.dispatchNext();
        } catch (RuntimeException e) {
            return new RetryResult(attemptId, null, PilotAttempt.State.RESERVED.name(),
                    "the retry attempt is queued; dispatch could not start it: " + cause(e));
        }
        PilotAttempt attempt = attempts.findById(attemptId).orElseThrow();
        if (attempt.getExecutionId() != null) {
            return new RetryResult(attemptId, attempt.getExecutionId(), attempt.getState().name(),
                    "the retry attempt launched a new execution");
        }
        if (attempt.getState() == PilotAttempt.State.RESERVED) {
            return new RetryResult(attemptId, null, attempt.getState().name(),
                    "the retry attempt is queued behind the active attempt");
        }
        return new RetryResult(attemptId, null, attempt.getState().name(), attempt.getResultReason());
    }

    private RetryResult resolveConcurrent(String attemptRequestId, UUID taskId, String conflict) {
        RetryResult stored = storedRetry(attemptRequestId);
        if (stored != null) {
            return stored;
        }
        return rejection(taskId, conflict);
    }

    private RetryResult storedRetry(String attemptRequestId) {
        return attempts.findByRequestId(attemptRequestId)
                .map(attempt -> new RetryResult(attempt.getId(), attempt.getExecutionId(),
                        attempt.getState().name(), duplicateExplanation(attempt)))
                .orElse(null);
    }

    private String duplicateExplanation(PilotAttempt attempt) {
        String reason = attempt.getResultReason();
        return "a retry request for this task was already recorded; the stored attempt is "
                + attempt.getState() + (reason == null || reason.isBlank() ? "" : ": " + reason);
    }

    private RetryResult rejection(UUID taskId, String reason) {
        List<PilotAttempt> history = attempts.findByTaskIdOrderByOrdinalAsc(taskId);
        if (history.isEmpty()) {
            return new RetryResult(null, null, null, reason, true);
        }
        PilotAttempt latest = history.getLast();
        return new RetryResult(latest.getId(), latest.getExecutionId(), latest.getState().name(), reason, true);
    }

    private static String cause(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }
}
