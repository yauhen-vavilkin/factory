package org.folio.factory.app.pilot.admission;

import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.pilot.Digests;
import org.folio.factory.app.pilot.execution.PilotDispatcher;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotRoutingReason;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admission into the durable pilot queue: tasks selected in My tasks, or one task
 * added by its Jira key. Before anything is reserved, the GitHub token's push
 * access to the task's delivery repository is checked. Each admission then runs
 * in its own short transaction that locks the task, revalidates what the
 * developer saw and reserves one live attempt; a repeated request id resolves to
 * the existing admission instead of creating anything. After admitting work it
 * asks the dispatcher to start the queue head — best-effort, so a refused
 * dispatch only leaves the attempts queued for the scheduled poller.
 */
@Service
public class PilotAdmissionService {

    /**
     * Attempt request ids are the run id plus the task id, so the run id must
     * leave room for both within pilot_attempt.request_id (100 characters).
     */
    static final int MAX_REQUEST_ID_LENGTH = 63;

    private final PilotTaskRepository tasks;
    private final PilotAttemptRepository attempts;
    private final PilotDispatcher dispatcher;
    private final PilotDiscoveryService discovery;
    private final PilotJiraInstance jiraInstance;
    private final DeveloperPreflight preflight;
    private final TransactionTemplate transactions;

    public PilotAdmissionService(PilotTaskRepository tasks, PilotAttemptRepository attempts,
                                 PilotDispatcher dispatcher, PilotDiscoveryService discovery,
                                 PilotJiraInstance jiraInstance, DeveloperPreflight preflight,
                                 PlatformTransactionManager transactionManager) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.dispatcher = dispatcher;
        this.discovery = discovery;
        this.jiraInstance = jiraInstance;
        this.preflight = preflight;
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

    public AdmissionResult runSelected(PilotCommands.RunSelected run) {
        String requestId = requireRequestId(run.requestId());
        List<UUID> admitted = new ArrayList<>();
        Map<UUID, String> rejected = new LinkedHashMap<>();
        Map<String, String> pushProblems = new HashMap<>();
        for (PilotCommands.Selection selection : run.selections()) {
            if (attempts.findByRequestId(attemptRequestId(requestId, selection.taskId())).isPresent()) {
                admitted.add(selection.taskId());
                continue;
            }
            String reason = tasks.findById(selection.taskId())
                    .map(task -> pushProblem(task.getRoutingReason(), pushProblems))
                    .orElse(null);
            if (reason == null) {
                reason = admitSelection(requestId, selection, run.runSettings());
            }
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

    /**
     * Queues one task by its Jira key or link, whatever its Jira status or
     * assignee, also after an earlier pull request. Nothing is written for a key
     * that cannot run; a task that is already queued or running is refused
     * before its row is touched.
     */
    public KeyResult runByKey(String requestId, String input) {
        return runByKey(requestId, input, null);
    }

    public KeyResult runByKey(String requestId, String input, String runSettings) {
        String run = requireRequestId(requestId);
        String issueKey;
        try {
            issueKey = jiraInstance.issueKey(input);
        } catch (IllegalArgumentException e) {
            return new KeyResult(null, null, e.getMessage());
        }
        DiscoveredIssue issue;
        try {
            issue = discovery.fetch(issueKey);
        } catch (HttpClientErrorException e) {
            issue = null;
        } catch (RestClientResponseException e) {
            // The response body may be a whole HTML error page; the status says enough.
            return new KeyResult(issueKey, null, "Jira could not be read: HTTP " + e.getStatusCode().value());
        } catch (RuntimeException e) {
            return new KeyResult(issueKey, null, "Jira could not be read: " + e.getMessage());
        }
        if (issue == null) {
            return new KeyResult(issueKey, null, "Jira has no task " + issueKey + " that Factory can read");
        }
        PilotDiscoveryService.Routing routing = discovery.routing(issue);
        if (routing.eligibility() != PilotTask.Eligibility.ELIGIBLE) {
            return new KeyResult(issueKey, null, routing.reason());
        }
        String pushProblem = pushProblem(routing.reason(), new HashMap<>());
        if (pushProblem != null) {
            return new KeyResult(issueKey, null, pushProblem);
        }
        DiscoveredIssue fetched = issue;
        KeyResult result;
        try {
            result = transactions.execute(status -> admitKey(run, fetched, runSettings));
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException e) {
            return new KeyResult(issueKey, null,
                    "Jira was being scanned at the same moment; add " + issueKey + " again");
        }
        if (result.problem() == null) {
            dispatcher.dispatchNextQuietly();
        }
        return result;
    }

    private KeyResult admitKey(String requestId, DiscoveredIssue issue, String runSettings) {
        PilotTask existing = tasks.findByJiraInstanceAndIssueId(jiraInstance.current(), issue.id())
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
        PilotTask task = discovery.upsert(issue, false);
        PilotAttempt.Kind kind = attempts.findByTaskIdOrderByOrdinalAsc(task.getId()).isEmpty()
                ? PilotAttempt.Kind.INITIAL : PilotAttempt.Kind.RETRY;
        reserve(task, kind, attemptRequestId(requestId, task.getId()), PilotAttempt.AdmittedVia.BY_KEY,
                inputFingerprint(task), destinationFingerprint(task), runSettings);
        return new KeyResult(issue.key(), task.getId(), null);
    }

    private String admitSelection(String requestId, PilotCommands.Selection selection, String runSettings) {
        try {
            return transactions.execute(status -> admitOnce(requestId, selection, runSettings));
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException e) {
            // A concurrent admission or scan won the row. When it holds our request
            // id, resolve to that stored admission; otherwise this click is stale.
            if (attempts.findByRequestId(attemptRequestId(requestId, selection.taskId())).isPresent()) {
                return null;
            }
            return "task changed at the same moment; review and select again";
        }
    }

    /**
     * @return null when the attempt was reserved, otherwise the rejection reason
     */
    private String admitOnce(String requestId, PilotCommands.Selection selection, String runSettings) {
        PilotTask task = tasks.lockById(selection.taskId()).orElse(null);
        if (task == null) {
            return "task " + selection.taskId() + " no longer exists";
        }
        if (task.getVersion() != selection.expectedVersion()
                || !matches(selection.inputFingerprint(), inputFingerprint(task))
                || !matches(selection.destinationFingerprint(), destinationFingerprint(task))) {
            return "task changed since selection; review and select again";
        }
        if (task.getEligibility() != PilotTask.Eligibility.ELIGIBLE) {
            return "task is not eligible: " + task.getRoutingReason();
        }
        if (!task.isInScope()) {
            return "task is no longer assigned to you in Jira; scan again";
        }
        if (!attempts.findLiveByTaskId(task.getId()).isEmpty()) {
            return "task already has a live admission";
        }
        if (attempts.findByTaskIdOrderByOrdinalAsc(task.getId()).stream()
                .anyMatch(attempt -> attempt.getState() != PilotAttempt.State.WITHDRAWN)) {
            return "task already has an attempt; use Retry";
        }
        reserve(task, PilotAttempt.Kind.INITIAL, attemptRequestId(requestId, task.getId()),
                PilotAttempt.AdmittedVia.MANUAL, selection.inputFingerprint(), selection.destinationFingerprint(),
                runSettings);
        return null;
    }

    private void reserve(PilotTask task, PilotAttempt.Kind kind, String attemptRequestId,
                         PilotAttempt.AdmittedVia admittedVia, String input, String destination,
                         String runSettings) {
        PilotAttempt attempt = new PilotAttempt(task.getId(), nextOrdinal(attempts, task.getId()),
                kind, attemptRequestId, PilotAttempt.State.RESERVED);
        attempt.setInputFingerprint(input);
        attempt.setDestinationFingerprint(destination);
        attempt.setApprovedSnapshot(task.getIssueSnapshot());
        attempt.setAdmittedVia(admittedVia);
        attempt.setRunSettings(runSettings);
        attempts.save(attempt);
    }

    /** Null when the token can push to the routed repository; the refusal otherwise (cached per repository). */
    String pushProblem(String routingReason, Map<String, String> checked) {
        String repositoryKey = PilotRoutingReason.eligibleRepositoryKey(routingReason);
        if (repositoryKey == null) {
            return null;
        }
        String problem = checked.computeIfAbsent(repositoryKey, key -> {
            try {
                preflight.requirePushAccess(key);
                return "";
            } catch (RuntimeException e) {
                return e.getMessage() == null ? "GitHub push access could not be checked" : e.getMessage();
            }
        });
        return problem.isEmpty() ? null : problem;
    }

    static int nextOrdinal(PilotAttemptRepository attempts, UUID taskId) {
        return attempts.findByTaskIdOrderByOrdinalAsc(taskId).stream()
                .mapToInt(PilotAttempt::getOrdinal)
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

    static boolean matches(String expected, String current) {
        return expected != null && expected.equals(current);
    }

    /** Identity of the task's bounded intake view; source revision is the snapshot hash. */
    public static String inputFingerprint(PilotTask task) {
        return task.getSourceRevision();
    }

    /** Identity of the task's routing decision; a re-mapped project yields a different value. */
    public static String destinationFingerprint(PilotTask task) {
        return Digests.sha256Hex(task.getRoutingReason());
    }
}
