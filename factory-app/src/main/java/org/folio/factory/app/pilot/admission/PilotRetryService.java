package org.folio.factory.app.pilot.admission;

import org.folio.factory.app.pilot.execution.PilotDispatcher;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * automatically. The new RETRY attempt
 * freezes the current bounded issue input and the current source authority as
 * its approval, reuses the serial dispatch path for a new execution on a new
 * owned branch, and keeps the previous attempts' history and costs. The
 * request id and the task's live-slot lock make duplicate and stale clicks
 * resolve without creating a second attempt.
 */
@Service
public class PilotRetryService {

    private final PilotTaskRepository tasks;
    private final PilotAttemptRepository attempts;
    private final PilotInstallationRepository installations;
    private final PilotDispatcher dispatcher;
    private final TransactionTemplate transactions;

    public PilotRetryService(PilotTaskRepository tasks, PilotAttemptRepository attempts,
                             PilotInstallationRepository installations, PilotDispatcher dispatcher,
                             PlatformTransactionManager transactionManager) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.installations = installations;
        this.dispatcher = dispatcher;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record RetryResult(UUID attemptId, UUID executionId, String state, String explanation) {
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
        try {
            Reservation reservation =
                    transactions.execute(status -> reserve(requestId, attemptRequestId, command));
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
            // The singleton installation row was saved concurrently (mode or
            // authority change); this retry is stale, not lost.
            return resolveConcurrent(attemptRequestId, command.taskId(),
                    "pilot installation changed concurrently; review the task and retry again");
        }
    }

    private Reservation reserve(String requestId, String attemptRequestId, PilotCommands.Retry command) {
        PilotTask task = tasks.lockById(command.taskId()).orElse(null);
        if (task == null) {
            return new Reservation(null, "task " + command.taskId() + " no longer exists");
        }
        String problem = retryProblem(task, command);
        if (problem != null) {
            return new Reservation(null, problem);
        }
        PilotInstallation installation = installations.findById(1).orElseGet(PilotInstallation::initial);
        if (!installation.isSyncComplete() || installation.getSyncProblem() != null) {
            return new Reservation(null, "pilot source sync is incomplete"
                    + (installation.getSyncProblem() == null ? "" : ": " + installation.getSyncProblem())
                    + "; refresh discovery and retry again");
        }
        String authority = installation.getObservedAuthorityFingerprint();
        PilotAttempt attempt = new PilotAttempt(task.getId(),
                PilotAdmissionService.nextOrdinal(attempts, task.getId()),
                PilotAttempt.Kind.RETRY, attemptRequestId, PilotAttempt.State.RESERVED);
        attempt.setInputFingerprint(PilotAdmissionService.inputFingerprint(task));
        attempt.setScopeFingerprint(authority);
        attempt.setDestinationFingerprint(PilotAdmissionService.destinationFingerprint(task));
        attempt.setApprovedSnapshot(task.getIssueSnapshot());
        attempt.setAdmittedVia(PilotAttempt.AdmittedVia.MANUAL);
        attempts.save(attempt);
        // An explicit retry approval accepts the observed source authority.
        installation.setAcceptedAuthorityFingerprint(authority);
        installations.save(installation);
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
        if (!task.isInScope()) {
            return "task is outside the current pilot scope; refresh discovery and retry again";
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
        try {
            dispatcher.dispatchNext();
        } catch (RuntimeException e) {
            return new RetryResult(attemptId, null, PilotAttempt.State.RESERVED.name(),
                    "the retry attempt is queued; dispatch could not start it: " + e.getMessage());
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
            return new RetryResult(null, null, null, reason);
        }
        PilotAttempt latest = history.getLast();
        return new RetryResult(latest.getId(), latest.getExecutionId(), latest.getState().name(), reason);
    }
}
