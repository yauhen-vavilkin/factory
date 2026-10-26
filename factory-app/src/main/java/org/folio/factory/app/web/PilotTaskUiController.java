package org.folio.factory.app.web;

import jakarta.servlet.http.HttpServletRequest;
import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.admission.PilotCommands;
import org.folio.factory.app.pilot.admission.PilotRetryService;
import org.folio.factory.app.pilot.execution.PilotDispatcher;
import org.folio.factory.app.pilot.execution.PilotOutcomeService;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.presentation.PilotTaskQuery;
import org.folio.factory.app.pilot.presentation.PilotTaskUsage;
import org.folio.factory.app.pilot.presentation.PilotTaskView;
import org.folio.factory.app.pilot.source.PilotDiscoveryService;
import org.folio.factory.app.pilot.source.PilotJiraInstance;
import org.folio.factory.app.pilot.source.PilotSourceProperties;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * My tasks on the Developer Flow page and the task summary. The list shows the
 * developer's own Jira tasks (Available) and everything already started
 * (Triggered); POST routes delegate to the pilot services, which revalidate every
 * submission against the server-rendered version and fingerprints, and redirect
 * back. The app has no login, so every POST also refuses requests that a page on
 * another site could send.
 */
@Controller
public class PilotTaskUiController {

    static final String FLOW_ID = "dev-factory";
    private static final String FLOW_PAGE = "/flows/" + FLOW_ID;
    private static final int TRIGGERED_PAGE_SIZE = 20;
    private static final Set<String> DEFAULT_STATUSES = Set.of("open", "in progress");
    private static final Pattern PULL_NUMBER = Pattern.compile("/pull/(\\d+)");
    private static final Pattern LEADING_STAGE =
            Pattern.compile("^(?:shared: )?([A-Za-z][A-Za-z_-]*): (.+)$", Pattern.DOTALL);
    private static final DateTimeFormatter STARTED =
            DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US).withZone(ZoneOffset.UTC);

    private final PilotTaskQuery taskQuery;
    private final PilotInstallationRepository installations;
    private final PilotAttemptRepository attempts;
    private final PilotAdmissionService admissions;
    private final PilotRetryService retries;
    private final PilotDiscoveryService discovery;
    private final PilotDispatcher dispatcher;
    private final ObjectProvider<PilotOutcomeService> outcomes;
    private final PilotSourceProperties source;
    private final PilotJiraInstance jira;
    private final FlowRegistry flows;
    private final DevFactoryProperties repositories;
    private final DevDeliveryProperties delivery;

    public PilotTaskUiController(PilotTaskQuery taskQuery, PilotInstallationRepository installations,
                                 PilotAttemptRepository attempts, PilotAdmissionService admissions,
                                 PilotRetryService retries, PilotDiscoveryService discovery,
                                 PilotDispatcher dispatcher, ObjectProvider<PilotOutcomeService> outcomes,
                                 PilotSourceProperties source, PilotJiraInstance jira, FlowRegistry flows,
                                 DevFactoryProperties repositories, DevDeliveryProperties delivery) {
        this.taskQuery = taskQuery;
        this.installations = installations;
        this.attempts = attempts;
        this.admissions = admissions;
        this.retries = retries;
        this.discovery = discovery;
        this.dispatcher = dispatcher;
        this.outcomes = outcomes;
        this.source = source;
        this.jira = jira;
        this.flows = flows;
        this.repositories = repositories;
        this.delivery = delivery;
    }

    /** The Developer Flow page, tab My tasks; {@code ?view=definition} is served by {@link FlowUiController}. */
    @GetMapping(value = FLOW_PAGE, params = "!view")
    public String myTasks(@RequestParam(name = "page", defaultValue = "0") int page,
                          @RequestParam(name = "error", required = false) String error,
                          Model model) {
        FlowDescriptor descriptor = flows.find(FLOW_ID)
                .orElseThrow(() -> new NoSuchElementException("No registered flow with id '" + FLOW_ID + "'"));
        // The live-refresh baseline is read first: a change landing while the page is
        // built then differs from it and reloads the page, instead of being absorbed.
        PilotInstallation installation = installation();
        String pollBaseline = revision(installation);
        PilotTaskQuery.MyTasks myTasks = taskQuery.myTasks(PageRequest.of(Math.max(page, 0), TRIGGERED_PAGE_SIZE));
        long queuedCount = attempts.countByStateIn(List.of(PilotAttempt.State.RESERVED));
        List<Map<String, Object>> available = myTasks.available().stream().map(this::availableRow).toList();
        Page<PilotTaskView> triggered = myTasks.triggered();

        model.addAttribute("flow", Map.of("id", descriptor.id(), "name", descriptor.name(),
                "version", descriptor.version()));
        model.addAttribute("source", sourceCard(installation));
        model.addAttribute("repositoryMap", repositoryMap());
        List<Map<String, Object>> chips = statusChips(available);
        Set<Object> shownStatuses = chips.stream().filter(chip -> (boolean) chip.get("pressed"))
                .map(chip -> chip.get("status")).collect(Collectors.toSet());
        available.forEach(row -> row.put("shown", shownStatuses.contains(row.get("jiraStatus"))));
        model.addAttribute("available", available);
        model.addAttribute("availableShown", available.stream().filter(row -> (boolean) row.get("shown")).count());
        model.addAttribute("statusChips", chips);
        model.addAttribute("triggered", triggered.getContent().stream().map(this::triggeredRow).toList());
        model.addAttribute("page", triggered);
        model.addAttribute("baseUrl", FLOW_PAGE + "?");
        model.addAttribute("workingCount",
                attempts.countByStateIn(List.of(PilotAttempt.State.QUEUED, PilotAttempt.State.RUNNING)));
        model.addAttribute("queuedCount", queuedCount);
        model.addAttribute("queueWaiting", queueWaiting(installation, triggered.getContent()));
        model.addAttribute("queueProblem", queuedCount > 0 ? dispatcher.queueProblem() : null);
        // A row with Mark as stopped… needs the wider status column so the inline control stays on one line.
        model.addAttribute("triggeredWide", triggered.getContent().stream().anyMatch(view ->
                !view.attempts().isEmpty() && "NEEDS_INSPECTION".equals(view.attempts().getLast().state())));
        model.addAttribute("error", error);
        model.addAttribute("pollUrl", "/tasks/poll");
        model.addAttribute("pollFields", "revision");
        model.addAttribute("pollBaseline", pollBaseline);
        return "flow-my-tasks";
    }

    @GetMapping("/tasks")
    public String tasks() {
        return "redirect:" + FLOW_PAGE;
    }

    @GetMapping("/tasks/{taskId}")
    public String task(@PathVariable("taskId") UUID taskId,
                       @RequestParam(name = "error", required = false) String error, Model model) {
        PilotTaskView view = taskQuery.detail(taskId);
        model.addAttribute("task", detailMap(view));
        model.addAttribute("attempts", view.attempts().reversed().stream().map(this::attemptRow).toList());
        model.addAttribute("error", error);
        return "pilot-task";
    }

    /** Baseline for the page's live refresh; a read-only projection, never an action. */
    @GetMapping("/tasks/poll")
    @ResponseBody
    public Map<String, Object> poll() {
        return Map.of("revision", revision(installation()));
    }

    @PostMapping(FLOW_PAGE + "/scan")
    public String scan(HttpServletRequest request, RedirectAttributes redirect) {
        requireSameOrigin(request);
        PilotDiscoveryService.SyncResult result = discovery.sync();
        if (!result.complete()) {
            return UiFormat.errorRedirect(FLOW_PAGE, "Scan failed: " + result.problem());
        }
        redirect.addFlashAttribute("message", "Scan complete: " + result.discoveredCount()
                + (result.discoveredCount() == 1 ? " task is" : " tasks are") + " assigned to you.");
        return "redirect:" + FLOW_PAGE;
    }

    @PostMapping("/tasks/run-selected")
    public String runSelected(@RequestParam(name = "taskId", required = false) List<String> taskIds,
                              @RequestParam Map<String, String> allParams,
                              HttpServletRequest request, RedirectAttributes redirect) {
        requireSameOrigin(request);
        try {
            List<PilotCommands.Selection> selections = selections(taskIds, allParams);
            if (selections.isEmpty()) {
                return UiFormat.errorRedirect(FLOW_PAGE, "Select at least one task, then choose Run.");
            }
            PilotAdmissionService.AdmissionResult result = admissions.runSelected(
                    new PilotCommands.RunSelected("ui-" + UUID.randomUUID(), selections));
            if (!result.admittedTaskIds().isEmpty()) {
                redirect.addFlashAttribute("message", "Queued " + result.admittedTaskIds().size()
                        + (result.admittedTaskIds().size() == 1 ? " task." : " tasks."));
            }
            if (!result.rejectedReasons().isEmpty()) {
                return UiFormat.errorRedirect(FLOW_PAGE, rejectedExplanation(result.rejectedReasons()));
            }
            return "redirect:" + FLOW_PAGE;
        } catch (NumberFormatException e) {
            return UiFormat.errorRedirect(FLOW_PAGE,
                    "The submitted selection was malformed; reload the page and try again.");
        } catch (IllegalArgumentException e) {
            return UiFormat.errorRedirect(FLOW_PAGE, e.getMessage());
        }
    }

    @PostMapping(FLOW_PAGE + "/run-by-key")
    public String runByKey(@RequestParam(name = "key", required = false) String key,
                           HttpServletRequest request, RedirectAttributes redirect) {
        requireSameOrigin(request);
        PilotAdmissionService.KeyResult result = admissions.runByKey("ui-" + UUID.randomUUID(), key);
        if (result.problem() != null) {
            return UiFormat.errorRedirect(FLOW_PAGE, result.problem());
        }
        redirect.addFlashAttribute("message", result.issueKey() + " joined the queue.");
        return "redirect:" + FLOW_PAGE;
    }

    @PostMapping("/tasks/{taskId}/retry")
    public String retry(@PathVariable("taskId") UUID taskId,
                        @RequestParam(name = "expectedVersion", defaultValue = "0") long expectedVersion,
                        @RequestParam(name = "inputFingerprint", required = false) String inputFingerprint,
                        @RequestParam(name = "destinationFingerprint", required = false) String destinationFingerprint,
                        @RequestParam(name = "returnTo", required = false) String returnTo,
                        HttpServletRequest request, RedirectAttributes redirect) {
        requireSameOrigin(request);
        String back = "flow".equals(returnTo) ? FLOW_PAGE : "/tasks/" + taskId;
        PilotRetryService.RetryResult result = retries.retry(new PilotCommands.Retry(
                "ui-" + UUID.randomUUID(), taskId, expectedVersion, inputFingerprint, destinationFingerprint));
        if (isLive(result.state())) {
            redirect.addFlashAttribute("message", "Retry queued: " + result.explanation());
            return "redirect:" + back;
        }
        return UiFormat.errorRedirect(back, result.explanation());
    }

    @PostMapping("/tasks/{taskId}/attempts/{attemptId}/mark-stopped")
    public String markStopped(@PathVariable("taskId") UUID taskId, @PathVariable("attemptId") UUID attemptId,
                              @RequestParam(name = "returnTo", required = false) String returnTo,
                              HttpServletRequest request, RedirectAttributes redirect) {
        requireSameOrigin(request);
        String back = "flow".equals(returnTo) ? FLOW_PAGE : "/tasks/" + taskId;
        PilotOutcomeService outcomeService = outcomes.getIfAvailable();
        if (outcomeService == null) {
            return UiFormat.errorRedirect(back, "Pilot execution is disabled; set FACTORY_PILOT_EXECUTION_ENABLED=true.");
        }
        String refusal;
        try {
            refusal = outcomeService.markStopped(attemptId);
        } catch (RuntimeException e) {
            refusal = "The task changed at the same moment; reload and try again.";
        }
        if (refusal != null) {
            return UiFormat.errorRedirect(back, refusal);
        }
        dispatcher.dispatchNextQuietly();
        redirect.addFlashAttribute("message", "Marked as stopped; the queue continues.");
        return "redirect:" + back;
    }

    /**
     * Refuses form posts sent by a page on another site: browsers mark them with
     * Sec-Fetch-Site or Origin. Requests without either header (curl, scripts)
     * are not cross-site browser submissions and pass.
     */
    static void requireSameOrigin(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null) {
            if (!"same-origin".equals(site) && !"none".equals(site)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-site request refused");
            }
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.equalsIgnoreCase(request.getScheme() + "://" + host(request))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-site request refused");
        }
    }

    private static String host(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host != null) {
            return host;
        }
        int port = request.getServerPort();
        boolean defaultPort = port <= 0 || port == ("https".equalsIgnoreCase(request.getScheme()) ? 443 : 80);
        return request.getServerName() + (defaultPort ? "" : ":" + port);
    }

    private static boolean isLive(String state) {
        return "RESERVED".equals(state) || "QUEUED".equals(state) || "RUNNING".equals(state);
    }

    private static List<PilotCommands.Selection> selections(List<String> taskIds, Map<String, String> allParams) {
        if (taskIds == null) {
            return List.of();
        }
        var selections = new ArrayList<PilotCommands.Selection>(taskIds.size());
        for (String id : taskIds) {
            selections.add(new PilotCommands.Selection(UUID.fromString(id),
                    Long.parseLong(allParams.getOrDefault("version-" + id, "0")),
                    allParams.get("input-" + id),
                    allParams.get("destination-" + id)));
        }
        return selections;
    }

    private String rejectedExplanation(Map<UUID, String> rejectedReasons) {
        return rejectedReasons.entrySet().stream()
                .map(entry -> taskLabel(entry.getKey()) + ": " + entry.getValue())
                .collect(Collectors.joining("; "));
    }

    private String taskLabel(UUID taskId) {
        try {
            return taskQuery.detail(taskId).displayKey();
        } catch (NoSuchElementException e) {
            return UiFormat.abbreviate(taskId.toString());
        }
    }

    private PilotInstallation installation() {
        return installations.findById(1).orElseGet(PilotInstallation::initial);
    }

    private Map<String, Object> sourceCard(PilotInstallation installation) {
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
    private String repositoryMap() {
        List<String> parts = new ArrayList<>();
        repositories.repositories().forEach((key, repository) -> repository.jiraProjects()
                .forEach(project -> parts.add(project + " → " + repositoryName(key))));
        return parts.size() + (parts.size() == 1 ? " Jira project" : " Jira projects") + " configured: "
                + String.join(" · ", parts);
    }

    private List<Map<String, Object>> statusChips(List<Map<String, Object>> available) {
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

    private static int chipOrder(String status) {
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "open" -> 0;
            case "in progress" -> 1;
            default -> 2;
        };
    }

    private Map<String, Object> queueWaiting(PilotInstallation installation, List<PilotTaskView> triggered) {
        UUID active = installation.getActiveAttemptId();
        if (active == null) {
            return null;
        }
        return attempts.findById(active)
                .filter(attempt -> attempt.getState() == PilotAttempt.State.NEEDS_INSPECTION)
                .map(attempt -> {
                    String key = triggered.stream()
                            .filter(view -> view.taskId().equals(attempt.getTaskId()))
                            .map(PilotTaskView::displayKey)
                            .findFirst()
                            .orElseGet(() -> taskLabel(attempt.getTaskId()));
                    Map<String, Object> waiting = new LinkedHashMap<>();
                    waiting.put("key", key);
                    waiting.put("taskId", attempt.getTaskId());
                    waiting.put("attemptId", attempt.getId());
                    return waiting;
                })
                .orElse(null);
    }

    /** Compact identity for live refresh; comma-free so poll.js can split fields safely. */
    private String revision(PilotInstallation installation) {
        String problem = installation.getSyncProblem() == null ? "none" : "present";
        return installation.isSyncComplete() + "|" + problem + "|" + installation.getUpdatedAt()
                + "|" + attempts.findLatestUpdate().map(Instant::toString).orElse("none")
                + "|" + attempts.count() + "|" + (dispatcher.queueProblem() == null ? "moving" : "stuck");
    }

    private Map<String, Object> baseRow(PilotTaskView view) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", view.taskId());
        row.put("key", view.displayKey());
        row.put("jiraUrl", view.jiraUrl());
        row.put("title", view.title());
        row.put("repository", view.destinationRepository() == null ? null : repositoryName(view.destinationRepository()));
        row.put("version", view.version());
        row.put("inputFingerprint", view.inputFingerprint());
        row.put("destinationFingerprint", view.destinationFingerprint());
        return row;
    }

    private Map<String, Object> availableRow(PilotTaskView view) {
        Map<String, Object> row = baseRow(view);
        row.put("jiraStatus", view.jiraStatus() == null ? "Unknown" : view.jiraStatus());
        row.put("selectable", view.allowedActions().select());
        row.put("notEligibleReason", view.state() == PilotTaskView.TaskState.UNSUPPORTED ? view.reason() : null);
        return row;
    }

    private Map<String, Object> triggeredRow(PilotTaskView view) {
        Map<String, Object> row = baseRow(view);
        PilotTaskView.AttemptHistoryEntry latest = view.attempts().getLast();
        String label = stateLabel(view);
        row.put("stateLabel", label);
        row.put("stateClass", stateClass(label));
        // A task added by key stays marked after a Retry: it is still not one of My tasks.
        row.put("addedByKey", !view.inScope() && view.attempts().stream()
                .anyMatch(attempt -> "BY_KEY".equals(attempt.admittedVia())));
        row.put("link", "Working".equals(label) && latest.executionId() != null
                ? "/executions/" + latest.executionId() : "/tasks/" + view.taskId());
        row.put("detail", triggeredDetail(view, label));
        row.put("prUrl", view.pullRequestUrl());
        row.put("prLabel", view.pullRequestUrl() == null ? null : pullRequestLabel(view.pullRequestUrl()));
        row.put("retryAllowed", view.allowedActions().retry());
        row.put("markStoppedAttemptId", "NEEDS_INSPECTION".equals(latest.state()) ? latest.attemptId() : null);
        row.put("cost", costLabel(view.usage()));
        row.put("costNote", "Queued".equals(label) ? "not started"
                : "Working".equals(label) ? "so far" : !view.usage().complete() ? "incomplete" : null);
        boolean started = latest.startedAt() != null;
        row.put("when", ago(started ? latest.startedAt() : latest.createdAt()));
        row.put("whenTitle", UiFormat.format(started ? latest.startedAt() : latest.createdAt()));
        row.put("whenNote", "Working".equals(label) ? "started"
                : !started ? "triggered" : minutes(latest.activeSeconds()) + " active");
        return row;
    }

    private static String triggeredDetail(PilotTaskView view, String label) {
        return switch (label) {
            case "Working" -> joinNonNull(stageLabel(view.stage()), view.stageStartedAt() == null ? null
                    : minutes(Duration.between(view.stageStartedAt(), Instant.now()).getSeconds()));
            case "Queued" -> view.queuePosition() == null || view.queuePosition() == 1
                    ? "next in line" : ordinal(view.queuePosition()) + " in line";
            case "PR ready" -> null;
            case "Needs inspection" -> "outcome not confirmed";
            default -> failureText(view.reason());
        };
    }

    private Map<String, Object> detailMap(PilotTaskView view) {
        Map<String, Object> row = baseRow(view);
        PilotTaskUsage usage = view.usage();
        String label = view.attempts().isEmpty() ? null : stateLabel(view);
        PilotTaskView.AttemptHistoryEntry latest = view.attempts().isEmpty() ? null : view.attempts().getLast();
        row.put("stateLabel", label == null ? (view.state() == PilotTaskView.TaskState.UNSUPPORTED
                ? "Not eligible" : "Not started") : label);
        row.put("stateClass", stateClass(label));
        row.put("result", resultSentence(view, label));
        row.put("chips", resultChips(view));
        row.put("retryAllowed", view.allowedActions().retry());
        row.put("lastRunUrl", latest == null || latest.executionId() == null ? null : "/executions/" + latest.executionId());
        row.put("prUrl", view.pullRequestUrl());
        row.put("prLabel", view.pullRequestUrl() == null ? null : pullRequestLabel(view.pullRequestUrl()));
        row.put("prAction", view.pullRequestUrl() == null ? null
                : "Open p" + pullRequestLabel(view.pullRequestUrl()).substring(1));
        row.put("markStoppedAttemptId", latest != null && "NEEDS_INSPECTION".equals(latest.state())
                ? latest.attemptId() : null);
        row.put("project", view.projectKey());
        row.put("destination", destination(view.destinationRepository()));
        row.put("usagePresent", usage.tokens() > 0 || !usage.estimatesByCurrency().isEmpty());
        row.put("usageTokens", UiFormat.count(usage.tokens()));
        row.put("usageCategories", usage.tokensByCategory().entrySet().stream()
                .map(entry -> Map.of("label", categoryLabel(entry.getKey()),
                        "value", UiFormat.count(entry.getValue())))
                .toList());
        row.put("usageCost", costLabel(usage));
        row.put("usageNote", usageNote(usage));
        return row;
    }

    private Map<String, Object> attemptRow(PilotTaskView.AttemptHistoryEntry attempt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", attempt.ordinal() + " · " + ("RETRY".equals(attempt.kind()) ? "retry" : "first run")
                + ("BY_KEY".equals(attempt.admittedVia()) ? " by key" : ""));
        row.put("executionId", attempt.executionId());
        row.put("executionShort", attempt.executionId() == null
                ? null : attempt.executionId().toString().substring(0, 4) + "…");
        row.put("result", attemptResult(attempt));
        row.put("started", attempt.startedAt() == null ? "not started" : STARTED.format(attempt.startedAt()));
        row.put("startedTitle", UiFormat.format(attempt.startedAt()));
        row.put("active", attempt.paidStart() ? minutes(attempt.activeSeconds()) : "—");
        row.put("cost", costLabel(attempt.usage()));
        row.put("costNote", attempt.usage().complete() ? null : "incomplete");
        return row;
    }

    private static String attemptResult(PilotTaskView.AttemptHistoryEntry attempt) {
        return switch (attempt.state()) {
            case "PR_READY" -> pullRequestLabel(attempt.pullRequestUrl()) + " opened";
            case "RESERVED" -> "Queued";
            case "QUEUED", "RUNNING" -> "In progress";
            case "WITHDRAWN" -> "Withdrawn before start";
            default -> failureText(attempt.reason());
        };
    }

    private static String resultSentence(PilotTaskView view, String label) {
        if (label == null) {
            return view.state() == PilotTaskView.TaskState.UNSUPPORTED ? sentence(view.reason())
                    : "Not started yet. Select it in My tasks and press Run.";
        }
        return switch (label) {
            case "PR ready" -> pullRequestLabel(view.pullRequestUrl()) + " is ready for review.";
            case "Working" -> stageLabel(view.stage()) == null ? "In progress." : "In progress: "
                    + stageLabel(view.stage()) + ".";
            case "Queued" -> view.queuePosition() == null || view.queuePosition() == 1
                    ? "Queued: next in line." : "Queued: " + ordinal(view.queuePosition()) + " in line.";
            case "Needs inspection" -> "Factory could not confirm how the last run ended.";
            default -> view.reason() != null && view.reason().contains(PilotOutcomeService.PULL_REQUEST_MAY_EXIST)
                    ? sentence(failureText(view.reason())) + " Check the repository before you retry."
                    : sentence(failureText(view.reason())) + " No pull request was created.";
        };
    }

    private static List<String> resultChips(PilotTaskView view) {
        List<String> chips = new ArrayList<>();
        String cost = costLabel(view.usage());
        if (cost != null) {
            chips.add(cost + " total" + (view.usage().complete() ? "" : " · incomplete"));
        }
        int count = view.attempts().size();
        if (count > 0) {
            chips.add(count + (count == 1 ? " attempt" : " attempts"));
        }
        long active = view.attempts().stream().filter(PilotTaskView.AttemptHistoryEntry::paidStart)
                .mapToLong(PilotTaskView.AttemptHistoryEntry::activeSeconds).sum();
        if (active > 0) {
            chips.add(minutes(active) + " active");
        }
        if (!view.usage().models().isEmpty()) {
            chips.add(String.join(", ", view.usage().models()));
        }
        return chips;
    }

    static String stateLabel(PilotTaskView view) {
        if (view.state() == PilotTaskView.TaskState.WORKING) {
            return !view.attempts().isEmpty() && "RESERVED".equals(view.attempts().getLast().state())
                    ? "Queued" : "Working";
        }
        return switch (view.state()) {
            case AWAITING_SELECTION -> "Not started";
            case WITHDRAWN -> "Withdrawn";
            case UNSUPPORTED -> "Not eligible";
            case STOPPED -> "Could not complete";
            case UNCERTAIN, PROCESSING_PAUSED -> "Needs inspection";
            case PR_READY -> "PR ready";
            case WORKING -> "Working";
        };
    }

    /** The run page's link back to its task: key, Jira link and the state of the attempt behind this run. */
    static Map<String, Object> runContext(PilotTaskView view, UUID executionId) {
        String label = view.attempts().stream()
                .filter(attempt -> executionId.equals(attempt.executionId()))
                .findFirst()
                .map(attempt -> attemptStateLabel(attempt.state()))
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

    private static String attemptStateLabel(String state) {
        return switch (state) {
            case "RESERVED" -> "Queued";
            case "QUEUED", "RUNNING" -> "Working";
            case "PR_READY" -> "PR ready";
            case "STOPPED" -> "Could not complete";
            case "NEEDS_INSPECTION" -> "Needs inspection";
            case "WITHDRAWN" -> "Withdrawn";
            default -> state;
        };
    }

    private static String stateClass(String label) {
        if (label == null) {
            return "state-pill";
        }
        return switch (label) {
            case "PR ready" -> "state-pill state-pill-success";
            case "Could not complete" -> "state-pill state-pill-danger";
            case "Needs inspection" -> "state-pill state-pill-warning";
            default -> "state-pill";
        };
    }

    /**
     * Maps the two raw stage vocabularies — dev-factory step ids and the terminal
     * result-state prefixes the outcome service composes — to the four stage
     * labels. Stop states and unknown values show no stage.
     */
    static String stageLabel(String stage) {
        if (stage == null) {
            return null;
        }
        return switch (stage) {
            case "read-task", "select-repository", "prepare-task" -> "Preparation";
            case "implement", "clarify-implementation", "continue-implementation", "coding",
                    "DEVELOPMENT_FAILED" -> "Implementation";
            case "verify", "verification", "VERIFIED", "VERIFICATION_FAILED" -> "Verification";
            case "publish", "initialize-pr-feedback", "pr-follow-up", "DELIVERY_BLOCKED" -> "Publication";
            default -> null;
        };
    }

    /**
     * "VERIFICATION_FAILED: 2 tests in error" and "verify: 2 tests in error" read
     * as "Verification failed: 2 tests in error"; other reasons pass through.
     */
    static String failureText(String reason) {
        if (reason == null || reason.isBlank()) {
            return "No reason was recorded";
        }
        String text = reason.strip();
        String stage = null;
        Matcher prefix = LEADING_STAGE.matcher(text);
        if (prefix.matches() && (stageLabel(prefix.group(1)) != null || prefix.group(1).matches("[A-Z_]+"))) {
            stage = "VERIFIED".equals(prefix.group(1)) ? null : stageLabel(prefix.group(1));
            text = prefix.group(2).strip();
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return stage == null ? Character.toUpperCase(text.charAt(0)) + text.substring(1)
                : stage + " failed: " + text;
    }

    private static String sentence(String text) {
        if (text == null || text.isBlank()) {
            return "No reason was recorded.";
        }
        String stripped = text.strip();
        return stripped.endsWith(".") ? stripped : stripped + ".";
    }

    private static String pullRequestLabel(String url) {
        if (url == null) {
            return "Pull request";
        }
        Matcher number = PULL_NUMBER.matcher(url);
        return number.find() ? "Pull request #" + number.group(1) : "Pull request";
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
        DeliveryTarget target = delivery.target(repositoryKey, repositories);
        return target == null || target.repository() == null ? null
                : target.repository() + " · base " + target.baseBranch();
    }

    private static String costLabel(PilotTaskUsage usage) {
        if (usage.estimatesByCurrency().isEmpty()) {
            return null;
        }
        return usage.estimatesByCurrency().entrySet().stream()
                .map(entry -> "USD".equals(entry.getKey())
                        ? "$" + entry.getValue().setScale(2, RoundingMode.HALF_UP).toPlainString()
                        : plain(entry.getValue()) + " " + entry.getKey())
                .collect(Collectors.joining(" + "));
    }

    private static String usageNote(PilotTaskUsage usage) {
        var notes = new ArrayList<String>();
        if (!usage.complete()) {
            notes.add("Incomplete: some usage has no reported tokens or rate yet. Cost per attempt is in Attempts.");
        }
        if (usage.provisional()) {
            notes.add("Includes live usage that can still change.");
        }
        return notes.isEmpty() ? null : String.join(" ", notes);
    }

    private static String categoryLabel(Object category) {
        return switch (category.toString()) {
            case "INPUT" -> "Input (uncached)";
            case "CACHE_READ" -> "Cache read";
            case "CACHE_WRITE" -> "Cache write";
            case "OUTPUT" -> "Output";
            default -> category.toString();
        };
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String joinNonNull(String first, String second) {
        if (first == null) {
            return second;
        }
        return second == null ? first : first + " · " + second;
    }

    private static String ordinal(int position) {
        int lastTwo = position % 100;
        String suffix = lastTwo >= 11 && lastTwo <= 13 ? "th" : switch (position % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return position + suffix;
    }

    static String minutes(long seconds) {
        long minutes = Math.max(0, seconds) / 60;
        if (minutes < 1) {
            return "<1 min";
        }
        if (minutes < 60) {
            return minutes + " min";
        }
        return minutes / 60 + " h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " min");
    }

    static String ago(Instant instant) {
        if (instant == null) {
            return null;
        }
        long seconds = Math.max(0, Duration.between(instant, Instant.now()).getSeconds());
        if (seconds < 60) {
            return "just now";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " min ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " h ago";
        }
        long days = hours / 24;
        return days + (days == 1 ? " day ago" : " days ago");
    }
}
