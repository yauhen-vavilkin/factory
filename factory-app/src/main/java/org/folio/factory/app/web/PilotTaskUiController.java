package org.folio.factory.app.web;

import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.admission.PilotCommands;
import org.folio.factory.app.pilot.admission.PilotModeService;
import org.folio.factory.app.pilot.admission.PilotRetryService;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.presentation.PilotTaskQuery;
import org.folio.factory.app.pilot.presentation.PilotTaskUsage;
import org.folio.factory.app.pilot.presentation.PilotTaskView;
import org.folio.factory.app.pilot.source.PilotSourceProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Server-rendered pilot task screens: a task list with selection, admission-mode
 * and Run-selected controls, plus a per-task detail page with result, costs and
 * attempt history. POST routes delegate to the pilot admission services and
 * redirect; every submission is revalidated by its service against the
 * server-rendered version and fingerprints.
 */
@Controller
public class PilotTaskUiController {

    private static final int DEFAULT_SIZE = 20;

    private final PilotTaskQuery taskQuery;
    private final PilotInstallationRepository installations;
    private final PilotAdmissionService admissions;
    private final PilotModeService modes;
    private final PilotRetryService retries;
    private final PilotSourceProperties source;

    public PilotTaskUiController(PilotTaskQuery taskQuery, PilotInstallationRepository installations,
                                 PilotAdmissionService admissions, PilotModeService modes,
                                 PilotRetryService retries, PilotSourceProperties source) {
        this.taskQuery = taskQuery;
        this.installations = installations;
        this.admissions = admissions;
        this.modes = modes;
        this.retries = retries;
        this.source = source;
    }

    @GetMapping("/tasks")
    public String tasks(@RequestParam(name = "page", defaultValue = "0") int page,
                        @RequestParam(name = "size", defaultValue = "20") int size,
                        @RequestParam(name = "error", required = false) String error,
                        Model model) {
        int clampedSize = PageValidation.clampSize(size, DEFAULT_SIZE);
        Page<PilotTaskView> result = taskQuery.list(PageRequest.of(Math.max(page, 0), clampedSize));
        PilotInstallation installation = installation();
        model.addAttribute("tasks", result.getContent().stream().map(this::taskRow).toList());
        model.addAttribute("page", result);
        model.addAttribute("baseUrl", "/tasks?size=" + clampedSize);
        model.addAttribute("error", error);
        addInstallation(model, installation, result.getTotalElements());
        return "pilot-tasks";
    }

    @GetMapping("/tasks/{taskId}")
    public String task(@PathVariable("taskId") UUID taskId,
                       @RequestParam(name = "error", required = false) String error, Model model) {
        PilotTaskView view = taskQuery.detail(taskId);
        model.addAttribute("task", detailMap(view));
        model.addAttribute("attempts", view.attempts().stream().map(this::attemptRow).toList());
        model.addAttribute("error", error);
        return "pilot-task";
    }

    /** Baseline for the list page's live refresh; a read-only projection, never an action. */
    @GetMapping("/tasks/poll")
    @ResponseBody
    public Map<String, Object> poll() {
        PilotInstallation installation = installation();
        long taskCount = taskQuery.list(PageRequest.of(0, 1)).getTotalElements();
        return Map.of(
                "mode", installation.getMode().name(),
                "taskCount", taskCount,
                "revision", revision(installation, taskCount));
    }

    @PostMapping("/tasks/run-selected")
    public String runSelected(@RequestParam(name = "taskId", required = false) List<String> taskIds,
                              @RequestParam Map<String, String> allParams,
                              RedirectAttributes redirect) {
        try {
            List<PilotCommands.Selection> selections = selections(taskIds, allParams);
            if (selections.isEmpty()) {
                return UiFormat.errorRedirect("/tasks", "Select at least one task, then choose Run selected.");
            }
            PilotAdmissionService.AdmissionResult result = admissions.runSelected(new PilotCommands.RunSelected(
                    "ui-" + UUID.randomUUID(), allParams.get("expectedAuthorityFingerprint"), selections));
            if (!result.admittedTaskIds().isEmpty()) {
                redirect.addFlashAttribute("message", "Admitted " + result.admittedTaskIds().size()
                        + " task(s) to the pilot queue.");
            }
            if (!result.rejectedReasons().isEmpty()) {
                return UiFormat.errorRedirect("/tasks", rejectedExplanation(result.rejectedReasons()));
            }
            return "redirect:/tasks";
        } catch (NumberFormatException e) {
            return UiFormat.errorRedirect("/tasks",
                    "The submitted selection was malformed; reload the task list and try again.");
        } catch (IllegalArgumentException e) {
            return UiFormat.errorRedirect("/tasks", e.getMessage());
        }
    }

    @PostMapping("/tasks/mode")
    public String mode(@RequestParam(name = "mode", required = false) String mode,
                       @RequestParam(name = "expectedAuthorityFingerprint", required = false) String fingerprint,
                       RedirectAttributes redirect) {
        try {
            PilotModeService.ModeResult result =
                    modes.setMode(UiFormat.enumOrNull(PilotModeService.AdmissionMode.class, mode), fingerprint);
            redirect.addFlashAttribute("message", result.explanation());
            return "redirect:/tasks";
        } catch (IllegalArgumentException e) {
            return UiFormat.errorRedirect("/tasks", e.getMessage());
        }
    }

    @PostMapping("/tasks/{taskId}/retry")
    public String retry(@PathVariable("taskId") UUID taskId,
                        @RequestParam(name = "expectedVersion", defaultValue = "0") long expectedVersion,
                        @RequestParam(name = "inputFingerprint", required = false) String inputFingerprint,
                        @RequestParam(name = "destinationFingerprint", required = false) String destinationFingerprint,
                        RedirectAttributes redirect) {
        PilotRetryService.RetryResult result = retries.retry(new PilotCommands.Retry(
                "ui-" + UUID.randomUUID(), taskId, expectedVersion, inputFingerprint, destinationFingerprint));
        if (isLive(result.state())) {
            redirect.addFlashAttribute("message", "Retry admitted: " + result.explanation());
            return "redirect:/tasks/" + taskId;
        }
        return UiFormat.errorRedirect("/tasks/" + taskId, result.explanation());
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

    private void addInstallation(Model model, PilotInstallation installation, long taskCount) {
        String observed = installation.getObservedAuthorityFingerprint();
        model.addAttribute("modeValue", installation.getMode().name());
        model.addAttribute("modeLabel",
                installation.getMode() == PilotInstallation.Mode.AUTOMATIC ? "Automatic" : "Review before run");
        model.addAttribute("syncComplete", installation.isSyncComplete());
        model.addAttribute("syncProblem", installation.getSyncProblem());
        model.addAttribute("lastSync", UiFormat.format(installation.getLastSuccessfulSyncAt()));
        model.addAttribute("lastSyncRelative", relative(installation.getLastSuccessfulSyncAt()));
        model.addAttribute("sourceName", installation.getSourceName());
        model.addAttribute("sourceUrl", installation.getSourceUrl());
        model.addAttribute("sprintName", installation.getSprintName());
        model.addAttribute("scopeStatuses", joined(source.statuses()));
        model.addAttribute("scopeRestrictions", restrictions(source));
        model.addAttribute("enableScopeContext", enableScopeContext(installation, source));
        model.addAttribute("authorityFingerprint", observed);
        model.addAttribute("automaticAdmissionAvailable",
                observed != null && installation.isSyncComplete() && installation.getSyncProblem() == null);
        model.addAttribute("pollUrl", "/tasks/poll");
        model.addAttribute("pollFields", "revision");
        model.addAttribute("pollBaseline", revision(installation, taskCount));
    }

    private static String joined(List<String> values) {
        return values.isEmpty() ? null : String.join(", ", values);
    }

    /** The configured AND narrowings over the inherited team filter, in plain language. */
    private static String restrictions(PilotSourceProperties source) {
        List<String> parts = new ArrayList<>();
        if (source.assignee() != null) {
            parts.add("assignee " + source.assignee());
        }
        if (!source.projects().isEmpty()) {
            parts.add("projects " + String.join(", ", source.projects()));
        }
        if (source.label() != null) {
            parts.add("label " + source.label());
        }
        return parts.isEmpty() ? null : "Only: " + String.join(", ", parts);
    }

    /** What automatic admission would delegate: the resolved source, sprint and statuses. */
    private static String enableScopeContext(PilotInstallation installation, PilotSourceProperties source) {
        List<String> parts = new ArrayList<>();
        if (installation.getSourceName() != null) {
            parts.add(installation.getSourceName());
        }
        if (installation.getSprintName() != null) {
            parts.add("sprint " + installation.getSprintName());
        }
        if (!source.statuses().isEmpty()) {
            parts.add("statuses " + String.join(", ", source.statuses()));
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** Compact installation identity for live refresh; comma-free so poll.js can split fields safely. */
    private static String revision(PilotInstallation installation, long taskCount) {
        String problem = installation.getSyncProblem() == null ? "none" : "present";
        return installation.getMode() + "|" + installation.isSyncComplete() + "|" + problem
                + "|" + installation.getUpdatedAt() + "|" + taskCount;
    }

    private Map<String, Object> taskRow(PilotTaskView view) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", view.taskId());
        row.put("key", view.displayKey());
        row.put("jiraUrl", view.jiraUrl());
        row.put("title", view.title());
        row.put("state", view.state().name());
        row.put("stateLabel", stateLabel(view));
        row.put("stageLabel", stageLabel(view.stage()));
        row.put("reason", view.reason());
        row.put("destination", view.destinationRepository() == null ? "—" : view.destinationRepository());
        row.put("prUrl", view.pullRequestUrl());
        row.put("cost", costLabel(view.usage()));
        row.put("costNote", costNote(view.usage()));
        row.put("updated", relative(view.updatedAt()));
        row.put("updatedTitle", UiFormat.format(view.updatedAt()));
        row.put("outScope", !view.inScope());
        row.put("selectable", view.allowedActions().select());
        row.put("version", view.version());
        row.put("inputFingerprint", view.inputFingerprint());
        row.put("destinationFingerprint", view.destinationFingerprint());
        return row;
    }

    private Map<String, Object> detailMap(PilotTaskView view) {
        Map<String, Object> row = taskRow(view);
        PilotTaskUsage usage = view.usage();
        row.put("project", view.projectKey());
        row.put("created", UiFormat.format(view.jiraCreatedAt()));
        row.put("usagePresent", usage.tokens() > 0 || !usage.estimatesByCurrency().isEmpty());
        row.put("usageTokens", UiFormat.count(usage.tokens()));
        row.put("usageCategories", usage.tokensByCategory().entrySet().stream()
                .map(entry -> Map.of("label", categoryLabel(entry.getKey()),
                        "value", UiFormat.count(entry.getValue())))
                .toList());
        row.put("usageEstimates", usage.estimatesByCurrency().entrySet().stream()
                .map(entry -> Map.of("amount", plain(entry.getValue()), "currency", entry.getKey()))
                .toList());
        row.put("usageModels", usage.models());
        row.put("usageNote", usageNote(usage));
        row.put("retryAllowed", view.allowedActions().retry());
        row.put("retryNote", retryNote(view));
        return row;
    }

    private Map<String, Object> attemptRow(PilotTaskView.AttemptHistoryEntry attempt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ordinal", attempt.ordinal());
        row.put("kind", "RETRY".equals(attempt.kind()) ? "Retry" : "Initial attempt");
        row.put("admittedVia", "AUTOMATIC".equals(attempt.admittedVia()) ? "automatic" : "manual selection");
        row.put("state", attempt.state());
        row.put("stateLabel", attemptStateLabel(attempt.state()));
        row.put("reason", attempt.reason());
        row.put("prUrl", attempt.pullRequestUrl());
        row.put("executionId", attempt.executionId());
        row.put("executionShort", attempt.executionId() == null
                ? null : UiFormat.abbreviate(attempt.executionId().toString()));
        row.put("started", relative(attempt.startedAt()));
        row.put("startedTitle", UiFormat.format(attempt.startedAt()));
        row.put("deadline", relative(attempt.deadlineAt()));
        row.put("active", attempt.paidStart() ? UiFormat.duration(attempt.activeSeconds()) : null);
        row.put("cost", costLabel(attempt.usage()));
        row.put("costNote", costNote(attempt.usage()));
        return row;
    }

    private static String stateLabel(PilotTaskView view) {
        if (view.state() == PilotTaskView.TaskState.WORKING) {
            return queued(view) ? "Queued" : "Working";
        }
        return switch (view.state()) {
            case AWAITING_SELECTION -> "Awaiting selection";
            case WITHDRAWN -> "Withdrawn";
            case UNSUPPORTED -> "Not eligible";
            case STOPPED -> "Could not complete";
            case UNCERTAIN -> "Needs inspection";
            case PROCESSING_PAUSED -> "Paused";
            case PR_READY -> "PR ready";
            case WORKING -> "Working";
        };
    }

    private static boolean queued(PilotTaskView view) {
        if (view.attempts().isEmpty()) {
            return false;
        }
        String latest = view.attempts().getLast().state();
        return "RESERVED".equals(latest) || "QUEUED".equals(latest);
    }

    /**
     * Maps the two raw stage vocabularies — dev-factory step ids and the terminal
     * result-state prefixes the outcome service composes — to the spec's four
     * stage labels. Stop states and unknown values show no stage.
     */
    private static String stageLabel(String stage) {
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

    private static String attemptStateLabel(String state) {
        return switch (state) {
            case "RESERVED" -> "Reserved";
            case "QUEUED" -> "Queued";
            case "RUNNING" -> "Running";
            case "STOPPED" -> "Could not complete";
            case "PR_READY" -> "PR ready";
            case "NEEDS_INSPECTION" -> "Needs inspection";
            case "WITHDRAWN" -> "Withdrawn";
            default -> state;
        };
    }

    private static String retryNote(PilotTaskView view) {
        if (view.allowedActions().retry()) {
            return null;
        }
        return switch (view.state()) {
            case PR_READY -> "Retry is unavailable: the delivered pull request is under human review.";
            case WORKING -> "Retry is unavailable while an attempt is queued or running.";
            case UNCERTAIN, PROCESSING_PAUSED -> "Retry is unavailable until the held attempt is resolved.";
            case AWAITING_SELECTION -> "This task has not been attempted yet; select it on the task list.";
            case UNSUPPORTED -> "Retry is unavailable: no repository mapping supports this task.";
            case WITHDRAWN -> "The admission was withdrawn before start; select the task again.";
            case STOPPED -> "Retry is unavailable because the task no longer matches the pilot filter"
                    + " or destination.";
        };
    }

    private static String costLabel(PilotTaskUsage usage) {
        if (usage.estimatesByCurrency().isEmpty()) {
            return null;
        }
        return usage.estimatesByCurrency().entrySet().stream()
                .map(entry -> plain(entry.getValue()) + " " + entry.getKey())
                .collect(Collectors.joining(" + "));
    }

    private static String costNote(PilotTaskUsage usage) {
        var notes = new ArrayList<String>();
        if (!usage.complete()) {
            notes.add("incomplete");
        }
        if (usage.provisional()) {
            notes.add("provisional");
        }
        return notes.isEmpty() ? null : String.join(", ", notes) + " estimate";
    }

    private static String usageNote(PilotTaskUsage usage) {
        var notes = new ArrayList<String>();
        if (!usage.complete()) {
            notes.add("Totals are incomplete: some usage has no reported tokens or rate yet.");
        }
        if (usage.provisional()) {
            notes.add("Includes live usage that can still change.");
        }
        return notes.isEmpty() ? null : String.join(" ", notes);
    }

    private static String categoryLabel(Object category) {
        return switch (category.toString()) {
            case "INPUT" -> "Input";
            case "CACHE_READ" -> "Cache read";
            case "CACHE_WRITE" -> "Cache write";
            case "OUTPUT" -> "Output";
            default -> category.toString();
        };
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String relative(Instant instant) {
        if (instant == null) {
            return null;
        }
        long seconds = Duration.between(instant, Instant.now()).getSeconds();
        return seconds >= 0 ? UiFormat.duration(seconds) + " ago" : "in " + UiFormat.duration(-seconds);
    }
}
