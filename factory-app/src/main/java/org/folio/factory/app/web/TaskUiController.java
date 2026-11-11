package org.folio.factory.app.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.admission.TaskCommands;
import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.execution.TaskDispatcher;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.presentation.AvailableRow;
import org.folio.factory.app.tasks.presentation.RunRows;
import org.folio.factory.app.tasks.presentation.TaskQuery;
import org.folio.factory.app.tasks.presentation.TaskRevisions;
import org.folio.factory.app.tasks.presentation.TaskRows;
import org.folio.factory.app.tasks.presentation.TaskView;
import org.folio.factory.core.registry.FlowRegistry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;
import static org.folio.factory.devfactory.DevFactoryConfiguration.FLOW_ID;

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

    private static final String FLOW_PAGE = "/flows/" + FLOW_ID;
    private static final int TRIGGERED_PAGE_SIZE = 20;

    private final TaskQuery taskQuery;
    private final TaskInstallationRepository installations;
    private final TaskAttemptRepository attempts;
    private final TaskAdmissionService admissions;
    private final TaskDispatcher dispatcher;
    private final TaskRows rows;
    private final FlowRegistry flows;
    private final TaskRunSettings runSettings;
    private final TaskRevisions revisions;

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
        var pollBaseline = revisions.myTasks(installation);
        var myTasks = taskQuery.myTasks(PageRequest.of(Math.max(page, 0), TRIGGERED_PAGE_SIZE));
        var queuedCount = attempts.countByStateIn(List.of(TaskAttempt.State.RESERVED));
        var available = myTasks.available().stream().map(rows::availableRow).toList();
        model.addAttribute("flow", Map.of("id", descriptor.id(), "name", descriptor.name(),
                "version", descriptor.version()));
        model.addAttribute("source", rows.sourceCard(installation));
        addAvailable(model, available);
        addTriggered(model, installation, myTasks.triggered(), queuedCount);
        // Model for Run and the Effort choices of each row; null with the Pi harness.
        model.addAttribute("runForm", runSettings.form());
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

    /** Baseline for the page's live refresh; a read-only projection, never an action. */
    @GetMapping("/tasks/poll")
    @ResponseBody
    public Map<String, Object> poll() {
        return Map.of("revision", revisions.myTasks(installation()));
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
            var result = admissions.runSelected(new TaskCommands.RunSelected("ui-" + UUID.randomUUID(), selections));
            return admissionRedirect(result, redirect);
        } catch (NumberFormatException e) {
            return UiFormat.errorRedirect(FLOW_PAGE,
                    "The submitted selection was malformed; reload the page and try again.");
        } catch (IllegalArgumentException e) {
            return UiFormat.errorRedirect(FLOW_PAGE, e.getMessage());
        }
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

    private static void addAvailable(Model model, List<AvailableRow> available) {
        model.addAttribute("available", available.stream().filter(AvailableRow::selectable).toList());
        model.addAttribute("cannotRun", RunRows.cannotRun(available));
    }

    private void addTriggered(Model model, TaskInstallation installation, Page<TaskView> triggered,
                              long queuedCount) {
        model.addAttribute("triggered", triggered.getContent().stream().map(RunRows::row).toList());
        model.addAttribute("inProgress", RunRows.strip(triggered.getContent()).orElse(null));
        model.addAttribute("page", triggered);
        model.addAttribute("baseUrl", FLOW_PAGE + "?");
        model.addAttribute("queueWaiting", queueWaiting(installation, triggered.getContent()));
        model.addAttribute("queueProblem", queuedCount > 0 ? dispatcher.queueProblem() : null);
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

    /** Each selected task with the identity the developer saw and its own run settings: the effort of its row. */
    private List<TaskCommands.Selection> selections(List<String> taskIds, Map<String, String> allParams) {
        if (taskIds == null) {
            return List.of();
        }
        var selections = new ArrayList<TaskCommands.Selection>(taskIds.size());
        for (String id : taskIds) {
            selections.add(new TaskCommands.Selection(UUID.fromString(id),
                    Long.parseLong(allParams.getOrDefault("version-" + id, "0")),
                    allParams.get("input-" + id),
                    allParams.get("destination-" + id),
                    runSettings.snapshot(allParams.get("modelId"), allParams.get("effort-" + id), null)));
        }
        return selections;
    }

    private String rejectedExplanation(Map<UUID, String> rejectedReasons) {
        return rejectedReasons.entrySet().stream()
                .map(entry -> taskLabel(entry.getKey()) + ": " + entry.getValue())
                .collect(Collectors.joining("; "));
    }

    private String taskLabel(UUID taskId) {
        return taskQuery.displayKey(taskId).orElseGet(() -> UiFormat.abbreviate(taskId.toString()));
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
}
