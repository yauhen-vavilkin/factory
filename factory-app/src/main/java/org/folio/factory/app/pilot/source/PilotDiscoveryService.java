package org.folio.factory.app.pilot.source;

import org.folio.factory.app.pilot.Digests;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotRoutingReason;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.connectors.jira.JiraSourceConnector;
import org.folio.factory.connectors.jira.JiraSourceModels.DiscoveredIssue;
import org.folio.factory.connectors.jira.JiraSourceModels.IssuePage;
import org.folio.factory.devfactory.intake.IssueSnapshot;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Read-only discovery of the developer's own Jira tasks into durable pilot tasks,
 * run when the developer presses Scan Jira. Every search page is read before a
 * single short transaction replaces the visible task snapshot, so a read or write
 * failure preserves previous rows and the last-success time while recording the
 * problem. Discovery never calls a model or launches an execution.
 */
@Component
public class PilotDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(PilotDiscoveryService.class);
    private static final int MAX_PROBLEM_LENGTH = 2000;
    private static final int MAX_TITLE_LENGTH = 500;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_PAGES = 100;

    private final JiraSourceConnector connector;
    private final PilotSourceProperties properties;
    private final RepositoryPolicy repositoryPolicy;
    private final PilotTaskRepository tasks;
    private final PilotInstallationRepository installations;
    private final TransactionTemplate transactions;
    private final PilotJiraInstance jiraInstance;
    private final JsonMapper json = JsonMapper.builder().build();

    public PilotDiscoveryService(JiraSourceConnector jiraSourceConnector,
                                 PilotSourceProperties properties,
                                 RepositoryPolicy repositoryPolicy,
                                 PilotTaskRepository tasks,
                                 PilotInstallationRepository installations,
                                 PlatformTransactionManager transactionManager,
                                 PilotJiraInstance jiraInstance) {
        this.connector = jiraSourceConnector;
        this.properties = properties;
        this.repositoryPolicy = repositoryPolicy;
        this.tasks = tasks;
        this.installations = installations;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jiraInstance = jiraInstance;
    }

    public record SyncResult(boolean complete, int discoveredCount, Instant lastSuccessfulSync, String problem) {
    }

    /** The routing decision for an issue: one repository, or the reason it cannot run. */
    public record Routing(PilotTask.Eligibility eligibility, String reason) {
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
        log.info("Pilot discovery complete: {} issue(s) assigned", discovered.size());
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
            return new Routing(PilotTask.Eligibility.ELIGIBLE,
                    PilotRoutingReason.ELIGIBLE_PREFIX + candidates.getFirst());
        }
        if (candidates.isEmpty()) {
            return new Routing(PilotTask.Eligibility.INELIGIBLE,
                    "Project " + issue.projectKey() + " is not configured: no repository for it in"
                            + " config/developer-repositories.yaml");
        }
        return new Routing(PilotTask.Eligibility.INELIGIBLE,
                "Project " + issue.projectKey() + " maps to several repositories: " + String.join(", ", candidates));
    }

    /**
     * Writes the issue's current snapshot and routing onto its task, creating the
     * task on first sight. Only a scan decides whether the task is in My tasks;
     * a task first seen through its key starts outside it.
     */
    public PilotTask upsert(DiscoveredIssue issue, boolean fromScan) {
        Map<String, Object> snapshot = IssueSnapshot.of(issue.issue());
        Routing routing = routing(issue);
        PilotTask task = tasks.findByJiraInstanceAndIssueId(jiraInstance.current(), issue.id()).orElse(null);
        if (task == null) {
            task = new PilotTask(jiraInstance.current(), issue.id(), issue.key(),
                    truncated(issue.issue().summary(), MAX_TITLE_LENGTH), issue.projectKey(),
                    json.writeValueAsString(snapshot), revision(snapshot),
                    routing.eligibility(), truncated(routing.reason(), MAX_REASON_LENGTH));
            task.setInScope(fromScan);
        }
        task.setDisplayKey(issue.key());
        task.setTitle(truncated(issue.issue().summary(), MAX_TITLE_LENGTH));
        task.setJiraCreatedAt(issue.createdAt());
        task.setProjectKey(issue.projectKey());
        task.setIssueSnapshot(json.writeValueAsString(snapshot));
        task.setSourceRevision(revision(snapshot));
        task.setEligibility(routing.eligibility());
        task.setRoutingReason(truncated(routing.reason(), MAX_REASON_LENGTH));
        if (fromScan) {
            task.setInScope(true);
        }
        return tasks.save(task);
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
        PilotInstallation installation = installations.lockById(1).orElseGet(PilotInstallation::initial);
        Set<String> discoveredIds = new HashSet<>();
        for (DiscoveredIssue issue : discovered) {
            discoveredIds.add(issue.id());
            upsert(issue, true);
        }
        for (PilotTask task : tasks.findByJiraInstanceAndInScopeTrue(jiraInstance.current())) {
            if (!discoveredIds.contains(task.getIssueId())) {
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
            PilotInstallation installation = installations.lockById(1).orElseGet(PilotInstallation::initial);
            installation.setSyncComplete(false);
            installation.setSyncProblem(problem);
            installations.save(installation);
            return new SyncResult(false, discoveredCount, installation.getLastSuccessfulSyncAt(), problem);
        });
        log.warn("Pilot discovery incomplete after {} issue(s): {}", discoveredCount, problem);
        return result;
    }

    private String revision(Map<String, Object> snapshot) {
        return Digests.sha256Hex(json.writeValueAsString(snapshot));
    }

    private static String truncated(String value, int max) {
        String stripped = value == null ? "" : value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }
}
