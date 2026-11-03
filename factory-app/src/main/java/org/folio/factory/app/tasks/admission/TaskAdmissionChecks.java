package org.folio.factory.app.tasks.admission;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskRoutingReason;
import org.folio.factory.app.tasks.source.TaskDiscoveryService;
import org.folio.factory.app.tasks.source.TaskJiraInstance;
import org.folio.factory.connectors.jira.JiraSourceModels.DiscoveredIssue;
import org.folio.factory.devfactory.candidate.Candidate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * What a task must still be before an attempt is reserved for it: unchanged since the developer saw it,
 * eligible, readable in Jira, routed to a repository the GitHub token can push to, and with an attempt history
 * that allows a first run or a Retry. Each check returns null when it passes and the refusal shown on My
 * tasks otherwise.
 */
@Component
@RequiredArgsConstructor
public class TaskAdmissionChecks {

    private final TaskDiscoveryService discovery;
    private final TaskJiraInstance jiraInstance;
    private final DeveloperPreflight preflight;

    /** The issue Jira returned, or why it could not be read. */
    record FetchedIssue(DiscoveredIssue issue, String problem) {
        static FetchedIssue refused(String problem) {
            return new FetchedIssue(null, problem);
        }
    }

    FetchedIssue fetch(String issueKey) {
        DiscoveredIssue issue;
        try {
            issue = discovery.fetch(issueKey);
        } catch (HttpClientErrorException e) {
            issue = null;
        } catch (RestClientResponseException e) {
            // The response body may be a whole HTML error page; the status says enough.
            return FetchedIssue.refused("Jira could not be read: HTTP " + e.getStatusCode().value());
        } catch (RuntimeException e) {
            return FetchedIssue.refused("Jira could not be read: " + e.getMessage());
        }
        if (issue == null) {
            return FetchedIssue.refused("Jira has no task " + issueKey + " that Factory can read");
        }
        return new FetchedIssue(issue, null);
    }

    /** The task as Jira shows it now, or null when Jira cannot be read and the stored snapshot is used. */
    DiscoveredIssue freshIssue(Task task) {
        if (!task.getJiraInstance().equals(jiraInstance.current())) {
            return null;
        }
        String issueKey;
        try {
            issueKey = jiraInstance.issueKey(task.getDisplayKey());
        } catch (RuntimeException e) {
            return null;
        }
        DiscoveredIssue issue = fetch(issueKey).issue();
        return issue != null && issue.id().equals(task.getIssueId()) ? issue : null;
    }

    /** Null when the token can push to the routed repository; the refusal otherwise (cached per repository). */
    String pushProblem(String routingReason, Map<String, String> checked) {
        String repositoryKey = TaskRoutingReason.eligibleRepositoryKey(routingReason);
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

    /** Identity of the task's bounded intake view; source revision is the snapshot hash. */
    public static String inputFingerprint(Task task) {
        return task.getSourceRevision();
    }

    /** Identity of the task's routing decision; a re-mapped project yields a different value. */
    public static String destinationFingerprint(Task task) {
        return Candidate.sha256(task.getRoutingReason());
    }

    static boolean changedSince(Task task, long expectedVersion, String inputFingerprint,
                                String destinationFingerprint) {
        return task.getVersion() != expectedVersion
                || !matches(inputFingerprint, inputFingerprint(task))
                || !matches(destinationFingerprint, destinationFingerprint(task));
    }

    static String eligibilityProblem(Task task) {
        return task.getEligibility() == Task.Eligibility.ELIGIBLE ? null
                : "task is not eligible: " + task.getRoutingReason();
    }

    /** A first run of the selected task; {@code live} tells whether it already has a live attempt. */
    static String selectionProblem(Task task, TaskCommands.Selection selection, boolean live,
                                   List<TaskAttempt> history) {
        if (changedSince(task, selection.expectedVersion(), selection.inputFingerprint(),
                selection.destinationFingerprint())) {
            return "task changed since selection; review and select again";
        }
        if (eligibilityProblem(task) != null) {
            return eligibilityProblem(task);
        }
        if (!task.isInScope()) {
            return "task is no longer assigned to you in Jira; scan again";
        }
        if (live) {
            return "task already has a live admission";
        }
        if (history.stream().anyMatch(attempt -> attempt.getState() != TaskAttempt.State.WITHDRAWN)) {
            return "task already has an attempt; use Retry";
        }
        return null;
    }

    static String retryProblem(Task task, TaskCommands.Retry command, List<TaskAttempt> history) {
        if (changedSince(task, command.expectedVersion(), command.inputFingerprint(),
                command.destinationFingerprint())) {
            return "task changed since the retry was requested; review the current result and retry again";
        }
        if (eligibilityProblem(task) != null) {
            return eligibilityProblem(task);
        }
        return historyProblem(history);
    }

    /** Null when the task re-read from Jira can still retry on the repository this click checked. */
    static String freshTaskProblem(Task task, String checkedDestination) {
        if (eligibilityProblem(task) != null) {
            return eligibilityProblem(task);
        }
        if (!checkedDestination.equals(destinationFingerprint(task))) {
            return "the task now routes to another repository in Jira; review it and retry";
        }
        return null;
    }

    /** Null when the attempt history allows a fresh Retry; the reason otherwise. */
    private static String historyProblem(List<TaskAttempt> history) {
        if (history.isEmpty()) {
            return "the task has never been attempted; select it for admission instead";
        }
        TaskAttempt.State latest = history.getLast().getState();
        if (latest.isInFlight()) {
            return "the task already has a live attempt";
        }
        if (latest == TaskAttempt.State.NEEDS_INSPECTION) {
            return "the latest attempt needs inspection; retry stays disabled until the outcome is resolved";
        }
        if (latest == TaskAttempt.State.PR_READY) {
            return "the latest attempt delivered a pull request; retry does not reopen delivered work";
        }
        if (latest == TaskAttempt.State.WITHDRAWN && history.stream()
                .allMatch(attempt -> attempt.getState() == TaskAttempt.State.WITHDRAWN)) {
            return "the latest admission was withdrawn before start; select the task again";
        }
        // A withdrawn retry, or a withdrawal after earlier real work, is not the
        // terminal word: a fresh explicit Retry stays possible.
        return null;
    }

    private static boolean matches(String expected, String current) {
        return expected != null && expected.equals(current);
    }
}
