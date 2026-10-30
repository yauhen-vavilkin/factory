package org.folio.factory.app.pilot.presentation;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.pilot.admission.PilotRunSettings;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.source.PilotJiraInstance;
import org.folio.factory.app.pilot.source.PilotSourceProperties;
import org.folio.factory.app.web.UiFormat;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
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

import static org.folio.factory.app.pilot.presentation.PilotTaskLabels.ago;
import static org.folio.factory.app.pilot.presentation.PilotTaskLabels.attemptResult;
import static org.folio.factory.app.pilot.presentation.PilotTaskLabels.attemptStateLabel;
import static org.folio.factory.app.pilot.presentation.PilotTaskLabels.categoryLabel;
import static org.folio.factory.app.pilot.presentation.PilotTaskLabels.costLabel;
import static org.folio.factory.app.pilot.presentation.PilotTaskLabels.costNoteTitle;
import static org.folio.factory.app.pilot.presentation.PilotTaskLabels.incompleteCostNote;
import static org.folio.factory.app.pilot.presentation.PilotTaskLabels.minutes;
import static org.folio.factory.app.pilot.presentation.PilotTaskLabels.pullRequestLabel;
import static org.folio.factory.app.pilot.presentation.PilotTaskLabels.resultChips;
import static org.folio.factory.app.pilot.presentation.PilotTaskLabels.resultSentence;
import static org.folio.factory.app.pilot.presentation.PilotTaskLabels.stateClass;
import static org.folio.factory.app.pilot.presentation.PilotTaskLabels.stateLabel;
import static org.folio.factory.app.pilot.presentation.PilotTaskLabels.triggeredDetail;
import static org.folio.factory.app.pilot.presentation.PilotTaskLabels.usageNote;

/**
 * Page rows of My tasks, the task summary and the run page header, built from the
 * task projection. My tasks adds the shown flag to the available rows after the
 * status chips are counted, so the rows are Maps.
 */
@Component
@RequiredArgsConstructor
public class PilotTaskRows {

    private static final Set<String> DEFAULT_STATUSES = Set.of("open", "in progress");
    private static final DateTimeFormatter STARTED =
            DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US).withZone(ZoneOffset.UTC);

    private final PilotSourceProperties source;
    private final PilotJiraInstance jira;
    private final DevFactoryProperties repositories;
    private final DevDeliveryProperties delivery;

    public Map<String, Object> sourceCard(PilotInstallation installation) {
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

    public Map<String, Object> availableRow(PilotTaskView view) {
        Map<String, Object> row = baseRow(view);
        row.put("jiraStatus", view.jiraStatus() == null ? "Unknown" : view.jiraStatus());
        row.put("selectable", view.allowedActions().select());
        row.put("notEligibleReason", view.state() == PilotTaskView.TaskState.UNSUPPORTED ? view.reason() : null);
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
    public static boolean anyNeedsInspection(List<PilotTaskView> views) {
        return views.stream().anyMatch(view ->
                !view.attempts().isEmpty() && "NEEDS_INSPECTION".equals(view.attempts().getLast().state()));
    }

    public Map<String, Object> triggeredRow(PilotTaskView view) {
        Map<String, Object> row = baseRow(view);
        var latest = view.attempts().getLast();
        var label = stateLabel(view);
        row.put("stateLabel", label);
        row.put("stateClass", stateClass(label));
        // A task added by key stays marked after a Retry: it is still not one of My tasks.
        row.put("addedByKey", !view.inScope() && view.attempts().stream()
                .anyMatch(attempt -> "BY_KEY".equals(attempt.admittedVia())));
        row.put("link", "Working".equals(label) && latest.executionId() != null
                ? "/executions/" + latest.executionId() : "/tasks/" + view.taskId());
        row.put("detail", triggeredDetail(view, label));
        putPullRequest(row, view.pullRequestUrl());
        row.put("retryAllowed", view.allowedActions().retry());
        row.put("markStoppedAttemptId", "NEEDS_INSPECTION".equals(latest.state()) ? latest.attemptId() : null);
        row.put("cost", costLabel(view.usage()));
        putCostNote(row, triggeredCostNote(view, label));
        putWhen(row, latest, label);
        return row;
    }

    public Map<String, Object> detail(PilotTaskView view) {
        Map<String, Object> row = baseRow(view);
        var label = view.attempts().isEmpty() ? null : stateLabel(view);
        var latest = view.attempts().isEmpty() ? null : view.attempts().getLast();
        row.put("stateLabel", label == null ? (view.state() == PilotTaskView.TaskState.UNSUPPORTED
                ? "Not eligible" : "Not started") : label);
        row.put("stateClass", stateClass(label));
        row.put("result", resultSentence(view, label));
        row.put("chips", resultChips(view));
        row.put("retryAllowed", view.allowedActions().retry());
        row.put("lastRunUrl",
                latest == null || latest.executionId() == null ? null : "/executions/" + latest.executionId());
        putPullRequest(row, view.pullRequestUrl());
        row.put("prAction", view.pullRequestUrl() == null ? null
                : "Open p" + pullRequestLabel(view.pullRequestUrl()).substring(1));
        row.put("draftReasons", view.draftReasons());
        row.put("markStoppedAttemptId", latest != null && "NEEDS_INSPECTION".equals(latest.state())
                ? latest.attemptId() : null);
        row.put("project", view.projectKey());
        row.put("destination", destination(view.destinationRepository()));
        putUsage(row, view.usage());
        return row;
    }

    public static Map<String, Object> attemptRow(PilotTaskView.AttemptHistoryEntry attempt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", attempt.ordinal() + " · " + ("RETRY".equals(attempt.kind()) ? "retry" : "first run")
                + ("BY_KEY".equals(attempt.admittedVia()) ? " by key" : ""));
        row.put("ordinal", attempt.ordinal());
        row.put("run", PilotRunSettings.label(attempt.runSettings()));
        row.put("executionId", attempt.executionId());
        boolean draft = "PR_READY".equals(attempt.state()) && attempt.draftReasons() != null;
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
    public static Map<String, Object> runContext(PilotTaskView view, UUID executionId) {
        String label = view.attempts().stream()
                .filter(attempt -> executionId.equals(attempt.executionId()))
                .findFirst()
                .map(attempt -> attempt.draftReasons() == null ? attemptStateLabel(attempt.state()) : "Draft PR")
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
    public static Map<String, Object> queueWaiting(String key, PilotAttempt attempt) {
        Map<String, Object> waiting = new LinkedHashMap<>();
        waiting.put("key", key);
        waiting.put("taskId", attempt.getTaskId());
        waiting.put("attemptId", attempt.getId());
        return waiting;
    }

    private Map<String, Object> baseRow(PilotTaskView view) {
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

    private static String triggeredCostNote(PilotTaskView view, String label) {
        return "Queued".equals(label) ? "not started"
                : "Working".equals(label) ? "so far" : incompleteCostNote(view.usage());
    }

    /** Same notes as Triggered; a queued attempt already says "not started" in Started. */
    private static String attemptCostNote(PilotTaskView.AttemptHistoryEntry attempt) {
        return switch (attempt.state()) {
            case "RESERVED" -> null;
            case "QUEUED", "RUNNING" -> "so far";
            default -> incompleteCostNote(attempt.usage());
        };
    }

    private static void putCostNote(Map<String, Object> row, String costNote) {
        row.put("costNote", costNote);
        row.put("costNoteTitle", costNoteTitle(costNote));
    }

    private static void putWhen(Map<String, Object> row, PilotTaskView.AttemptHistoryEntry latest, String label) {
        boolean started = latest.startedAt() != null;
        row.put("when", ago(started ? latest.startedAt() : latest.createdAt()));
        row.put("whenTitle", UiFormat.format(started ? latest.startedAt() : latest.createdAt()));
        row.put("whenNote", "Working".equals(label) ? "started"
                : !started ? "triggered" : minutes(latest.activeSeconds()) + " active");
    }

    private static void putUsage(Map<String, Object> row, PilotTaskUsage usage) {
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
        if (repositoryKey == null) {
            return null;
        }
        var target = delivery.target(repositoryKey, repositories);
        return target == null || target.repository() == null ? null
                : target.repository() + " · base " + target.baseBranch();
    }
}
