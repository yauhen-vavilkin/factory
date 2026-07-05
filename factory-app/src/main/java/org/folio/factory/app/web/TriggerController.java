package org.folio.factory.app.web;

import org.folio.factory.core.trigger.PipelineRouter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/triggers")
public class TriggerController {

    private final PipelineRouter router;

    public TriggerController(PipelineRouter router) {
        this.router = router;
    }

    public record ManualTriggerRequest(String flowId, JsonNode payload) {
    }

    @PostMapping("/manual")
    public ResponseEntity<Map<String, String>> manual(@RequestBody ManualTriggerRequest request) {
        if (request.flowId() == null || request.flowId().isBlank()) {
            throw new IllegalArgumentException("flowId is required");
        }
        UUID executionId = router.routeManual(request.flowId(), request.payload());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("executionId", executionId.toString()));
    }
}
