package org.folio.factory.core.limits;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Cost-control and size limits for the control plane. Flow-agnostic and
 * config-driven, bound from {@code factory.limits.*}.
 *
 * <p>Cost guards are individually gated: a zero numeric limit disables that guard,
 * and dedup has its own flag. Size caps are non-destructive (they only reject
 * oversize new writes), so they default to generous, always-on limits.</p>
 */
@ConfigurationProperties(prefix = "factory.limits")
public record LimitsProperties(
        Integer maxConcurrentExecutions,
        Integer maxExecutionsPerDay,
        Integer maxArtifactBytes,
        Integer maxTriggerPayloadBytes,
        Dedup dedup) {

    public LimitsProperties {
        maxConcurrentExecutions = maxConcurrentExecutions == null ? 0 : maxConcurrentExecutions;
        maxExecutionsPerDay = maxExecutionsPerDay == null ? 0 : maxExecutionsPerDay;
        maxArtifactBytes = maxArtifactBytes == null ? 5_000_000 : maxArtifactBytes;
        maxTriggerPayloadBytes = maxTriggerPayloadBytes == null ? 262_144 : maxTriggerPayloadBytes;
        dedup = dedup == null ? new Dedup(null, null, null) : dedup;
    }

    /** True when the concurrency cap should be enforced. */
    public boolean concurrencyCapped() {
        return maxConcurrentExecutions > 0;
    }

    /** True when the daily execution budget should be enforced. */
    public boolean dailyBudgetEnabled() {
        return maxExecutionsPerDay > 0;
    }

    /**
     * Deduplication of near-simultaneous re-fired triggers.
     *
     * @param enabled    master switch for dedup
     * @param window     how far back a prior execution with the same key still
     *                   suppresses a new one
     * @param idPointers JSON pointers into the trigger payload, tried in order; the
     *                   first that resolves to a non-blank scalar is the stable id.
     *                   When none resolve, a sha256 of the serialized payload is used.
     */
    public record Dedup(Boolean enabled, Duration window, List<String> idPointers) {
        public Dedup {
            enabled = enabled == null || enabled;
            window = window == null ? Duration.ofMinutes(10) : window;
            idPointers = idPointers == null ? List.of() : List.copyOf(idPointers);
        }
    }
}
