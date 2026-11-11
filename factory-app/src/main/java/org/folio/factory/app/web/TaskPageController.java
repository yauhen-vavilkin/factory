package org.folio.factory.app.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.admission.TaskCommands;
import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.presentation.TaskAnswers;
import org.folio.factory.app.tasks.presentation.TaskQuery;
import org.folio.factory.app.tasks.presentation.TaskRows;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;
import java.util.UUID;

import static org.folio.factory.app.web.TaskUiController.requireSameOrigin;
import static org.folio.factory.devfactory.DevFactoryConfiguration.FLOW_ID;

/** The task page: its state, the agent's question or the next attempt, and the attempts so far. */
@Controller
@RequiredArgsConstructor
public class TaskPageController {

    private static final String FLOW_PAGE = "/flows/" + FLOW_ID;

    private final TaskQuery taskQuery;
    private final TaskAdmissionService admissions;
    private final TaskRows rows;
    private final TaskRunSettings runSettings;
    private final TaskAnswers answers;

    @GetMapping("/tasks/{taskId}")
    public String task(@PathVariable("taskId") UUID taskId,
                       @RequestParam(name = "error", required = false) String error, Model model) {
        var view = taskQuery.detail(taskId);
        model.addAttribute("task", rows.detail(view));
        model.addAttribute("question", answers.question(view).orElse(null));
        model.addAttribute("attempts", view.attempts().reversed().stream().map(TaskRows::attemptRow).toList());
        // A new attempt starts from the configured model and effort, not from the previous attempt's.
        model.addAttribute("retryForm", runSettings.form());
        model.addAttribute("instructionsLimit", ModelCatalog.MAX_INSTRUCTIONS);
        model.addAttribute("error", error);
        return "task";
    }

    /**
     * The optional text fields (fingerprints, returnTo, the run settings and the answer) are read
     * from the form map, the same way Run reads its run settings. An answer to the agent's question
     * becomes the instructions of the new attempt.
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
            settings = runSettings.snapshot(form.get("modelId"), form.get("effort"), instructions(taskId, form));
        } catch (IllegalArgumentException e) {
            return refused(back, e.getMessage(), form, redirect);
        }
        var result = admissions.retry(new TaskCommands.Retry("ui-" + UUID.randomUUID(), taskId, expectedVersion,
                form.get("inputFingerprint"), form.get("destinationFingerprint"), settings));
        // The status card and Runs already say that the new attempt is queued or running.
        if (!result.refused() && result.state() != null && result.state().isInFlight()) {
            return "redirect:" + back;
        }
        return refused(back, result.explanation(), form, redirect);
    }

    /** The page shows the text the developer typed again; it goes through the session, never into the URL. */
    private static String refused(String back, String message, Map<String, String> form, RedirectAttributes redirect) {
        keepTyped(form.get("instructions"), "typedInstructions", redirect);
        keepTyped(form.get("note"), "typedNote", redirect);
        return UiFormat.errorRedirect(back, message);
    }

    private static void keepTyped(String text, String attribute, RedirectAttributes redirect) {
        if (text != null && !text.isBlank()) {
            redirect.addFlashAttribute(attribute, text);
        }
    }

    private String instructions(UUID taskId, Map<String, String> form) {
        if (!form.containsKey("answer")) {
            return form.get("instructions");
        }
        return answers.instructions(taskQuery.detail(taskId), form.get("answer"), form.get("note"));
    }
}
