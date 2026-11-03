package org.folio.factory.app.tasks.source;

import lombok.extern.slf4j.Slf4j;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.TaskRoutingReason;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.connectors.jira.JiraSourceConnector;
import org.folio.factory.connectors.jira.JiraSourceModels.DiscoveredIssue;
import org.folio.factory.connectors.jira.JiraSourceModels.IssuePage;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.intake.IssueSnapshot;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only discovery of the developer's own Jira tasks into durable tasks,
 * run when the developer presses Scan Jira. Every search page is read before a
 * single short transaction replaces the visible task snapshot, so a read or write
 * failure preserves previous rows and the last-success time while recording the
 * problem. Discovery never calls a model or launches an execution.
 */
@Component
@Slf4j
public class TaskDiscoveryService {

    private static final int MAX_PROBLEM_LENGTH = 2000;
    private static final int MAX_TITLE_LENGTH = 500;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_PAGES = 100;

    private final JiraSourceConnector connector;
    private final TaskSourceProperties properties;
    private final RepositoryPolicy repositoryPolicy;
    private final TaskRepository tasks;
    private final TaskInstallationRepository installations;
    private final TaskAttemptRepository attempts;
    private final TransactionTemplate transactions;
    private final TaskJiraInstance jiraInstance;
    private final JsonMapper json = JsonMapper.builder().build();

    public TaskDiscoveryService(TaskSource source,
                                 RepositoryPolicy repositoryPolicy,
                                 TaskRepository tasks,
                                 TaskInstallationRepository installations,
                                 TaskAttemptRepository attempts,
                                 PlatformTransactionManager transactionManager) {
        this.connector = source.connector();
        this.properties = source.properties();
        this.repositoryPolicy = repositoryPolicy;
        this.tasks = tasks;
        this.installations = installations;
        this.attempts = attempts;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jiraInstance = source.jira();
    }

    public record SyncResult(boolean complete, int discoveredCount, Instant lastSuccessfulSync, String problem) {
    }

    /** The routing decision for an issue: one repository, or the reason it cannot run. */
    public record Routing(Task.Eligibility eligibility, String reason) {
    }

    public SyncResult sync() {
        String query;
        try {
            query = properties.effectiveQuery();
        } catch (RuntimeException e) {
            return recordFailure(0, e);
        }
        List<DiscoveredIssue> discovered = new ArrayList<>();
        try {
            readAllPages(query, discovered);
        } catch (RuntimeException e) {
            return recordFailure(discovered.size(), e);
        }
        Instant lastSuccessfulSync;
        try {
            lastSuccessfulSync = transactions.execute(status -> applyCompleteSync(discovered));
        } catch (RuntimeException e) {
            return recordFailure(discovered.size(), e);
        }
        log.info("Task discovery complete: {} issue(s) assigned", discovered.size());
        return new SyncResult(true, discovered.size(), lastSuccessfulSync, null);
    }

    /** One issue by its key, read the same way a scan reads it; null when Jira does not return it. */
    public DiscoveredIssue fetch(String issueKey) {
        IssuePage page = connector.searchIssues("key = " + issueKey, null);
        return page.issues().stream()
                .filter(issue -> issue.key().equalsIgnoreCase(issueKey))
                .findFirst()
                .orElse(null);
    }

    public Routing routing(DiscoveredIssue issue) {
        List<String> candidates = repositoryPolicy.candidates(issue.projectKey(), issue.components());
        if (candidates.size() == 1) {
            return new Routing(Task.Eligibility.ELIGIBLE,
                    TaskRoutingReason.ELIGIBLE_PREFIX + candidates.getFirst());
        }
        if (candidates.isEmpty()) {
            return new Routing(Task.Eligibility.INELIGIBLE, TaskRoutingReason.noRepository(issue.projectKey()));
        }
        return new Routing(Task.Eligibility.INELIGIBLE,
                TaskRoutingReason.severalRepositories(issue.projectKey(), candidates));
    }

    /**
     * Writes the issue's current snapshot and routing onto its task, creating the
     * task on first sight. A scan puts the task in My tasks as one assigned to the
     * developer; otherwise its place in the list does not change, and a new task
     * starts outside it (see {@link #addToList}). Started work holds: a
     * task with a queued or running attempt keeps the key, snapshot and routing it
     * was admitted with, so a later change in Jira cannot redirect or block it; the
     * first scan after the run ends refreshes the row.
     */
    public Task upsert(DiscoveredIssue issue, boolean fromScan) {
        Map<String, Object> snapshot = IssueSnapshot.of(issue.issue());
        Routing routing = routing(issue);
        // The row lock orders this write against admission, which locks the same row before it
        // adds an attempt, so a click that commits first is always seen as live work here.
        Task task = tasks.findByJiraInstanceAndIssueId(jiraInstance.current(), issue.id())
                .flatMap(found -> tasks.lockById(found.getId()))
                .orElse(null);
        if (task != null && !attempts.findLiveByTaskId(task.getId()).isEmpty()) {
            if (fromScan) {
                assignedToDeveloper(task);
            }
            return tasks.save(task);
        }
        if (task == null) {
            task = new Task(jiraInstance.current(), issue.id(), issue.key(),
                    truncated(issue.issue().summary(), MAX_TITLE_LENGTH), issue.projectKey(),
                    json.writeValueAsString(snapshot), revision(snapshot),
                    routing.eligibility(), truncated(routing.reason(), MAX_REASON_LENGTH));
            task.setInScope(fromScan);
        }
        applyIssue(task, issue, snapshot, routing);
        if (fromScan) {
            assignedToDeveloper(task);
        }
        return tasks.save(task);
    }

    /**
     * Puts a task in My tasks by its key, whoever it is assigned to: later scans keep it there until it runs or
     * the developer removes it. Call it in the transaction that locked the task.
     */
    public Task addToList(DiscoveredIssue issue) {
        Task task = upsert(issue, false);
        task.setInScope(true);
        task.setAddedByKey(true);
        return tasks.save(task);
    }

    /** Takes a task that was added by key out of My tasks. The message says why nothing changed, or is null. */
    public String removeFromList(UUID taskId) {
        return transactions.execute(status -> {
            Task task = tasks.lockById(taskId).orElse(null);
            if (task == null || !task.isAddedByKey() || !task.isInScope()) {
                return "This task is not one you added by key; reload the page.";
            }
            boolean ran = attempts.findByTaskIdOrderByOrdinalAsc(taskId).stream()
                    .anyMatch(attempt -> attempt.getState() != TaskAttempt.State.WITHDRAWN);
            if (ran) {
                return task.getDisplayKey() + " has already run, so it stays under Triggered.";
            }
            task.setInScope(false);
            task.setAddedByKey(false);
            tasks.save(task);
            return null;
        });
    }

    /** A scan found the task assigned to the developer: it is one of My tasks, not a task added by key. */
    private static void assignedToDeveloper(Task task) {
        task.setInScope(true);
        task.setAddedByKey(false);
    }

    private void applyIssue(Task task, DiscoveredIssue issue, Map<String, Object> snapshot, Routing routing) {
        task.setDisplayKey(issue.key());
        task.setTitle(truncated(issue.issue().summary(), MAX_TITLE_LENGTH));
        task.setJiraCreatedAt(issue.createdAt());
        task.setProjectKey(issue.projectKey());
        task.setIssueSnapshot(json.writeValueAsString(snapshot));
        task.setSourceRevision(revision(snapshot));
        task.setEligibility(routing.eligibility());
        task.setRoutingReason(truncated(routing.reason(), MAX_REASON_LENGTH));
    }

    private void readAllPages(String query, List<DiscoveredIssue> discovered) {
        String cursor = null;
        int pages = 0;
        do {
            if (++pages > MAX_PAGES) {
                throw new IllegalStateException(
                        "Jira search did not reach a last page within " + MAX_PAGES + " pages");
            }
            IssuePage page = connector.searchIssues(query, cursor);
            discovered.addAll(page.issues());
            cursor = page.nextCursor();
        } while (cursor != null);
    }

    private Instant applyCompleteSync(List<DiscoveredIssue> discovered) {
        // The row lock serializes this write against other installation saves, so
        // a scan and a concurrent dispatch cannot collide on the optimistic version.
        TaskInstallation installation = installations.lockById(1).orElseThrow(TaskInstallation::missing);
        Set<String> discoveredIds = new HashSet<>();
        for (DiscoveredIssue issue : discovered) {
            discoveredIds.add(issue.id());
            upsert(issue, true);
        }
        for (Task task : tasks.findByJiraInstanceAndInScopeTrue(jiraInstance.current())) {
            if (!discoveredIds.contains(task.getIssueId()) && !task.isAddedByKey()) {
                task.setInScope(false);
            }
        }
        installation.setSyncComplete(true);
        installation.setLastSuccessfulSyncAt(Instant.now());
        installation.setSyncProblem(null);
        installations.save(installation);
        return installation.getLastSuccessfulSyncAt();
    }

    private SyncResult recordFailure(int discoveredCount, RuntimeException cause) {
        String problem = truncated(cause.getMessage() == null ? cause.toString() : cause.getMessage(),
                MAX_PROBLEM_LENGTH);
        SyncResult result = transactions.execute(status -> {
            TaskInstallation installation = installations.lockById(1).orElseThrow(TaskInstallation::missing);
            installation.setSyncComplete(false);
            installation.setSyncProblem(problem);
            installations.save(installation);
            return new SyncResult(false, discoveredCount, installation.getLastSuccessfulSyncAt(), problem);
        });
        log.warn("Task discovery incomplete after {} issue(s): {}", discoveredCount, problem);
        return result;
    }

    private String revision(Map<String, Object> snapshot) {
        return Candidate.sha256(json.writeValueAsString(snapshot));
    }

    private static String truncated(String value, int max) {
        String stripped = value == null ? "" : value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }
}
