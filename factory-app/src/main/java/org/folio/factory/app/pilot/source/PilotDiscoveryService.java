package org.folio.factory.app.pilot.source;

import org.folio.factory.app.pilot.Digests;
import org.folio.factory.app.pilot.admission.PilotAutomaticAdmission;
import org.folio.factory.app.pilot.admission.PilotModeService;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotRoutingReason;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.connectors.jira.JiraSourceConnector;
import org.folio.factory.connectors.jira.JiraSourceModels.DiscoveredIssue;
import org.folio.factory.connectors.jira.JiraSourceModels.IssuePage;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.intake.IssueSnapshot;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * Read-only discovery of the team source into durable pilot tasks. Every search
 * page is read before a single short transaction replaces the visible task
 * snapshot, so a read or write failure preserves previous rows and the
 * last-success time while marking the installation's sync incomplete. The
 * observed source authority (saved-filter scope plus routing/destination
 * configuration) is recorded on every complete sync and checked against the
 * accepted authority, so a change reverts automatic mode in the same
 * transaction. Discovery never calls a model or launches an execution; after a
 * complete sync it only asks the automatic admission service to delegate
 * eligible tasks, which it does solely in explicit AUTOMATIC mode.
 */
@Component
@ConditionalOnProperty(prefix = "factory.pilot", name = "discovery.enabled",
        havingValue = "true", matchIfMissing = false)
public class PilotDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(PilotDiscoveryService.class);
    private static final int MAX_PROBLEM_LENGTH = 2000;
    private static final int MAX_TITLE_LENGTH = 500;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_SOURCE_NAME_LENGTH = 500;
    private static final int MAX_SOURCE_URL_LENGTH = 1000;
    private static final int MAX_SPRINT_NAME_LENGTH = 500;
    private static final int MAX_PAGES = 100;

    private final JiraSourceConnector connector;
    private final PilotSourceProperties properties;
    private final RepositoryPolicy repositoryPolicy;
    private final DevFactoryProperties devFactoryProperties;
    private final PilotTaskRepository tasks;
    private final PilotInstallationRepository installations;
    private final TransactionTemplate transactions;
    private final PilotScopeResolver scopeResolver;
    private final PilotModeService modeService;
    private final PilotAutomaticAdmission automaticAdmission;
    private final PilotJiraInstance jiraInstance;
    private final JsonMapper json = JsonMapper.builder().build();

    public PilotDiscoveryService(JiraSourceConnector jiraSourceConnector,
                                 PilotSourceProperties properties,
                                 RepositoryPolicy repositoryPolicy,
                                 DevFactoryProperties devFactoryProperties,
                                 PilotTaskRepository tasks,
                                 PilotInstallationRepository installations,
                                 PlatformTransactionManager transactionManager,
                                 PilotModeService modeService,
                                 PilotAutomaticAdmission automaticAdmission,
                                 PilotJiraInstance jiraInstance) {
        this.connector = jiraSourceConnector;
        this.properties = properties;
        this.repositoryPolicy = repositoryPolicy;
        this.devFactoryProperties = devFactoryProperties;
        this.tasks = tasks;
        this.installations = installations;
        this.transactions = new TransactionTemplate(transactionManager);
        this.scopeResolver = new PilotScopeResolver(connector);
        this.modeService = modeService;
        this.automaticAdmission = automaticAdmission;
        this.jiraInstance = jiraInstance;
    }

    public record SyncResult(boolean complete, int discoveredCount, Instant lastSuccessfulSync, String problem) {
    }

    public SyncResult sync() {
        PilotScopeResolver.PilotScope scope;
        try {
            scope = scopeResolver.resolve(properties);
        } catch (RuntimeException e) {
            return recordFailure(0, e);
        }
        List<DiscoveredIssue> discovered = new ArrayList<>();
        try {
            readAllPages(scope, discovered);
        } catch (RuntimeException e) {
            return recordFailure(discovered.size(), e);
        }
        Instant lastSuccessfulSync;
        try {
            lastSuccessfulSync = transactions.execute(status -> applyCompleteSync(scope, discovered));
        } catch (RuntimeException e) {
            return recordFailure(discovered.size(), e);
        }
        admitNewMatches();
        log.info("Pilot discovery complete: {} issue(s) in scope", discovered.size());
        return new SyncResult(true, discovered.size(), lastSuccessfulSync, null);
    }

    private void admitNewMatches() {
        try {
            automaticAdmission.admitEligible();
        } catch (RuntimeException e) {
            log.warn("Automatic pilot admission after sync failed; the next sync retries: {}",
                    e.getMessage());
        }
    }

    private void readAllPages(PilotScopeResolver.PilotScope scope, List<DiscoveredIssue> discovered) {
        String cursor = null;
        int pages = 0;
        do {
            if (++pages > MAX_PAGES) {
                throw new IllegalStateException(
                        "Jira search did not reach a last page within " + MAX_PAGES + " pages");
            }
            IssuePage page = connector.searchIssues(scope.effectiveQuery(), cursor);
            discovered.addAll(page.issues());
            cursor = page.nextCursor();
        } while (cursor != null);
    }

    private Instant applyCompleteSync(PilotScopeResolver.PilotScope scope, List<DiscoveredIssue> discovered) {
        // The row lock serializes this write against other installation saves, so
        // a first-boot sync and a concurrent dispatch or mode change cannot collide
        // on the optimistic version and surface a spurious sync problem.
        PilotInstallation installation = installations.lockById(1).orElseGet(PilotInstallation::initial);
        Set<String> discoveredIds = new HashSet<>();
        for (DiscoveredIssue issue : discovered) {
            discoveredIds.add(issue.id());
            upsertTask(issue);
        }
        for (PilotTask task : tasks.findByJiraInstanceAndInScopeTrue(jiraInstance.current())) {
            if (!discoveredIds.contains(task.getIssueId())) {
                task.setInScope(false);
            }
        }
        installation.setObservedAuthorityFingerprint(observedAuthority(scope));
        installation.setSourceName(truncated(scope.source().name(), MAX_SOURCE_NAME_LENGTH));
        installation.setSourceUrl(scope.source().link() == null
                ? null : truncated(scope.source().link().toString(), MAX_SOURCE_URL_LENGTH));
        installation.setSprintName(truncated(scope.sprint().name(), MAX_SPRINT_NAME_LENGTH));
        installation.setSyncComplete(true);
        installation.setLastSuccessfulSyncAt(Instant.now());
        installation.setSyncProblem(null);
        modeService.revertIfAuthorityChanged(installation);
        installations.save(installation);
        return installation.getLastSuccessfulSyncAt();
    }

    /**
     * The observed installation authority: the resolved Jira scope plus the
     * trusted routing/destination configuration, so a saved-filter, routing or
     * destination change is detectable as a fingerprint change.
     */
    private String observedAuthority(PilotScopeResolver.PilotScope scope) {
        return Digests.sha256Hex(scope.authorityFingerprint() + "\n" + destinationAuthority());
    }

    private String destinationAuthority() {
        StringBuilder canonical = new StringBuilder(devFactoryProperties.gitBaseUrl()).append('\n');
        devFactoryProperties.repositories().forEach((key, repository) -> canonical.append(key)
                .append('|').append(repository.sourceRepo())
                .append('|').append(repository.baseBranch())
                .append('|').append(repository.jiraProjects())
                .append('|').append(repository.jiraComponents())
                .append('|').append(repository.delivery() == null ? "" : repository.delivery().repository())
                .append('|').append(repository.delivery() != null && repository.delivery().authorized())
                .append('\n'));
        return Digests.sha256Hex(canonical.toString());
    }

    private void upsertTask(DiscoveredIssue issue) {
        Map<String, Object> snapshot = IssueSnapshot.of(issue.issue());
        Evaluation evaluation = evaluate(issue, snapshot);
        PilotTask task = tasks.findByJiraInstanceAndIssueId(jiraInstance.current(), issue.id())
                .orElseGet(() -> new PilotTask(jiraInstance.current(), issue.id(), issue.key(),
                        truncated(issue.issue().summary(), MAX_TITLE_LENGTH), issue.projectKey(),
                        json.writeValueAsString(snapshot), revision(snapshot),
                        evaluation.eligibility(), truncated(evaluation.reason(), MAX_REASON_LENGTH)));
        task.setDisplayKey(issue.key());
        task.setTitle(truncated(issue.issue().summary(), MAX_TITLE_LENGTH));
        task.setJiraCreatedAt(issue.createdAt());
        task.setProjectKey(issue.projectKey());
        task.setIssueSnapshot(json.writeValueAsString(snapshot));
        task.setSourceRevision(revision(snapshot));
        task.setEligibility(evaluation.eligibility());
        task.setRoutingReason(truncated(evaluation.reason(), MAX_REASON_LENGTH));
        task.setInScope(true);
        tasks.save(task);
    }

    private Evaluation evaluate(DiscoveredIssue issue, Map<String, Object> snapshot) {
        List<String> dependencies = linkedKeys(snapshot);
        if (!dependencies.isEmpty()) {
            return new Evaluation(PilotTask.Eligibility.INELIGIBLE, "known dependency: " + join(dependencies));
        }
        List<String> candidates = repositoryPolicy.candidates(issue.projectKey(), issue.components());
        if (candidates.size() == 1) {
            return new Evaluation(PilotTask.Eligibility.ELIGIBLE,
                    PilotRoutingReason.ELIGIBLE_PREFIX + candidates.getFirst());
        }
        if (candidates.isEmpty()) {
            return new Evaluation(PilotTask.Eligibility.INELIGIBLE,
                    "no repository mapping for project " + issue.projectKey());
        }
        return new Evaluation(PilotTask.Eligibility.INELIGIBLE,
                "ambiguous repository mapping: " + join(candidates));
    }

    private static List<String> linkedKeys(Map<String, Object> snapshot) {
        if (!(snapshot.get("links") instanceof List<?> links)) {
            return List.of();
        }
        return links.stream()
                .filter(Map.class::isInstance)
                .map(link -> String.valueOf(((Map<?, ?>) link).get("key")).strip())
                .filter(key -> !key.isEmpty() && !"null".equals(key))
                .toList();
    }

    private SyncResult recordFailure(int discoveredCount, RuntimeException cause) {
        String problem = truncated(cause.getMessage() == null ? cause.toString() : cause.getMessage(),
                MAX_PROBLEM_LENGTH);
        SyncResult result = transactions.execute(status -> {
            PilotInstallation installation = currentInstallation();
            installation.setSyncComplete(false);
            installation.setSyncProblem(problem);
            installations.save(installation);
            return new SyncResult(false, discoveredCount, installation.getLastSuccessfulSyncAt(), problem);
        });
        log.warn("Pilot discovery incomplete after {} issue(s): {}", discoveredCount, problem);
        return result;
    }

    private PilotInstallation currentInstallation() {
        return installations.findById(1).orElseGet(PilotInstallation::initial);
    }

    private String revision(Map<String, Object> snapshot) {
        return Digests.sha256Hex(json.writeValueAsString(snapshot));
    }

    private static String join(List<String> values) {
        return String.join(", ", values);
    }

    private static String truncated(String value, int max) {
        String stripped = value == null ? "" : value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }

    private record Evaluation(PilotTask.Eligibility eligibility, String reason) {
    }
}
