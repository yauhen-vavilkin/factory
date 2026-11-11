package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import lombok.AccessLevel;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.devfactory.DevFactoryConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared formatting and request-parsing helpers for the server-rendered views.
 * All methods are pure so the controllers stay thin and consistent.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class UiFormat {

    /** Longest message carried in a redirect; a remote error body could otherwise overflow the headers. */
    static final int MAX_REDIRECT_MESSAGE = 300;

    static String abbreviate(String value) {
        return value.length() <= 13 ? value : value.substring(0, 10) + "...";
    }

    /** One cost format for every page: {@code $0.70} for USD, {@code 0.70 EUR} for other currencies. */
    public static String money(BigDecimal amount, String currency) {
        var rounded = amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return "USD".equals(currency) ? "$" + rounded : rounded + " " + currency;
    }

    /** Costs in several currencies joined with " + "; null when there is no cost. */
    public static String money(Map<String, BigDecimal> amountsByCurrency) {
        if (amountsByCurrency.isEmpty()) {
            return null;
        }
        return amountsByCurrency.entrySet().stream()
                .map(entry -> money(entry.getValue(), entry.getKey()))
                .collect(Collectors.joining(" + "));
    }

    /** Groups a count with thousands separators — token totals reach seven figures. */
    public static String count(long value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(value);
    }

    /** Compact elapsed time for live UI clocks: seconds, then minutes, then hours. */
    /** "9 s", "5 min", "1 h 20 min": the words the run page uses, and poll.js ticks in, for a time span. */
    static String duration(long seconds) {
        long safe = Math.max(0, seconds);
        if (safe < 60) {
            return safe + " s";
        }
        long minutes = safe / 60;
        if (minutes < 60) {
            return minutes + " min";
        }
        long remainingMinutes = minutes % 60;
        return minutes / 60 + " h" + (remainingMinutes == 0 ? "" : " " + remainingMinutes + " min");
    }

    /** Milliseconds under one second, the compact duration from one second up. */
    static String milliseconds(long value) {
        return value < 1000 ? value + " ms" : duration(value / 1000);
    }

    /** Lenient read of stored JSON text: null or unreadable text yields an empty object. */
    static JsonNode parse(String value, JsonMapper jsonMapper) {
        try {
            return value == null ? jsonMapper.createObjectNode() : jsonMapper.readTree(value);
        } catch (RuntimeException ignored) {
            return jsonMapper.createObjectNode();
        }
    }

    /** The Definition of a flow: the Developer Flow page opens on My tasks, so it needs its Definition tab. */
    static String flowDefinitionUrl(String flowId) {
        return DevFactoryConfiguration.FLOW_ID.equals(flowId) ? "/flows/" + flowId + "?view=definition"
                : "/flows/" + flowId;
    }

    static String stepLabel(StepDescriptor step) {
        return switch (step.type()) {
            case AGENT -> step.workerId();
            case HITL_GATE -> step.gate().title();
            case SUB_FLOW -> "Sub-flow: " + step.subFlow().flowId();
        };
    }

    /** Lenient enum parse: blank yields null, and any unknown value yields null rather than throwing. */
    static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String errorRedirect(String path, String message) {
        String text = message == null ? "Request failed" : message.strip();
        if (text.length() > MAX_REDIRECT_MESSAGE) {
            text = text.substring(0, MAX_REDIRECT_MESSAGE - 1) + "…";
        }
        return "redirect:" + path + "?error=" + encode(text);
    }

    /** The one row shape the {@code auditTable} fragment renders, wherever it appears. */
    static Map<String, Object> auditRow(AuditEvent event, JsonMapper jsonMapper) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("occurredAt", PageTime.timestamp(event.getOccurredAt()));
        row.put("eventType", event.getEventType().name());
        row.put("eventLabel", eventLabel(event.getEventType()));
        row.put("eventTone", eventTone(event.getEventType()));
        row.put("stepId", event.getStepId());
        row.put("actor", event.getActor());
        row.put("executionId", event.getExecutionId());
        row.put("executionShort", event.getExecutionId() == null ? null
                : abbreviate(event.getExecutionId().toString()));
        row.put("detail", prettyDetail(event.getDetail(), jsonMapper));
        return row;
    }

    /** Human label for an audit event: hand-named where the enum reads poorly, sentence case otherwise. */
    static String eventLabel(AuditEventType type) {
        return switch (type) {
            case HITL_REQUESTED -> "Review requested";
            case HITL_DECIDED -> "Review decided";
            case SUBFLOW_INVOKED -> "Sub-flow invoked";
            case SUBFLOW_RETURNED -> "Sub-flow returned";
            default -> {
                String words = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
                yield Character.toUpperCase(words.charAt(0)) + words.substring(1);
            }
        };
    }

    /** Badge tone for an audit event, or null for the neutral outline badge. */
    static String eventTone(AuditEventType type) {
        return switch (type) {
            case STEP_FAILED, ESCALATED, EXECUTION_BUDGET_EXCEEDED -> "destructive";
            case RETRY_SCHEDULED -> "warning";
            case EXECUTION_COMPLETED -> "success";
            default -> null;
        };
    }

    private static String prettyDetail(String detail, JsonMapper jsonMapper) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        JsonNode node = jsonMapper.readTree(detail);
        return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }
}
