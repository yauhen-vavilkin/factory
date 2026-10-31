package org.folio.factory.app.tasks.presentation;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.source.TaskJiraInstance;
import org.folio.factory.app.tasks.source.TaskSourceProperties;
import org.folio.factory.app.web.UiFormat;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.folio.factory.app.tasks.presentation.TaskLabels.ago;
import static org.folio.factory.app.tasks.presentation.TaskLabels.attemptResult;
import static org.folio.factory.app.tasks.presentation.TaskLabels.categoryLabel;
import static org.folio.factory.app.tasks.presentation.TaskLabels.costLabel;
import static org.folio.factory.app.tasks.presentation.TaskLabels.costNoteTitle;
import static org.folio.factory.app.tasks.presentation.TaskLabels.incompleteCostNote;
import static org.folio.factory.app.tasks.presentation.TaskLabels.minutes;
import static org.folio.factory.app.tasks.presentation.TaskLabels.pullRequestLabel;
import static org.folio.factory.app.tasks.presentation.TaskLabels.queued;
import static org.folio.factory.app.tasks.presentation.TaskLabels.resultChips;
import static org.folio.factory.app.tasks.presentation.TaskLabels.resultSentence;
import static org.folio.factory.app.tasks.presentation.TaskLabels.stateClass;
import static org.folio.factory.app.tasks.presentation.TaskLabels.stateLabel;
import static org.folio.factory.app.tasks.presentation.TaskLabels.triggeredDetail;
import static org.folio.factory.app.tasks.presentation.TaskLabels.usageNote;
import static org.folio.factory.app.tasks.presentation.TaskLabels.working;

/**
 * Page rows of My tasks, the task summary and the run page header, built from the
 * task projection. My tasks adds the shown flag to the available rows after the
 * status chips are counted, so the rows are Maps.
 */
@Component
@RequiredArgsConstructor
public class TaskRows {

    private static final Set<String> DEFAULT_STATUSES = Set.of("open", "in progress");
    private static final DateTimeFormatter STARTED =
            DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US).withZone(ZoneOffset.UTC);

    private final TaskSourceProperties source;
    private final TaskJiraInstance jira;
    private final DevFactoryProperties repositories;

    public Map<String, Object> sourceCard(TaskInstallation installation) {
        Map<String, Object> card = new LinkedHashMap<>();
        String base = jira.baseUrl();
        card.put("assignee", source.assignee());
        card.put("jiraHost", base == null ? null : jira.current());
        boolean ready = source.assignee() != null && base != null;
        card.put("query", ready ? source.effectiveQuery() : null);
        card.put("searchUrl", ready ? source.searchUrl(base) : null);
        card.put("lastScan", ago(installation.getLastSuccessfulSyncAt()));
        card.put("lastScanTitle", UiFormat.format(installation.getLastSuccessfulSyncAt()));
        card.put("problem", installation.getSyncProblem());
        return card;
    }

    /** "PROJECT → repository" for each catalog entry, the routing a scan applies. */
    public String repositoryMap() {
        List<String> parts = new ArrayList<>();
        repositories.repositories().forEach((key, repository) -> repository.jiraProjects()
                .forEach(project -> parts.add(project + " → " + repositoryName(key))));
        return parts.size() + (parts.size() == 1 ? " Jira project" : " Jira projects") + " configured: "
                + String.join(" · ", parts);
    }

    public Map<String, Object> availableRow(TaskView view) {
        Map<String, Object> row = baseRow(view);
        row.put("jiraStatus", view.jiraStatus() == null ? "Unknown" : view.jiraStatus());
        row.put("selectable", view.allowedActions().select());
        row.put("notEligibleReason", view.state() == TaskView.TaskState.UNSUPPORTED ? view.reason() : null);
        return row;
    }

    public static List<Map<String, Object>> statusChips(List<Map<String, Object>> available) {
        Map<String, Long> counts = new TreeMap<>();
        for (Map<String, Object> row : available) {
            counts.merge((String) row.get("jiraStatus"), 1L, Long::sum);
        }
        boolean anyDefault = counts.keySet().stream()
                .anyMatch(status -> DEFAULT_STATUSES.contains(status.toLowerCase(Locale.ROOT)));
        List<Map<String, Object>> chips = new ArrayList<>();
        counts.forEach((status, count) -> chips.add(Map.of(
                "status", status,
                "count", count,
                "pressed", !anyDefault || DEFAULT_STATUSES.contains(status.toLowerCase(Locale.ROOT)))));
        chips.sort((left, right) -> Integer.compare(chipOrder((String) left.get("status")),
                chipOrder((String) right.get("status"))));
        return chips;
    }

    /** Marks an available row shown when the chip of its Jira status is pressed. */
    public static void markShown(List<Map<String, Object>> available, List<Map<String, Object>> chips) {
        Set<Object> shownStatuses = chips.stream().filter(chip -> (boolean) chip.get("pressed"))
                .map(chip -> chip.get("status")).collect(Collectors.toSet());
        available.forEach(row -> row.put("shown", shownStatuses.contains(row.get("jiraStatus"))));
    }

    /** Shown available rows, counted by whether they can run (true) or not (false). */
    public static Map<Boolean, Long> countShownByRunnable(List<Map<String, Object>> available) {
        return available.stream().filter(row -> (boolean) row.get("shown"))
                .collect(Collectors.partitioningBy(row -> (boolean) row.get("selectable"), Collectors.counting()));
    }

    /** True when the latest attempt of a task needs inspection, so its row offers Mark as stopped. */
    public static boolean anyNeedsInspection(List<TaskView> views) {
        return views.stream().anyMatch(view -> !view.attempts().isEmpty()
                && view.attempts().getLast().state() == TaskAttempt.State.NEEDS_INSPECTION);
    }

    public Map<String, Object> triggeredRow(TaskView view) {
        Map<String, Object> row = baseRow(view);
        var latest = view.attempts().getLast();
        var label = stateLabel(view);
        row.put("stateLabel", label);
        row.put("stateClass", stateClass(label));
        // A task added by key stays marked after a Retry: it is still not one of My tasks.
        row.put("addedByKey", !view.inScope() && view.attempts().stream()
                .anyMatch(attempt -> attempt.admittedVia() == TaskAttempt.AdmittedVia.BY_KEY));
        row.put("link", working(view) && latest.executionId() != null
                ? "/executions/" + latest.executionId() : "/tasks/" + view.taskId());
        row.put("detail", triggeredDetail(view));
        putPullRequest(row, view.pullRequestUrl());
        row.put("retryAllowed", view.allowedActions().retry());
        row.put("markStoppedAttemptId", markStoppedAttemptId(latest));
        row.put("cost", costLabel(view.usage()));
        putCostNote(row, triggeredCostNote(view));
        putWhen(row, latest, working(view));
        return row;
    }

    public Map<String, Object> detail(TaskView view) {
        Map<String, Object> row = baseRow(view);
        var label = view.attempts().isEmpty() ? null : stateLabel(view);
        var latest = view.attempts().isEmpty() ? null : view.attempts().getLast();
        row.put("stateLabel", label == null ? (view.state() == TaskView.TaskState.UNSUPPORTED
                ? "Not eligible" : "Not started") : label);
        row.put("stateClass", stateClass(label));
        row.put("result", resultSentence(view));
        row.put("chips", resultChips(view));
        row.put("retryAllowed", view.allowedActions().retry());
        row.put("lastRunUrl",
                latest == null || latest.executionId() == null ? null : "/executions/" + latest.executionId());
        putPullRequest(row, view.pullRequestUrl());
        row.put("prAction", view.pullRequestUrl() == null ? null
                : "Open p" + pullRequestLabel(view.pullRequestUrl()).substring(1));
        row.put("draftReasons", view.draftReasons());
        row.put("markStoppedAttemptId", latest == null ? null : markStoppedAttemptId(latest));
        row.put("project", view.projectKey());
        row.put("destination", destination(view.destinationRepository()));
        putUsage(row, view.usage());
        return row;
    }

    public static Map<String, Object> attemptRow(TaskView.AttemptHistoryEntry attempt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", attempt.ordinal() + " · " + (attempt.kind() == TaskAttempt.Kind.RETRY ? "retry" : "first run")
                + (attempt.admittedVia() == TaskAttempt.AdmittedVia.BY_KEY ? " by key" : ""));
        row.put("ordinal", attempt.ordinal());
        row.put("run", TaskRunSettings.label(attempt.runSettings()));
        row.put("executionId", attempt.executionId());
        boolean draft = attempt.state() == TaskAttempt.State.PR_READY && attempt.draftReasons() != null;
        row.put("resultLabel", draft ? "Draft PR" : null);
        row.put("resultClass", draft ? stateClass("Draft PR") : null);
        row.put("result", attemptResult(attempt));
        row.put("started", attempt.startedAt() == null ? "not started" : STARTED.format(attempt.startedAt()));
        row.put("startedTitle", UiFormat.format(attempt.startedAt()));
        row.put("active", attempt.paidStart() ? minutes(attempt.activeSeconds()) : "—");
        row.put("cost", costLabel(attempt.usage()));
        putCostNote(row, attemptCostNote(attempt));
        return row;
    }

    /** The run page's link back to its task: key, Jira link and the state of the attempt behind this run. */
    public static Map<String, Object> runContext(TaskView view, UUID executionId) {
        String label = view.attempts().stream()
                .filter(attempt -> executionId.equals(attempt.executionId()))
                .findFirst()
                .map(TaskLabels::attemptLabel)
                .orElseGet(() -> stateLabel(view));
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("taskId", view.taskId());
        run.put("key", view.displayKey());
        run.put("title", view.title());
        run.put("jiraUrl", view.jiraUrl());
        run.put("stateLabel", label);
        run.put("stateClass", stateClass(label));
        return run;
    }

    /** The notice that the queue waits on an attempt that needs inspection. */
    public static Map<String, Object> queueWaiting(String key, TaskAttempt attempt) {
        Map<String, Object> waiting = new LinkedHashMap<>();
        waiting.put("key", key);
        waiting.put("taskId", attempt.getTaskId());
        waiting.put("attemptId", attempt.getId());
        return waiting;
    }

    private Map<String, Object> baseRow(TaskView view) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", view.taskId());
        row.put("key", view.displayKey());
        row.put("jiraUrl", view.jiraUrl());
        row.put("title", view.title());
        row.put("repository",
                view.destinationRepository() == null ? null : repositoryName(view.destinationRepository()));
        row.put("version", view.version());
        row.put("inputFingerprint", view.inputFingerprint());
        row.put("destinationFingerprint", view.destinationFingerprint());
        return row;
    }

    private static void putPullRequest(Map<String, Object> row, String url) {
        row.put("prUrl", url);
        row.put("prLabel", url == null ? null : pullRequestLabel(url));
    }

    private static String triggeredCostNote(TaskView view) {
        return queued(view) ? "not started" : working(view) ? "so far" : incompleteCostNote(view.usage());
    }

    /** Same notes as Triggered; a queued attempt already says "not started" in Started. */
    private static String attemptCostNote(TaskView.AttemptHistoryEntry attempt) {
        return switch (attempt.state()) {
            case RESERVED -> null;
            case QUEUED, RUNNING -> "so far";
            default -> incompleteCostNote(attempt.usage());
        };
    }

    /** An attempt that needs inspection offers Mark as stopped. */
    private static UUID markStoppedAttemptId(TaskView.AttemptHistoryEntry attempt) {
        return attempt.state() == TaskAttempt.State.NEEDS_INSPECTION ? attempt.attemptId() : null;
    }

    private static void putCostNote(Map<String, Object> row, String costNote) {
        row.put("costNote", costNote);
        row.put("costNoteTitle", costNoteTitle(costNote));
    }

    private static void putWhen(Map<String, Object> row, TaskView.AttemptHistoryEntry latest, boolean working) {
        boolean started = latest.startedAt() != null;
        row.put("when", ago(started ? latest.startedAt() : latest.createdAt()));
        row.put("whenTitle", UiFormat.format(started ? latest.startedAt() : latest.createdAt()));
        row.put("whenNote", working ? "started"
                : !started ? "triggered" : minutes(latest.activeSeconds()) + " active");
    }

    private static void putUsage(Map<String, Object> row, TaskUsage usage) {
        row.put("usagePresent", usage.tokens() > 0 || !usage.estimatesByCurrency().isEmpty());
        row.put("usageTokens", UiFormat.count(usage.tokens()));
        row.put("usageCategories", usage.tokensByCategory().entrySet().stream()
                .map(entry -> Map.of("label", categoryLabel(entry.getKey()),
                        "value", UiFormat.count(entry.getValue())))
                .toList());
        row.put("usageCost", costLabel(usage));
        row.put("usageNote", usageNote(usage));
    }

    private static int chipOrder(String status) {
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "open" -> 0;
            case "in progress" -> 1;
            default -> 2;
        };
    }

    private String repositoryName(String repositoryKey) {
        DevFactoryProperties.Repository repository = repositories.repositories().get(repositoryKey);
        if (repository == null) {
            return repositoryKey;
        }
        String sourceRepo = repository.sourceRepo();
        return sourceRepo.substring(sourceRepo.indexOf('/') + 1);
    }

    private String destination(String repositoryKey) {
        var repository = repositoryKey == null ? null : repositories.repositories().get(repositoryKey);
        var target = repository == null ? null : repository.delivery();
        return target == null || target.repository() == null ? null
                : target.repository() + " · base " + target.baseBranch();
    }
}
