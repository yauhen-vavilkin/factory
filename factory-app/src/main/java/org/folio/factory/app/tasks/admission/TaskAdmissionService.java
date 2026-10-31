package org.folio.factory.app.tasks.admission;

import org.folio.factory.app.tasks.execution.TaskDispatcher;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.app.tasks.source.TaskDiscoveryService;
import org.folio.factory.app.tasks.source.TaskJiraInstance;
import org.folio.factory.connectors.jira.JiraSourceModels.DiscoveredIssue;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Admission into the durable task queue: tasks selected in My tasks, one task added by its Jira key, and an
 * explicit Retry of a terminal unsuccessful attempt. Before anything is reserved, the GitHub token's push
 * access to the task's delivery repository is checked. Each admission then runs in its own short transaction
 * that locks the task, revalidates what the developer saw ({@link TaskAdmissionChecks}) and reserves one live
 * attempt; a repeated request id resolves to the existing admission instead of creating anything. A retry
 * re-reads the task from Jira when it can, so it runs on what Jira says now, and keeps the earlier attempts'
 * history and costs. After admitting work the dispatcher is asked to start the queue head — best-effort for
 * a selection, so a refused dispatch only leaves the attempts queued for the scheduled poller.
 */
@Service
public class TaskAdmissionService {

    /**
     * Attempt request ids are the run id plus the task id, so the run id must
     * leave room for both within task_attempt.request_id (100 characters).
     */
    static final int MAX_REQUEST_ID_LENGTH = 63;

    private final TaskRepository tasks;
    private final TaskAttemptRepository attempts;
    private final TaskDispatcher dispatcher;
    private final TaskDiscoveryService discovery;
    private final TaskJiraInstance jiraInstance;
    private final TaskAdmissionChecks checks;
    private final TransactionTemplate transactions;

    public TaskAdmissionService(TaskRepository tasks, TaskAttemptRepository attempts,
                                TaskDispatcher dispatcher, TaskDiscoveryService discovery,
                                TaskJiraInstance jiraInstance, TaskAdmissionChecks checks,
                                PlatformTransactionManager transactionManager) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.dispatcher = dispatcher;
        this.discovery = discovery;
        this.jiraInstance = jiraInstance;
        this.checks = checks;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record AdmissionResult(List<UUID> admittedTaskIds, Map<UUID, String> rejectedReasons) {
        public AdmissionResult {
            admittedTaskIds = List.copyOf(admittedTaskIds);
            rejectedReasons = Map.copyOf(rejectedReasons);
        }
    }

    /** The task a run by key queued, or the reason nothing was queued. */
    public record KeyResult(String issueKey, UUID taskId, String problem) {
    }

    /**
     * @param state    the state of this click's attempt or, when refused, of the task's latest attempt
     * @param refused  true when this click queued nothing, whatever state the task is in
     */
    public record RetryResult(UUID attemptId, UUID executionId, TaskAttempt.State state, String explanation,
                              boolean refused) {
    }

    public AdmissionResult runSelected(TaskCommands.RunSelected run) {
        String requestId = requireRequestId(run.requestId());
        List<UUID> admitted = new ArrayList<>();
        Map<UUID, String> rejected = new LinkedHashMap<>();
        Map<String, String> pushProblems = new HashMap<>();
        for (TaskCommands.Selection selection : run.selections()) {
            String reason = admitSelected(requestId, selection, run.runSettings(), pushProblems);
            if (reason == null) {
                admitted.add(selection.taskId());
            } else {
                rejected.put(selection.taskId(), reason);
            }
        }
        if (!admitted.isEmpty()) {
            // Admitted work must not wait for the poller when a dispatch can start
            // now; a refusal keeps the attempts queued for the scheduled retry.
            dispatcher.dispatchNextQuietly();
        }
        return new AdmissionResult(admitted, rejected);
    }

    /** Null when the task is admitted, now or by an earlier request with the same id; the refusal otherwise. */
    private String admitSelected(String requestId, TaskCommands.Selection selection, String runSettings,
                                 Map<String, String> pushProblems) {
        String attemptRequestId = attemptRequestId(requestId, selection.taskId());
        if (attempts.findByRequestId(attemptRequestId).isPresent()) {
            return null;
        }
        String pushProblem = tasks.findById(selection.taskId())
                .map(task -> checks.pushProblem(task.getRoutingReason(), pushProblems))
                .orElse(null);
        if (pushProblem != null) {
            return pushProblem;
        }
        // A concurrent admission or scan may win the row. When it holds our request
        // id, resolve to that stored admission; otherwise this click is stale.
        return inTransaction(() -> admitOnce(attemptRequestId, selection, runSettings),
                conflict -> attempts.findByRequestId(attemptRequestId).isPresent() ? null
                        : "task changed at the same moment; review and select again");
    }

    /** Null when the attempt was reserved, otherwise the rejection reason. */
    private String admitOnce(String attemptRequestId, TaskCommands.Selection selection, String runSettings) {
        Task task = tasks.lockById(selection.taskId()).orElse(null);
        if (task == null) {
            return "task " + selection.taskId() + " no longer exists";
        }
        String refusal = TaskAdmissionChecks.selectionProblem(task, selection,
                !attempts.findLiveByTaskId(task.getId()).isEmpty(),
                attempts.findByTaskIdOrderByOrdinalAsc(task.getId()));
        if (refusal != null) {
            return refusal;
        }
        reserve(task, TaskAttempt.Kind.INITIAL, attemptRequestId, TaskAttempt.AdmittedVia.MANUAL, runSettings);
        return null;
    }

    /**
     * Queues one task by its Jira key or link, whatever its Jira status or
     * assignee, also after an earlier pull request. Nothing is written for a key
     * that cannot run; a task that is already queued or running is refused
     * before its row is touched.
     */
    public KeyResult runByKey(String requestId, String input, String runSettings) {
        String run = requireRequestId(requestId);
        String issueKey;
        try {
            issueKey = jiraInstance.issueKey(input);
        } catch (IllegalArgumentException e) {
            return new KeyResult(null, null, e.getMessage());
        }
        var fetched = checks.fetch(issueKey);
        var problem = fetched.problem() != null ? fetched.problem() : checks.routingProblem(fetched.issue());
        if (problem != null) {
            return new KeyResult(issueKey, null, problem);
        }
        var result = inTransaction(() -> admitKey(run, fetched.issue(), runSettings), conflict -> new KeyResult(
                issueKey, null, "Jira was being scanned at the same moment; add " + issueKey + " again"));
        if (result.problem() == null) {
            dispatcher.dispatchNextQuietly();
        }
        return result;
    }

    private KeyResult admitKey(String requestId, DiscoveredIssue issue, String runSettings) {
        Task existing = tasks.findByJiraInstanceAndIssueId(jiraInstance.current(), issue.id())
                .flatMap(task -> tasks.lockById(task.getId()))
                .orElse(null);
        if (existing != null) {
            if (attempts.findByRequestId(attemptRequestId(requestId, existing.getId())).isPresent()) {
                return new KeyResult(issue.key(), existing.getId(), null);
            }
            if (!attempts.findLiveByTaskId(existing.getId()).isEmpty()) {
                return new KeyResult(issue.key(), existing.getId(),
                        issue.key() + " is already queued or running");
            }
        }
        Task task = discovery.upsert(issue, false);
        TaskAttempt.Kind kind = attempts.findByTaskIdOrderByOrdinalAsc(task.getId()).isEmpty()
                ? TaskAttempt.Kind.INITIAL : TaskAttempt.Kind.RETRY;
        reserve(task, kind, attemptRequestId(requestId, task.getId()), TaskAttempt.AdmittedVia.BY_KEY, runSettings);
        return new KeyResult(issue.key(), task.getId(), null);
    }

    /**
     * An explicit fresh Retry. Eligibility keys off the task's attempt history; queued, running, held and
     * PR-ready work never restarts from here and nothing retries automatically. A retry does not depend on
     * whether the task is still assigned to the developer in Jira, and it is refused up front when the GitHub
     * token cannot push to the delivery repository.
     */
    public RetryResult retry(TaskCommands.Retry command) {
        String attemptRequestId = attemptRequestId(requireRequestId(command.requestId()), command.taskId());
        RetryResult stored = storedRetry(attemptRequestId);
        if (stored != null) {
            return stored;
        }
        Task current = tasks.findById(command.taskId()).orElse(null);
        String pushProblem = current == null ? null
                : checks.pushProblem(current.getRoutingReason(), new HashMap<>());
        if (pushProblem != null) {
            return rejection(command.taskId(), pushProblem);
        }
        DiscoveredIssue fresh = current == null ? null : checks.freshIssue(current);
        var reservation = inTransaction(() -> reserveRetry(attemptRequestId, command, fresh), conflict ->
                new Reservation(null, resolveConcurrentRetry(attemptRequestId, command.taskId(), conflict)));
        return reservation.outcome() != null ? reservation.outcome() : dispatch(reservation.attemptId());
    }

    /** The reserved retry attempt, or the answer that ends the click without one. */
    private record Reservation(UUID attemptId, RetryResult outcome) {
    }

    private Reservation reserveRetry(String attemptRequestId, TaskCommands.Retry command, DiscoveredIssue fresh) {
        Task task = tasks.lockById(command.taskId()).orElse(null);
        if (task == null) {
            return new Reservation(null, rejection(command.taskId(), "task " + command.taskId() + " no longer exists"));
        }
        String problem = TaskAdmissionChecks.retryProblem(task, command,
                attempts.findByTaskIdOrderByOrdinalAsc(task.getId()));
        if (problem == null && fresh != null) {
            String checkedDestination = TaskAdmissionChecks.destinationFingerprint(task);
            task = discovery.upsert(fresh, false);
            problem = TaskAdmissionChecks.freshTaskProblem(task, checkedDestination);
        }
        if (problem != null) {
            return new Reservation(null, rejection(command.taskId(), problem));
        }
        var attempt = reserve(task, TaskAttempt.Kind.RETRY, attemptRequestId, TaskAttempt.AdmittedVia.MANUAL,
                command.runSettings());
        return new Reservation(attempt.getId(), null);
    }

    private RetryResult dispatch(UUID attemptId) {
        if (!dispatcher.executionEnabled()) {
            // Nothing could reconcile a run launched now, so the retry waits like any queued task.
            return queuedRetry(attemptId, "the retry attempt is queued; task execution is off");
        }
        try {
            dispatcher.dispatchNext();
        } catch (RuntimeException e) {
            return queuedRetry(attemptId, "the retry attempt is queued; dispatch could not start it: " + cause(e));
        }
        TaskAttempt attempt = attempts.findById(attemptId).orElseThrow();
        if (attempt.getExecutionId() != null) {
            return new RetryResult(attemptId, attempt.getExecutionId(), attempt.getState(),
                    "the retry attempt launched a new execution", false);
        }
        if (attempt.getState() == TaskAttempt.State.RESERVED) {
            return queuedRetry(attemptId, "the retry attempt is queued behind the active attempt");
        }
        return new RetryResult(attemptId, null, attempt.getState(), attempt.getResultReason(), false);
    }

    private static RetryResult queuedRetry(UUID attemptId, String explanation) {
        return new RetryResult(attemptId, null, TaskAttempt.State.RESERVED, explanation, false);
    }

    private static String cause(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }

    private RetryResult resolveConcurrentRetry(String attemptRequestId, UUID taskId, RuntimeException conflict) {
        RetryResult stored = storedRetry(attemptRequestId);
        if (stored != null) {
            return stored;
        }
        // A unique constraint means a concurrent retry took the live slot; an optimistic lock
        // failure means a scan saved the task concurrently, so this retry is stale, not lost.
        return rejection(taskId, conflict instanceof DataIntegrityViolationException
                ? "task already has a live attempt"
                : "task changed at the same moment; review the task and retry again");
    }

    private RetryResult storedRetry(String attemptRequestId) {
        return attempts.findByRequestId(attemptRequestId)
                .map(attempt -> new RetryResult(attempt.getId(), attempt.getExecutionId(), attempt.getState(),
                        duplicateExplanation(attempt), false))
                .orElse(null);
    }

    private static String duplicateExplanation(TaskAttempt attempt) {
        String reason = attempt.getResultReason();
        return "a retry request for this task was already recorded; the stored attempt is "
                + attempt.getState() + (reason == null || reason.isBlank() ? "" : ": " + reason);
    }

    private RetryResult rejection(UUID taskId, String reason) {
        List<TaskAttempt> history = attempts.findByTaskIdOrderByOrdinalAsc(taskId);
        if (history.isEmpty()) {
            return new RetryResult(null, null, null, reason, true);
        }
        TaskAttempt latest = history.getLast();
        return new RetryResult(latest.getId(), latest.getExecutionId(), latest.getState(), reason, true);
    }

    private TaskAttempt reserve(Task task, TaskAttempt.Kind kind, String attemptRequestId,
                                TaskAttempt.AdmittedVia admittedVia, String runSettings) {
        TaskAttempt attempt = new TaskAttempt(task.getId(), nextOrdinal(task.getId()), kind, attemptRequestId,
                TaskAttempt.State.RESERVED);
        attempt.setInputFingerprint(TaskAdmissionChecks.inputFingerprint(task));
        attempt.setDestinationFingerprint(TaskAdmissionChecks.destinationFingerprint(task));
        attempt.setApprovedSnapshot(task.getIssueSnapshot());
        attempt.setAdmittedVia(admittedVia);
        attempt.setRunSettings(runSettings);
        return attempts.save(attempt);
    }

    /** Runs one reservation in its own transaction; a concurrent writer that won the row decides the answer. */
    private <T> T inTransaction(Supplier<T> reservation, Function<RuntimeException, T> onConflict) {
        try {
            return transactions.execute(status -> reservation.get());
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException e) {
            return onConflict.apply(e);
        }
    }

    private int nextOrdinal(UUID taskId) {
        return attempts.findByTaskIdOrderByOrdinalAsc(taskId).stream()
                .mapToInt(TaskAttempt::getOrdinal)
                .max()
                .orElse(0) + 1;
    }

    static String attemptRequestId(String requestId, UUID taskId) {
        return requestId + "#" + taskId;
    }

    static String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("A request id is required to admit selected tasks");
        }
        String trimmed = requestId.strip();
        if (trimmed.length() > MAX_REQUEST_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "Request id must not exceed " + MAX_REQUEST_ID_LENGTH + " characters");
        }
        return trimmed;
    }
}
