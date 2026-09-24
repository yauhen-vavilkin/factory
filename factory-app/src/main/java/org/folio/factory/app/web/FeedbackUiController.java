package org.folio.factory.app.web;
import org.folio.factory.devfactory.feedback.FeedbackActions;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.*;
@Controller
@RequestMapping("/executions/{id}/pr-feedback")
public class FeedbackUiController {
    private final FeedbackActions actions;
    public FeedbackUiController(FeedbackActions actions) { this.actions=actions; }
    @PostMapping("/refresh") public String refresh(@PathVariable UUID id,@RequestParam int stateVersion,RedirectAttributes redirect) {
        try {actions.refresh(id,stateVersion);} catch(RuntimeException e) {redirect.addFlashAttribute("feedbackError",message(e));}
        return "redirect:/executions/"+id+"#pr-feedback";
    }
    @PostMapping("/actions") public String command(@PathVariable UUID id,@RequestParam UUID reviewId,@RequestParam int stateVersion,
            @RequestParam FeedbackActions.Action action,@RequestParam(defaultValue="") String snapshotId,
            @RequestParam(required=false) List<String> selectedIds,@RequestParam(defaultValue="") String answer,RedirectAttributes redirect) {
        try {actions.command(id,new FeedbackActions.Command(reviewId,stateVersion,action,snapshotId,selectedIds,answer),"operator");}
        catch(RuntimeException e) {redirect.addFlashAttribute("feedbackError",message(e));}
        return "redirect:/executions/"+id+"#pr-feedback";
    }
    private String message(RuntimeException e) {
        return e instanceof IllegalArgumentException || e instanceof IllegalStateException?e.getMessage():"Could not complete this action. Reload and try again.";
    }
}
