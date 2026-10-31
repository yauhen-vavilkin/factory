package org.folio.factory.app.web;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.devfactory.runtime.TokenUsage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Immutable audit snapshots are grouped per actual coding attempt, never just per step. */
public final class AttemptUsageSummary {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static class Attempt {
        String model = "";
        JsonNode usage = JSON.createObjectNode();
        JsonNode rate = JSON.createObjectNode();
        boolean complete;
    }

    static Map<String, Object> build(List<AuditEvent> events, Integer round) {
        return build(events, round, false);
    }

    static Map<String, Object> build(List<AuditEvent> events, Integer round, boolean live) {
        return new Projection(events).summary(round, live);
    }

    /** Typed per-currency totals of one usage interpretation, before any display formatting. */
    public record Totals(
            boolean present,
            long tokens,
            boolean tokensReported,
            Map<AttemptCharge.Category, Long> breakdown,
            SortedMap<String, BigDecimal> costByCurrency,
            boolean partial,
            boolean provisional,
            int attempts,
            List<String> models,
            List<String> rateSources) {
    }

    /** Typed totals across every coding attempt in the events, priced exactly once each. */
    public static Totals totals(List<AuditEvent> events, boolean live) {
        return new Projection(events).totals(live);
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

        public Totals totals(boolean live) {
            return all.totals(live);
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
                    && !TokenUsage.from(d).hasCodingTokens()) return;
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

        Totals totals(boolean live) {
            var models = new LinkedHashSet<String>();
            var sources = new LinkedHashSet<String>();
            var sum = new ChargeSum(legacyUnknown);
            for (var a : attempts.values()) {
                if (!a.model.isBlank()) models.add(a.model);
                var source = rateSource(a.rate);
                if (!source.isBlank()) sources.add(source);
                sum.add(AttemptCharge.calculate(a.usage, a.rate, a.complete), live && !a.complete);
            }
            return new Totals(
                    !attempts.isEmpty() || legacyUnknown,
                    sum.tokens,
                    sum.tokensReported,
                    Collections.unmodifiableMap(sum.breakdown),
                    Collections.unmodifiableSortedMap(sum.costs),
                    sum.partial,
                    sum.provisional,
                    attempts.size(),
                    List.copyOf(models),
                    List.copyOf(sources));
        }

        /** Blank when the rate names no source. */
        private static String rateSource(JsonNode rate) {
            var source = rate.path("source").asString("");
            var asOf = rate.path("asOf").asString("");
            if (source.isBlank() || asOf.isBlank()) {
                return source;
            }
            return source + " (" + asOf + ")";
        }

        Map<String, Object> summary(boolean live) {
            var t = totals(live);
            var tokensDisplay = t.tokensReported() || !t.present() ? UiFormat.count(t.tokens()) : "—";
            return Map.of(
                    "present", t.present(),
                    "cost", costDisplay(t),
                    "partial", t.partial(),
                    "tokens", t.tokens(),
                    "tokensDisplay", tokensDisplay,
                    "provisional", t.provisional(),
                    "models", String.join(", ", t.models()),
                    "attempts", t.attempts(),
                    "sources", t.rateSources(),
                    "breakdown", breakdownCounts(t.breakdown()));
        }

        private static String costDisplay(Totals t) {
            var cost = t.costByCurrency().entrySet().stream()
                    .map(e -> e.getValue().setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                            + " " + e.getKey())
                    .collect(Collectors.joining(" + "));
            if (!cost.isBlank()) {
                return cost;
            }
            return t.present() ? "—" : "0";
        }

        private static Map<String, Long> breakdownCounts(Map<AttemptCharge.Category, Long> breakdown) {
            return Map.of(
                    "input", breakdown.getOrDefault(AttemptCharge.Category.INPUT, 0L),
                    "cacheRead", breakdown.getOrDefault(AttemptCharge.Category.CACHE_READ, 0L),
                    "cacheWrite", breakdown.getOrDefault(AttemptCharge.Category.CACHE_WRITE, 0L),
                    "output", breakdown.getOrDefault(AttemptCharge.Category.OUTPUT, 0L));
        }
    }

    /** Running sums over the priced attempts of one group. */
    private static final class ChargeSum {
        private final Map<AttemptCharge.Category, Long> breakdown = new EnumMap<>(AttemptCharge.Category.class);
        private final SortedMap<String, BigDecimal> costs = new TreeMap<>();
        private long tokens;
        private boolean tokensReported;
        private boolean partial;
        private boolean provisional;

        ChargeSum(boolean partial) {
            this.partial = partial;
        }

        void add(AttemptCharge charge, boolean provisionalAttempt) {
            provisional |= provisionalAttempt;
            tokensReported |= !charge.breakdown().isEmpty();
            tokens += charge.tokens();
            charge.breakdown().forEach((category, count) -> breakdown.merge(category, count, Long::sum));
            // Interrupted attempts retain their observed subtotal, even when pricing is incomplete.
            if (charge.subtotalPresent()) {
                costs.merge(charge.currency(), charge.cost(), BigDecimal::add);
            }
            if (!charge.known()) {
                partial = true;
            }
        }
    }

    public static long activeSeconds(List<AuditEvent> events, Instant now) {
        Instant start = null;
        long millis = 0;
        for (var e : events) {
            // Only one step is active per execution. HITL and recovery end an abandoned interval.
            if (e.getEventType() == AuditEventType.STEP_STARTED) {
                if (start != null) millis += elapsedMillis(start, e.getOccurredAt());
                start = e.getOccurredAt();
            }
            if (endsActiveInterval(e) && start != null) {
                millis += elapsedMillis(start, e.getOccurredAt());
                start = null;
            }
        }
        if (start != null) millis += elapsedMillis(start, now);
        return millis / 1000;
    }

    private static boolean endsActiveInterval(AuditEvent event) {
        return switch (event.getEventType()) {
            case STEP_COMPLETED, STEP_FAILED, HITL_REQUESTED, EXECUTION_COMPLETED -> true;
            case STATE_TRANSITION -> !JSON.readTree(event.getDetail() == null ? "{}" : event.getDetail())
                    .path("to").asString("").equals("RUNNING");
            default -> false;
        };
    }

    private static long elapsedMillis(Instant start, Instant end) {
        return Math.max(0, Duration.between(start, end).toMillis());
    }
}
