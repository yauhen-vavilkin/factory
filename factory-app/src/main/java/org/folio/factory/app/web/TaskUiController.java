package org.folio.factory.app.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.admission.TaskCommands;
import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.execution.TaskDispatcher;
import org.folio.factory.app.tasks.execution.TaskOutcomeService;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.presentation.TaskQuery;
import org.folio.factory.app.tasks.presentation.TaskRows;
import org.folio.factory.app.tasks.presentation.TaskView;
import org.folio.factory.app.tasks.source.TaskDiscoveryService;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.devfactory.runtime.RunProfiles;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * My tasks on the Developer Flow page and the task summary. The list shows the
 * developer's own Jira tasks (Available) and everything already started
 * (Triggered); POST routes delegate to the task services, which revalidate every
 * submission against the server-rendered version and fingerprints, and redirect
 * back. The app has no login, so every POST also refuses requests that a page on
 * another site could send.
 */
@Controller
@RequiredArgsConstructor
public class TaskUiController {

    static final String FLOW_ID = "dev-factory";
    private static final String FLOW_PAGE = "/flows/" + FLOW_ID;
    private static final int TRIGGERED_PAGE_SIZE = 20;

    private final TaskQuery taskQuery;
    private final TaskInstallationRepository installations;
    private final TaskAttemptRepository attempts;
    private final TaskAdmissionService admissions;
    private final TaskDiscoveryService discovery;
    private final TaskDispatcher dispatcher;
    private final ObjectProvider<TaskOutcomeService> outcomes;
    private final TaskRows rows;
    private final FlowRegistry flows;
    private final TaskRunSettings runSettings;

    /** The Developer Flow page, tab My tasks; {@code ?view=definition} is served by {@link FlowUiController}. */
    @GetMapping(value = FLOW_PAGE, params = "!view")
    public String myTasks(@RequestParam(name = "page", defaultValue = "0") int page,
                          @RequestParam(name = "error", required = false) String error,
                          Model model) {
        var descriptor = flows.find(FLOW_ID)
                .orElseThrow(() -> new NoSuchElementException("No registered flow with id '" + FLOW_ID + "'"));
        // The live-refresh baseline is read first: a change landing while the page is
        // built then differs from it and reloads the page, instead of being absorbed.
        var installation = installation();
        var pollBaseline = revision(installation);
        var myTasks = taskQuery.myTasks(PageRequest.of(Math.max(page, 0), TRIGGERED_PAGE_SIZE));
        var queuedCount = attempts.countByStateIn(List.of(TaskAttempt.State.RESERVED));
        var available = myTasks.available().stream().map(rows::availableRow).toList();
        model.addAttribute("flow", Map.of("id", descriptor.id(), "name", descriptor.name(),
                "version", descriptor.version()));
        model.addAttribute("source", rows.sourceCard(installation));
        model.addAttribute("repositoryMap", rows.repositoryMap());
        addAvailable(model, available);
        addTriggered(model, installation, myTasks.triggered(), queuedCount);
        // Profile, Effort and Max turns for Run and Run by key; null with the Pi runtime.
        model.addAttribute("runForm", runSettings.form(null));
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
        var view = taskQuery.detail(taskId);
        model.addAttribute("task", rows.detail(view));
        model.addAttribute("attempts", view.attempts().stream().map(TaskRows::attemptRow).toList());
        // Retry fills the form from the previous attempt.
        var previous = view.attempts().isEmpty() ? null : view.attempts().getLast().runSettings();
        model.addAttribute("retryForm", runSettings.form(previous));
        model.addAttribute("retryInstructions", TaskRunSettings.instructions(previous));
        model.addAttribute("instructionsLimit", RunProfiles.MAX_INSTRUCTIONS);
        model.addAttribute("error", error);
        return "task";
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
        TaskDiscoveryService.SyncResult result = discovery.sync();
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
            var selections = selections(taskIds, allParams);
            if (selections.isEmpty()) {
                return UiFormat.errorRedirect(FLOW_PAGE, "Select at least one task, then choose Run.");
            }
            // One choice of Profile, Effort and Max turns applies to every selected task.
            var settings = runSettings.snapshot(allParams.get("profileId"), allParams.get("effort"),
                    allParams.get("maxTurns"), null);
            var result = admissions.runSelected(
                    new TaskCommands.RunSelected("ui-" + UUID.randomUUID(), selections, settings));
            return admissionRedirect(result, redirect);
        } catch (NumberFormatException e) {
            return UiFormat.errorRedirect(FLOW_PAGE,
                    "The submitted selection was malformed; reload the page and try again.");
        } catch (IllegalArgumentException e) {
            return UiFormat.errorRedirect(FLOW_PAGE, e.getMessage());
        }
    }

    @PostMapping(FLOW_PAGE + "/run-by-key")
    public String runByKey(@RequestParam(name = "key", required = false) String key,
                           @RequestParam(name = "profileId", required = false) String profileId,
                           @RequestParam(name = "effort", required = false) String effort,
                           @RequestParam(name = "maxTurns", required = false) String maxTurns,
                           HttpServletRequest request, RedirectAttributes redirect) {
        requireSameOrigin(request);
        String settings;
        try {
            settings = runSettings.snapshot(profileId, effort, maxTurns, null);
        } catch (IllegalArgumentException e) {
            return UiFormat.errorRedirect(FLOW_PAGE, e.getMessage());
        }
        TaskAdmissionService.KeyResult result = admissions.runByKey("ui-" + UUID.randomUUID(), key, settings);
        if (result.problem() != null) {
            return UiFormat.errorRedirect(FLOW_PAGE, result.problem());
        }
        redirect.addFlashAttribute("message", result.issueKey() + " joined the queue.");
        return "redirect:" + FLOW_PAGE;
    }

    /**
     * The optional text fields (fingerprints, returnTo and the run settings) are read from the
     * form map, the same way Run reads its run settings.
     */
    @PostMapping("/tasks/{taskId}/retry")
    public String retry(@PathVariable("taskId") UUID taskId,
                        @RequestParam(name = "expectedVersion", defaultValue = "0") long expectedVersion,
                        @RequestParam Map<String, String> form,
                        HttpServletRequest request, RedirectAttributes redirect) {
        requireSameOrigin(request);
        var back = "flow".equals(form.get("returnTo")) ? FLOW_PAGE : "/tasks/" + taskId;
        String settings;
        try {
            settings = runSettings.snapshot(form.get("profileId"), form.get("effort"), form.get("maxTurns"),
                    form.get("instructions"));
        } catch (IllegalArgumentException e) {
            return UiFormat.errorRedirect(back, e.getMessage());
        }
        var result = admissions.retry(new TaskCommands.Retry("ui-" + UUID.randomUUID(), taskId, expectedVersion,
                form.get("inputFingerprint"), form.get("destinationFingerprint"), settings));
        if (!result.refused() && result.state() != null && result.state().isInFlight()) {
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
        TaskOutcomeService outcomeService = outcomes.getIfAvailable();
        if (outcomeService == null) {
            return UiFormat.errorRedirect(
                    back, "Task execution is disabled; set FACTORY_TASKS_EXECUTION_ENABLED=true.");
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

    private static void addAvailable(Model model, List<Map<String, Object>> available) {
        var chips = TaskRows.statusChips(available);
        TaskRows.markShown(available, chips);
        model.addAttribute("available", available);
        // Available counts only shown tasks that can run; poll.js applies the same rule after a filter change.
        var shownByRunnable = TaskRows.countShownByRunnable(available);
        model.addAttribute("availableShown", shownByRunnable.get(true));
        model.addAttribute("notEligibleShown", shownByRunnable.get(false));
        model.addAttribute("statusChips", chips);
    }

    private void addTriggered(Model model, TaskInstallation installation, Page<TaskView> triggered,
                              long queuedCount) {
        model.addAttribute("triggered", triggered.getContent().stream().map(rows::triggeredRow).toList());
        model.addAttribute("page", triggered);
        model.addAttribute("baseUrl", FLOW_PAGE + "?");
        model.addAttribute("workingCount",
                attempts.countByStateIn(List.of(TaskAttempt.State.QUEUED, TaskAttempt.State.RUNNING)));
        model.addAttribute("queuedCount", queuedCount);
        model.addAttribute("queueWaiting", queueWaiting(installation, triggered.getContent()));
        model.addAttribute("queueProblem", queuedCount > 0 ? dispatcher.queueProblem() : null);
        // A row with Mark as stopped… needs the wider action column so the button stays on one line.
        model.addAttribute("triggeredWide", TaskRows.anyNeedsInspection(triggered.getContent()));
    }

    private String admissionRedirect(TaskAdmissionService.AdmissionResult result, RedirectAttributes redirect) {
        if (!result.admittedTaskIds().isEmpty()) {
            redirect.addFlashAttribute("message", "Queued " + result.admittedTaskIds().size()
                    + (result.admittedTaskIds().size() == 1 ? " task." : " tasks."));
        }
        if (!result.rejectedReasons().isEmpty()) {
            return UiFormat.errorRedirect(FLOW_PAGE, rejectedExplanation(result.rejectedReasons()));
        }
        return "redirect:" + FLOW_PAGE;
    }

    private static List<TaskCommands.Selection> selections(List<String> taskIds, Map<String, String> allParams) {
        if (taskIds == null) {
            return List.of();
        }
        var selections = new ArrayList<TaskCommands.Selection>(taskIds.size());
        for (String id : taskIds) {
            selections.add(new TaskCommands.Selection(UUID.fromString(id),
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

    private TaskInstallation installation() {
        return installations.findById(1).orElseThrow(TaskInstallation::missing);
    }

    private Map<String, Object> queueWaiting(TaskInstallation installation, List<TaskView> triggered) {
        UUID active = installation.getActiveAttemptId();
        if (active == null) {
            return null;
        }
        return attempts.findById(active)
                .filter(attempt -> attempt.getState() == TaskAttempt.State.NEEDS_INSPECTION)
                .map(attempt -> TaskRows.queueWaiting(displayKey(attempt.getTaskId(), triggered), attempt))
                .orElse(null);
    }

    private String displayKey(UUID taskId, List<TaskView> triggered) {
        return triggered.stream()
                .filter(view -> view.taskId().equals(taskId))
                .map(TaskView::displayKey)
                .findFirst()
                .orElseGet(() -> taskLabel(taskId));
    }

    /** Compact identity for live refresh; comma-free so poll.js can split fields safely. */
    private String revision(TaskInstallation installation) {
        String problem = installation.getSyncProblem() == null ? "none" : "present";
        return installation.isSyncComplete() + "|" + problem + "|" + installation.getUpdatedAt()
                + "|" + attempts.findLatestUpdate().map(Instant::toString).orElse("none")
                + "|" + attempts.count() + "|" + (dispatcher.queueProblem() == null ? "moving" : "stuck");
    }
}
