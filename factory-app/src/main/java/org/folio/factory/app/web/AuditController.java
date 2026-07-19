package org.folio.factory.app.web;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.repository.AuditEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditEventRepository audit;
    private final JsonMapper jsonMapper;

    public AuditController(AuditEventRepository audit, JsonMapper jsonMapper) {
        this.audit = audit;
        this.jsonMapper = jsonMapper;
    }

    public record AuditFeedEntry(long id, UUID executionId, String eventType, String stepId,
                                 String actor, JsonNode detail, Instant occurredAt) {
    }

    @GetMapping
    public PageResponse<AuditFeedEntry> list(
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        Pageable pageable = PageValidation.pageable(page, size);
        Page<AuditEvent> result = (eventType == null || eventType.isBlank())
                ? audit.findAllByOrderByIdDesc(pageable)
                : audit.findByEventTypeOrderByIdDesc(parseType(eventType), pageable);
        return PageResponse.of(result, this::toEntry);
    }

    private AuditEventType parseType(String eventType) {
        try {
            return AuditEventType.valueOf(eventType.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown audit event type '" + eventType + "'");
        }
    }

    private AuditFeedEntry toEntry(AuditEvent event) {
        String detail = event.getDetail();
        JsonNode parsed = detail == null || detail.isBlank() ? null : jsonMapper.readTree(detail);
        return new AuditFeedEntry(event.getId(), event.getExecutionId(), event.getEventType().name(),
                event.getStepId(), event.getActor(), parsed, event.getOccurredAt());
    }
}
