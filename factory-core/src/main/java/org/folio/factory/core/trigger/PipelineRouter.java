package org.folio.factory.core.trigger;

import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.limits.DailyBudgetExceededException;
import org.folio.factory.core.limits.DedupKeyDeriver;
import org.folio.factory.core.limits.LimitsProperties;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.TriggerContract;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Entry point of the orchestration layer. Entirely data-driven: matches incoming
 * trigger events against the trigger contracts registered in the flow registry and
 * creates pipeline executions for every match. No flow-specific logic.
 *
 * <p>Two flow-agnostic cost guards run here: dedup (a re-fired trigger with the same
 * payload identity collapses onto the existing execution instead of starting a
 * duplicate) and a daily execution budget (refused with {@link
 * DailyBudgetExceededException} once exceeded). A per-trigger payload size cap
 * rejects oversize webhook bodies before any execution is persisted.</p>
 */
@Component
public class PipelineRouter {

    private static final Logger log = LoggerFactory.getLogger(PipelineRouter.class);

    private final FlowRegistry flowRegistry;
    private final StateManager stateManager;
    private final JsonMapper jsonMapper;
    private final LimitsProperties limits;
    private final DedupKeyDeriver dedupKeyDeriver;
    private final AuditLog auditLog;

    public PipelineRouter(FlowRegistry flowRegistry, StateManager stateManager, JsonMapper jsonMapper,
                          LimitsProperties limits, DedupKeyDeriver dedupKeyDeriver, AuditLog auditLog) {
        this.flowRegistry = flowRegistry;
        this.stateManager = stateManager;
        this.jsonMapper = jsonMapper;
        this.limits = limits;
        this.dedupKeyDeriver = dedupKeyDeriver;
        this.auditLog = auditLog;
    }

    /**
     * Routes an event to every flow whose trigger contract matches. Returns the ids
     * of the executions created (or, for deduped flows, of the existing executions).
     *
     * <p>Dedup is resolved per flow first, then the daily budget is enforced once for
     * all flows still needing an execution — all-or-nothing, so a multi-flow event can
     * never create one execution and then fail with a budget refusal for the next.</p>
     */
    public List<UUID> route(TriggerEvent event) {
        String dedupKey = dedupKeyDeriver.derive(event.payload());
        List<UUID> created = new ArrayList<>();
        List<FlowDescriptor> toCreate = new ArrayList<>();
        for (FlowDescriptor flow : flowRegistry.all()) {
            if (matches(flow, event) && hasRequiredInputs(flow, event.payload())) {
                Optional<PipelineExecution> existing = recentDuplicate(flow, event, dedupKey);
                if (existing.isPresent()) {
                    created.add(existing.get().getId());
                } else {
                    toCreate.add(flow);
                }
            }
        }
        if (!toCreate.isEmpty()) {
            enforceDailyBudget(toCreate);
            for (FlowDescriptor flow : toCreate) {
                created.add(create(flow, event, dedupKey).getId());
            }
        }
        if (created.isEmpty()) {
            log.debug("No flow matched trigger event type '{}' from {}", event.type(), event.source());
        }
        return created;
    }

    /**
     * Manual initiation: the caller names the flow explicitly, bypassing contract
     * matching but not input validation. Manual triggers opt out of dedup by default
     * (a deliberate manual run should never be silently collapsed).
     */
    public UUID routeManual(String flowId, JsonNode payload) {
        return routeManual(flowId, payload, null);
    }

    public UUID routeManual(String flowId, JsonNode payload, String dedupKey) {
        FlowDescriptor flow = flowRegistry.require(flowId);
        requireInputs(flow, payload);
        TriggerEvent event = TriggerEvent.of("manual", "api", payload);
        Optional<PipelineExecution> existing = recentDuplicate(flow, event, dedupKey);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        enforceDailyBudget(List.of(flow));
        return create(flow, event, dedupKey).getId();
    }

    private boolean matches(FlowDescriptor flow, TriggerEvent event) {
        for (TriggerContract contract : flow.triggers()) {
            if (!contract.eventType().equals(event.type())) {
                continue;
            }
            if (filtersMatch(contract, event.payload())) {
                return true;
            }
        }
        return false;
    }

    private boolean filtersMatch(TriggerContract contract, JsonNode payload) {
        for (Map.Entry<String, String> filter : contract.filters().entrySet()) {
            JsonNode value = payload == null ? null : payload.at(filter.getKey());
            if (value != null && !value.isMissingNode() && !value.isValueNode()) {
                // A filter pointing at an object/array is almost certainly a
                // descriptor typo; silent no-match would be undiagnosable.
                log.warn("Trigger filter '{}' resolved to a non-scalar node ({}); it will never match",
                        filter.getKey(), value.getNodeType());
                return false;
            }
            // asString(default) tolerates non-string scalars, where asString()
            // would throw.
            if (value == null || value.isMissingNode() || !filter.getValue().equals(value.asString(""))) {
                return false;
            }
        }
        return true;
    }

    private boolean hasRequiredInputs(FlowDescriptor flow, JsonNode payload) {
        try {
            requireInputs(flow, payload);
            return true;
        } catch (IllegalArgumentException e) {
            log.warn("Trigger matched flow '{}' but payload is invalid: {}", flow.id(), e.getMessage());
            return false;
        }
    }

    private void requireInputs(FlowDescriptor flow, JsonNode payload) {
        JsonNode required = flow.inputSchema() == null ? null : flow.inputSchema().get("required");
        if (required == null || !required.isArray()) {
            return;
        }
        for (JsonNode field : required) {
            String name = field.asString("");
            if (payload == null || payload.get(name) == null || payload.get(name).isNull()) {
                throw new IllegalArgumentException(
                        "Flow '" + flow.id() + "' requires input field '" + name + "'");
            }
        }
    }

    /**
     * The dedup lookup: an existing execution of {@code flow} with the same payload
     * identity that is still active or within the dedup window. Audited when hit.
     */
    private Optional<PipelineExecution> recentDuplicate(FlowDescriptor flow, TriggerEvent event, String dedupKey) {
        if (dedupKey == null) {
            return Optional.empty();
        }
        Optional<PipelineExecution> existing =
                stateManager.findRecentDuplicate(flow.id(), dedupKey, limits.dedup().window());
        existing.ifPresent(dup -> {
            auditLog.record(dup.getId(), AuditEventType.EXECUTION_DEDUPED, null,
                    Map.of("flowId", flow.id(), "dedupKey", dedupKey));
            log.info("Deduped trigger '{}' from {} onto existing execution {} of flow '{}' (key {})",
                    event.type(), event.source(), dup.getId(), flow.id(), dedupKey);
        });
        return existing;
    }

    private PipelineExecution create(FlowDescriptor flow, TriggerEvent event, String dedupKey) {
        String payloadJson = event.payload() == null ? null : jsonMapper.writeValueAsString(event.payload());
        if (payloadJson != null) {
            int size = payloadJson.getBytes(StandardCharsets.UTF_8).length;
            if (size > limits.maxTriggerPayloadBytes()) {
                throw new IllegalArgumentException("Trigger payload is " + size
                        + " bytes, exceeding the " + limits.maxTriggerPayloadBytes() + "-byte limit");
            }
        }
        try {
            PipelineExecution execution =
                    stateManager.createExecution(flow.id(), flow.version(), payloadJson, dedupKey);
            log.info("Trigger '{}' from {} started execution {} of flow '{}'",
                    event.type(), event.source(), execution.getId(), flow.id());
            return execution;
        } catch (DataIntegrityViolationException race) {
            // Lost the active-dedup race with a concurrent trigger: return the winner.
            return stateManager.findRecentDuplicate(flow.id(), dedupKey, limits.dedup().window())
                    .orElseThrow(() -> race);
        }
    }

    /**
     * One pre-flight check for every flow the event will start (check-then-act: a
     * concurrent burst can still slightly overshoot the budget, which is accepted).
     */
    private void enforceDailyBudget(List<FlowDescriptor> flows) {
        if (!limits.dailyBudgetEnabled()) {
            return;
        }
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        long today = stateManager.countCreatedSince(startOfDay);
        if (today >= limits.maxExecutionsPerDay()) {
            List<String> flowIds = flows.stream().map(FlowDescriptor::id).toList();
            auditLog.record(null, AuditEventType.EXECUTION_BUDGET_EXCEEDED, null,
                    Map.of("flowIds", flowIds,
                            "limit", limits.maxExecutionsPerDay(),
                            "todayCount", today));
            throw new DailyBudgetExceededException(
                    "Daily execution budget of " + limits.maxExecutionsPerDay()
                            + " reached; refusing new execution for flow(s) " + flowIds);
        }
    }
}
