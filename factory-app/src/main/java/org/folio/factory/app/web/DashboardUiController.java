package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.persistence.RunState;
import org.folio.factory.app.web.dashboard.DashboardStats.StepTokenCount;
import org.folio.factory.app.web.dashboard.DashboardStats.TokenUsage;
import org.folio.factory.app.web.dashboard.DashboardStatsService;
import org.folio.factory.connectors.ConnectorHealth;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.FlowRegistryEntry;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.engine.EngineProperties;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.repository.AuditEventRepository;
import org.folio.factory.core.repository.FlowRegistryEntryRepository;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Server-rendered operational views: the Dashboard home ({@code /}) with KPI
 * cards and interactive charts, the platform Status page (engine, connectors,
 * flow registry) and the Audit page (paged, filterable event feed). All draw on
 * the same beans as their REST counterparts.
 */
@Controller
@RequiredArgsConstructor
public class DashboardUiController {

    private static final int DEFAULT_DAYS = 14;
    private static final int DEFAULT_AUDIT_SIZE = 50;
    private static final Set<Integer> ALLOWED_DAYS = Set.of(1, 7, 14, 30, 90);

    private final List<ConnectorHealth> connectors;
    private final FlowRegistry flowRegistry;
    private final EngineProperties engineProperties;
    private final FlowRegistryEntryRepository flowRegistryEntries;
    private final HitlReviewRepository reviews;
    private final AuditEventRepository audit;
    private final JsonMapper jsonMapper;
    private final DashboardStatsService dashboardStats;

    @GetMapping("/")
    public String dashboard(@RequestParam(name = "days", defaultValue = "14") int days, Model model) {
        int window = ALLOWED_DAYS.contains(days) ? days : DEFAULT_DAYS;
        model.addAttribute("days", window);

        addExecutionCounts(model);
        model.addAttribute("pendingReviews", reviews.countByStatus(HitlReviewStatus.PENDING));

        model.addAttribute("engineEnabled", engineProperties.enabled());
        Map<String, Boolean> connectorConfig = connectorConfiguration();
        int configured = (int) connectorConfig.values().stream().filter(Boolean::booleanValue).count();
        model.addAttribute("connectorsConfigured", configured);
        model.addAttribute("connectorsTotal", connectorConfig.size());

        // Unlike the all-time KPIs above, token spend is scoped to the selected window.
        TokenUsage tokens = dashboardStats.tokenUsage(window);
        model.addAttribute("tokenTotal", UiFormat.count(tokens.totalTokens()));
        model.addAttribute("tokenBreakdown",
                UiFormat.count(tokens.promptTokens()) + " in · " + UiFormat.count(tokens.completionTokens()) + " out");
        model.addAttribute("stepTokens", dashboardStats.stepTokens(window).stream()
                .map(DashboardUiController::stepTokenRow).toList());
        return "dashboard";
    }

    /** All-time run counts: the total and one count per state the Executions list filters by. */
    private void addExecutionCounts(Model model) {
        var byState = dashboardStats.countByState();
        model.addAttribute("totalExecutions", byState.values().stream().mapToLong(Long::longValue).sum());
        model.addAttribute("completedCount", byState.getOrDefault(RunState.COMPLETED, 0L));
        model.addAttribute("pullRequestCount", byState.getOrDefault(RunState.PULL_REQUEST, 0L));
        model.addAttribute("awaitingHitlCount", byState.getOrDefault(RunState.WAITING_FOR_REVIEW, 0L));
        model.addAttribute("failedCount", byState.getOrDefault(RunState.FAILED, 0L));
        model.addAttribute("cancelledOrRejectedCount", byState.getOrDefault(RunState.CANCELLED_OR_REJECTED, 0L));
    }

    private static Map<String, Object> stepTokenRow(StepTokenCount step) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("flowId", step.flowId());
        row.put("stepId", step.stepId());
        row.put("prompt", UiFormat.count(step.promptTokens()));
        row.put("completion", UiFormat.count(step.completionTokens()));
        row.put("total", UiFormat.count(step.totalTokens()));
        return row;
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

        model.addAttribute("connectors", connectorConfiguration().entrySet().stream()
                .map(entry -> connectorRow(entry.getKey(), entry.getValue())).toList());

        model.addAttribute("flows", flowRegistry.all().stream().map(this::flowRow).toList());
        return "status";
    }

    @GetMapping("/audit")
    public String audit(@RequestParam(name = "eventType", required = false) String eventType,
                        @RequestParam(name = "executionId", required = false) UUID executionId,
                        @RequestParam(name = "page", defaultValue = "0") int page,
                        @RequestParam(name = "size", defaultValue = "50") int size,
                        Model model) {
        int clampedSize = PageValidation.clampSize(size, DEFAULT_AUDIT_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampedSize);
        AuditEventType type = UiFormat.enumOrNull(AuditEventType.class, eventType);

        Slice<AuditEvent> result = auditSlice(type, executionId, pageable);
        model.addAttribute("events", result.getContent().stream()
                .map(event -> UiFormat.auditRow(event, jsonMapper)).toList());
        model.addAttribute("page", result);
        model.addAttribute("eventTypes", Arrays.stream(AuditEventType.values())
                .map(t -> Map.of("value", t.name(), "label", UiFormat.eventLabel(t))).toList());
        model.addAttribute("selectedEventType", type == null ? "" : type.name());
        model.addAttribute("selectedExecutionId", executionId == null ? "" : executionId.toString());
        model.addAttribute("baseUrl", "/audit?eventType="
                + (type == null ? "" : type.name()) + "&executionId="
                + (executionId == null ? "" : executionId) + "&size=" + clampedSize);
        return "audit";
    }

    /**
     * Newest first. Slice, not Page: the audit table is append-only and unbounded, so a total
     * count(*) per page view would degrade without limit.
     */
    private Slice<AuditEvent> auditSlice(AuditEventType type, UUID executionId, Pageable pageable) {
        if (executionId == null) {
            return type == null
                    ? audit.findAllByOrderByIdDesc(pageable)
                    : audit.findByEventTypeOrderByIdDesc(type, pageable);
        }
        List<AuditEvent> filtered = new ArrayList<>(audit.findByExecutionIdOrderByIdAsc(executionId));
        Collections.reverse(filtered);
        if (type != null) filtered.removeIf(event -> event.getEventType() != type);
        int start = (int) Math.min(pageable.getOffset(), filtered.size());
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        return new SliceImpl<>(filtered.subList(start, end), pageable, end < filtered.size());
    }

    private Map<String, Boolean> connectorConfiguration() {
        Map<String, Boolean> byName = new TreeMap<>();
        for (ConnectorHealth connector : connectors) {
            byName.merge(connector.connectorName(), connector.isConfigured(), (a, b) -> a || b);
        }
        return byName;
    }

    /**
     * One connector card. Jira without sign-in still serves My tasks, which reads it with the base URL alone, so
     * its card says so instead of only "advisory mode".
     */
    private static ConnectorRow connectorRow(String name, boolean configured) {
        if (configured) {
            return new ConnectorRow(name, true, null, null);
        }
        return "jira".equals(name)
                ? new ConnectorRow(name, false, "Not configured for Test Factory sync, which needs"
                        + " FACTORY_CONNECTORS_JIRA_EMAIL and FACTORY_CONNECTORS_JIRA_API_TOKEN. My tasks reads Jira"
                        + " with FACTORY_CONNECTORS_JIRA_BASE_URL alone and sees public issues without them.",
                        "/developer/configuration")
                : new ConnectorRow(name, false,
                        "Sync actions run in advisory mode and are audited as CONNECTOR_SKIPPED.", null);
    }

    private FlowRow flowRow(FlowDescriptor flow) {
        var registeredAt = flowRegistryEntries.findById(new FlowRegistryEntry.Key(flow.id(), flow.version()))
                .map(entry -> PageTime.day(entry.getRegisteredAt()))
                .orElse(null);
        return new FlowRow(flow.id(), flow.name(), flow.version(), registeredAt, UiFormat.flowDefinitionUrl(flow.id()));
    }

    /** A connector card; {@code configurationUrl} links the page that shows its other use, or is null. */
    public record ConnectorRow(String name, boolean configured, String note, String configurationUrl) {
    }

    /** A registered flow; {@code url} opens its Definition. */
    public record FlowRow(String id, String name, String version, PageTime registeredAt, String url) {
    }

}
