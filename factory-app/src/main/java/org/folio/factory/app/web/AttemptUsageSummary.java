package org.folio.factory.app.web;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Immutable audit snapshots are grouped per actual coding attempt, never just per step. */
final class AttemptUsageSummary {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static class Attempt {
        String model = "";
        JsonNode usage = JSON.createObjectNode(), rate = JSON.createObjectNode();
        boolean complete;
    }

    static Map<String, Object> build(List<AuditEvent> events, Integer round) {
        return build(events, round, false);
    }

    static Map<String, Object> build(List<AuditEvent> events, Integer round, boolean live) {
        return new Projection(events).summary(round, live);
    }

    /** One interpretation pass per request, reused by totals and every feedback round. */
    static final class Projection {
        private final Group all = new Group();
        private final Map<Integer, Group> rounds = new LinkedHashMap<>();
        private final Map<Integer, List<Map<String, String>>> activities = new LinkedHashMap<>();

        Projection(List<AuditEvent> events) {
            for (var event : events) {
                var detail = JSON.readTree(event.getDetail() == null ? "{}" : event.getDetail());
                int round = detail.path("round").asInt(-1);
                all.record(event, detail);
                rounds.computeIfAbsent(round, key -> new Group()).record(event, detail);
                String text = detail.path("message").asString(detail.path("activity").asString(""));
                if (!text.isBlank() && !text.equals("coding_usage") && !text.equals("pi_usage"))
                    activities
                            .computeIfAbsent(round, key -> new ArrayList<>())
                            .add(
                                    Map.of(
                                            "time",
                                            UiFormat.format(event.getOccurredAt()),
                                            "text",
                                            text));
            }
        }

        Map<String, Object> summary(Integer round, boolean live) {
            return (round == null ? all : rounds.getOrDefault(round, new Group())).summary(live);
        }

        List<Map<String, String>> activity(int round) {
            return List.copyOf(activities.getOrDefault(round, List.of()));
        }
    }

    private static final class Group {
        private final Map<String, Attempt> attempts = new LinkedHashMap<>();
        private boolean legacyUnknown;

        void record(AuditEvent event, JsonNode d) {
            String id = d.path("attemptId").asString("");
            String activity = d.path("activity").asString("");
            if (!activity.equals("coding_starting")
                    && !activity.equals("coding_usage")
                    && !activity.equals("pi_usage")
                    && event.getEventType() != AuditEventType.STEP_COMPLETED) return;
            if (event.getEventType() == AuditEventType.STEP_COMPLETED
                    && !d.has("inputTokens")
                    && !d.has("outputTokens")) return;
            if (id.isBlank()) {
                legacyUnknown = true;
                return;
            }
            var a = attempts.computeIfAbsent(id, k -> new Attempt());
            if (a.model.isBlank() && d.hasNonNull("model")) a.model = d.path("model").asString();
            if (a.rate.isEmpty() && d.has("rate")) a.rate = d.path("rate");
            boolean terminal = event.getEventType() == AuditEventType.STEP_COMPLETED;
            if (terminal
                    || !a.complete
                            && (activity.equals("coding_usage") || activity.equals("pi_usage")))
                a.usage = d;
            a.complete |= terminal;
        }

        Map<String, Object> summary(boolean live) {
            var models = new LinkedHashSet<String>();
            var totals = new TreeMap<String, BigDecimal>();
            var sources = new LinkedHashSet<String>();
            long tokens = 0;
            var breakdown = new EnumMap<AttemptCharge.Category, Long>(AttemptCharge.Category.class);
            boolean partial = legacyUnknown, provisional = false, tokensReported = false;
            for (var entry : attempts.entrySet()) {
                var a = entry.getValue();
                if (!a.model.isBlank()) models.add(a.model);
                String source = a.rate.path("source").asString("");
                String asOf = a.rate.path("asOf").asString("");
                if (!source.isBlank())
                    sources.add(asOf.isBlank() ? source : source + " (" + asOf + ")");
                var charge = AttemptCharge.calculate(a.usage, a.rate, a.complete);
                provisional |= live && !a.complete;
                tokensReported |= !charge.breakdown().isEmpty();
                tokens += charge.tokens();
                charge.breakdown()
                        .forEach((category, count) -> breakdown.merge(category, count, Long::sum));
                // Interrupted attempts retain their observed subtotal, even when pricing is
                // incomplete.
                if (charge.subtotalPresent())
                    totals.merge(charge.currency(), charge.cost(), BigDecimal::add);
                if (!charge.known()) partial = true;
            }
            String cost =
                    totals.entrySet().stream()
                            .map(
                                    e ->
                                            e.getValue()
                                                            .setScale(6, RoundingMode.HALF_UP)
                                                            .stripTrailingZeros()
                                                            .toPlainString()
                                                    + " "
                                                    + e.getKey())
                            .collect(Collectors.joining(" + "));
            if (cost.isBlank()) cost = attempts.isEmpty() && !legacyUnknown ? "0" : "—";
            String tokensDisplay =
                    tokensReported || attempts.isEmpty() && !legacyUnknown
                            ? UiFormat.count(tokens)
                            : "—";
            return Map.of(
                    "present",
                    !attempts.isEmpty() || legacyUnknown,
                    "cost",
                    cost,
                    "partial",
                    partial,
                    "tokens",
                    tokens,
                    "tokensDisplay",
                    tokensDisplay,
                    "provisional",
                    provisional,
                    "models",
                    String.join(", ", models),
                    "attempts",
                    attempts.size(),
                    "sources",
                    List.copyOf(sources),
                    "breakdown",
                    Map.of(
                            "input",
                            breakdown.getOrDefault(AttemptCharge.Category.INPUT, 0L),
                            "cacheRead",
                            breakdown.getOrDefault(AttemptCharge.Category.CACHE_READ, 0L),
                            "cacheWrite",
                            breakdown.getOrDefault(AttemptCharge.Category.CACHE_WRITE, 0L),
                            "output",
                            breakdown.getOrDefault(AttemptCharge.Category.OUTPUT, 0L)));
        }
    }

    static long activeSeconds(List<AuditEvent> events, Instant now) {
        Instant start = null;
        long millis = 0;
        for (var e : events) {
            // Only one step is active per execution. HITL and recovery end an abandoned interval.
            if (e.getEventType() == AuditEventType.STEP_STARTED) {
                if (start != null)
                    millis += Math.max(0, Duration.between(start, e.getOccurredAt()).toMillis());
                start = e.getOccurredAt();
            }
            boolean stop =
                    e.getEventType() == AuditEventType.STEP_COMPLETED
                            || e.getEventType() == AuditEventType.STEP_FAILED
                            || e.getEventType() == AuditEventType.HITL_REQUESTED
                            || e.getEventType() == AuditEventType.EXECUTION_COMPLETED;
            if (e.getEventType() == AuditEventType.STATE_TRANSITION) {
                String to =
                        JSON.readTree(e.getDetail() == null ? "{}" : e.getDetail())
                                .path("to")
                                .asString("");
                stop |= !to.equals("RUNNING");
            }
            if (stop && start != null) {
                millis += Math.max(0, Duration.between(start, e.getOccurredAt()).toMillis());
                start = null;
            }
        }
        if (start != null) millis += Math.max(0, Duration.between(start, now).toMillis());
        return millis / 1000;
    }
}
