package org.folio.factory.app.web;

import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.core.trigger.TriggerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook receivers that adapt external event formats to the internal
 * {@link TriggerEvent} model. Authentication is a shared secret passed as a query
 * parameter; TODO: verify Jira/GitHub webhook signatures instead once secrets
 * management is in place.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final PipelineRouter router;
    private final String sharedSecret;

    public WebhookController(PipelineRouter router,
                             @Value("${factory.webhooks.shared-secret:}") String sharedSecret) {
        this.router = router;
        this.sharedSecret = sharedSecret;
        if (sharedSecret.isBlank()) {
            log.warn("Webhook endpoints are UNAUTHENTICATED: set factory.webhooks.shared-secret "
                    + "(FACTORY_WEBHOOKS_SHARED_SECRET) in any non-local deployment");
        }
    }

    @PostMapping("/jira")
    public ResponseEntity<Map<String, List<String>>> jira(
            @RequestParam(name = "token", required = false) String token,
            @RequestBody JsonNode body) {
        checkSecret(token);
        String webhookEvent = body.path("webhookEvent").asString("");
        String type = switch (webhookEvent) {
            case "jira:issue_updated" -> "jira.issue.transitioned";
            case "jira:issue_created" -> "jira.issue.created";
            default -> "jira.event";
        };
        return respond(router.route(TriggerEvent.of(type, "jira-webhook", normaliseJiraPayload(body))));
    }

    /**
     * Raw Jira webhook bodies carry the key at {@code issue.key}; flow input
     * schemas declare the internal contract field {@code issueKey}. Normalising
     * external formats to the internal contract is this adapter's job.
     */
    private JsonNode normaliseJiraPayload(JsonNode body) {
        if (body.isObject() && !body.has("issueKey")) {
            String issueKey = body.path("issue").path("key").asString("");
            if (!issueKey.isBlank()) {
                ((ObjectNode) body).put("issueKey", issueKey);
            }
        }
        return body;
    }

    @PostMapping("/github")
    public ResponseEntity<Map<String, List<String>>> github(
            @RequestParam(name = "token", required = false) String token,
            @RequestHeader(name = "X-GitHub-Event", defaultValue = "event") String event,
            @RequestBody JsonNode body) {
        checkSecret(token);
        return respond(router.route(TriggerEvent.of("github." + event, "github-webhook", body)));
    }

    private void checkSecret(String token) {
        if (!sharedSecret.isBlank() && !sharedSecret.equals(token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid webhook token");
        }
    }

    private ResponseEntity<Map<String, List<String>>> respond(List<UUID> executionIds) {
        if (executionIds.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("executionIds", executionIds.stream().map(UUID::toString).toList()));
    }
}
