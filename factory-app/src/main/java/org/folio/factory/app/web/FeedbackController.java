package org.folio.factory.app.web;
import org.folio.factory.devfactory.feedback.FeedbackActions;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/executions/{id}/pr-feedback")
public class FeedbackController {
    private final FeedbackActions actions;
    public FeedbackController(FeedbackActions actions) { this.actions=actions; }
    @GetMapping public FeedbackActions.View view(@PathVariable UUID id) { return actions.view(id); }
    public record Refresh(int stateVersion) { }
    @PostMapping("/refresh") public FeedbackActions.View refresh(@PathVariable UUID id,@RequestBody Refresh request) {
        return actions.refresh(id,request.stateVersion());
    }
    @PostMapping("/actions") public ResponseEntity<Void> command(@PathVariable UUID id,@RequestBody FeedbackActions.Command command) {
        actions.command(id,command,"operator"); return ResponseEntity.accepted().build();
    }
    @GetMapping(value="/diagnostics",produces=MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> diagnostics(@PathVariable UUID id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=feedback-diagnostics.txt").body(actions.diagnostics(id));
    }
    @GetMapping(value="/patch",produces=MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> patch(@PathVariable UUID id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=feedback.patch").body(actions.patch(id));
    }
}
