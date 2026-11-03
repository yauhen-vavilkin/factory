package org.folio.factory.app.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.source.TaskDiscoveryService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

import static org.folio.factory.app.web.TaskUiController.requireSameOrigin;
import static org.folio.factory.devfactory.DevFactoryConfiguration.FLOW_ID;

/** What is in My tasks: Scan Jira, a task added by its key, and Remove for such a task. */
@Controller
@RequiredArgsConstructor
public class TaskListController {

    private static final String FLOW_PAGE = "/flows/" + FLOW_ID;

    private final TaskDiscoveryService discovery;
    private final TaskAdmissionService admissions;

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

    @PostMapping(FLOW_PAGE + "/add-by-key")
    public String addByKey(@RequestParam(name = "key", required = false) String key,
                           HttpServletRequest request, RedirectAttributes redirect) {
        requireSameOrigin(request);
        TaskAdmissionService.KeyResult result = admissions.addByKey(key);
        if (result.problem() != null) {
            return UiFormat.errorRedirect(FLOW_PAGE, result.problem());
        }
        redirect.addFlashAttribute("message", result.issueKey() + " is in Available now.");
        return "redirect:" + FLOW_PAGE;
    }

    @PostMapping("/tasks/{taskId}/remove-from-list")
    public String removeFromList(@PathVariable("taskId") UUID taskId, HttpServletRequest request,
                                 RedirectAttributes redirect) {
        requireSameOrigin(request);
        String refusal = discovery.removeFromList(taskId);
        if (refusal != null) {
            return UiFormat.errorRedirect(FLOW_PAGE, refusal);
        }
        redirect.addFlashAttribute("message", "Removed from your list.");
        return "redirect:" + FLOW_PAGE;
    }
}
