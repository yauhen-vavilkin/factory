package org.folio.factory.app.web;

import org.folio.factory.connectors.ConnectorHealth;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.FlowRegistryEntry;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.engine.EngineProperties;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.repository.AuditEventRepository;
import org.folio.factory.core.repository.FlowRegistryEntryRepository;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-rendered operational views: the Dashboard home ({@code /}) with KPI
 * cards and interactive charts, the platform Status page (engine, connectors,
 * flow registry) and the Audit page (paged, filterable event feed). All draw on
 * the same beans as their REST counterparts.
 */
@Controller
public class DashboardUiController {

    private static final int MAX_SIZE = 200;
    private static final int DEFAULT_DAYS = 14;
    private static final Set<Integer> ALLOWED_DAYS = Set.of(1, 7, 14, 30, 90);
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final List<ConnectorHealth> connectors;
    private final FlowRegistry flowRegistry;
    private final EngineProperties engineProperties;
    private final FlowRegistryEntryRepository flowRegistryEntries;
    private final PipelineExecutionRepository executions;
    private final HitlReviewRepository reviews;
    private final AuditEventRepository audit;
    private final JsonMapper jsonMapper;

    public DashboardUiController(List<ConnectorHealth> connectors, FlowRegistry flowRegistry,
                                 EngineProperties engineProperties,
                                 FlowRegistryEntryRepository flowRegistryEntries,
                                 PipelineExecutionRepository executions, HitlReviewRepository reviews,
                                 AuditEventRepository audit, JsonMapper jsonMapper) {
        this.connectors = connectors;
        this.flowRegistry = flowRegistry;
        this.engineProperties = engineProperties;
        this.flowRegistryEntries = flowRegistryEntries;
        this.executions = executions;
        this.reviews = reviews;
        this.audit = audit;
        this.jsonMapper = jsonMapper;
    }

    @GetMapping("/")
    public String dashboard(@RequestParam(name = "days", defaultValue = "14") int days, Model model) {
        model.addAttribute("days", ALLOWED_DAYS.contains(days) ? days : DEFAULT_DAYS);

        Map<ExecutionStatus, Long> byStatus = new EnumMap<>(ExecutionStatus.class);
        long total = 0;
        for (ExecutionStatus status : ExecutionStatus.values()) {
            long count = executions.countByStatus(status);
            byStatus.put(status, count);
            total += count;
        }
        model.addAttribute("totalExecutions", total);
        model.addAttribute("completedCount", byStatus.get(ExecutionStatus.COMPLETED));
        model.addAttribute("awaitingHitlCount", byStatus.get(ExecutionStatus.AWAITING_HITL));
        model.addAttribute("failedCount", byStatus.get(ExecutionStatus.FAILED_ESCALATED));
        model.addAttribute("cancelledCount", byStatus.get(ExecutionStatus.CANCELLED));
        model.addAttribute("rejectedCount", byStatus.get(ExecutionStatus.REJECTED));

        model.addAttribute("pendingReviews", reviews
                .findByStatus(HitlReviewStatus.PENDING, PageRequest.of(0, 1)).getTotalElements());

        model.addAttribute("engineEnabled", engineProperties.enabled());
        int configured = (int) connectors.stream().filter(ConnectorHealth::isConfigured).count();
        model.addAttribute("connectorsConfigured", configured);
        model.addAttribute("connectorsTotal", connectors.size());
        return "dashboard";
    }

    @GetMapping("/status")
    public String status(Model model) {
        Map<String, Object> engine = new LinkedHashMap<>();
        engine.put("enabled", engineProperties.enabled());
        engine.put("pollIntervalMs", engineProperties.pollIntervalMs());
        engine.put("batchSize", engineProperties.batchSize());
        engine.put("workerThreads", engineProperties.workerThreads());
        engine.put("leaseTimeoutSeconds", engineProperties.leaseTimeoutSeconds());
        model.addAttribute("engine", engine);

        List<Map<String, Object>> connectorRows = new ArrayList<>();
        for (ConnectorHealth connector : connectors) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", connector.connectorName());
            row.put("configured", connector.isConfigured());
            connectorRows.add(row);
        }
        model.addAttribute("connectors", connectorRows);

        model.addAttribute("flows", flowRegistry.all().stream().map(this::flowRow).toList());
        return "status";
    }

    @GetMapping("/audit")
    public String audit(@RequestParam(name = "eventType", required = false) String eventType,
                        @RequestParam(name = "page", defaultValue = "0") int page,
                        @RequestParam(name = "size", defaultValue = "50") int size,
                        Model model) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        AuditEventType type = parseTypeLenient(eventType);

        Page<AuditEvent> result = type == null
                ? audit.findAllByOrderByIdDesc(pageable)
                : audit.findByEventTypeOrderByIdDesc(type, pageable);

        model.addAttribute("events", result.getContent().stream().map(this::auditRow).toList());
        model.addAttribute("page", result);
        model.addAttribute("eventTypes", AuditEventType.values());
        model.addAttribute("selectedEventType", type == null ? "" : type.name());
        model.addAttribute("baseUrl", "/audit?eventType="
                + (type == null ? "" : type.name()) + "&size=" + clampSize(size));
        return "audit";
    }

    private Map<String, Object> flowRow(FlowDescriptor flow) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", flow.id());
        row.put("name", flow.name());
        row.put("version", flow.version());
        row.put("registeredAt", flowRegistryEntries
                .findById(new FlowRegistryEntry.Key(flow.id(), flow.version()))
                .map(entry -> format(entry.getRegisteredAt()))
                .orElse("—"));
        return row;
    }

    private Map<String, Object> auditRow(AuditEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("occurredAt", format(event.getOccurredAt()));
        row.put("eventType", event.getEventType().name());
        row.put("stepId", event.getStepId());
        row.put("actor", event.getActor());
        row.put("executionId", event.getExecutionId());
        row.put("executionShort", event.getExecutionId() == null ? null
                : abbreviate(event.getExecutionId().toString()));
        row.put("detail", prettyDetail(event.getDetail()));
        return row;
    }

    private String prettyDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        JsonNode node = jsonMapper.readTree(detail);
        return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    private AuditEventType parseTypeLenient(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return null;
        }
        try {
            return AuditEventType.valueOf(eventType.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private int clampSize(int size) {
        if (size < 1) {
            return 1;
        }
        return Math.min(size, MAX_SIZE);
    }

    private static String abbreviate(String value) {
        return value.length() <= 13 ? value : value.substring(0, 10) + "...";
    }

    private static String format(Instant instant) {
        return instant == null ? "" : TIMESTAMP.format(instant);
    }
}
