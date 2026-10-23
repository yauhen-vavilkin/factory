package org.folio.factory.devfactory.pilot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The whole-attempt budget from preparation through delivery, persisted once
 * when preparation starts. Queue and human-review waiting do not consume it
 * and a restart does not reset it. Existing shorter per-operation limits still
 * apply; the deadline is an additional total bound.
 */
public record AttemptDeadline(Instant expiresAt) {

    public AttemptDeadline {
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** Negative once the budget is exhausted. */
    public Duration remaining(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return Duration.between(clock.instant(), expiresAt);
    }

    public boolean expired(Clock clock) {
        return remaining(clock).isZero() || remaining(clock).isNegative();
    }

    /** min(existing per-operation limit, remaining); zero once expired, never negative. */
    public int capSeconds(Clock clock, int perOperationSeconds) {
        if (perOperationSeconds <= 0) {
            throw new IllegalArgumentException("Per-operation timeout must be positive");
        }
        long remainingSeconds = Math.max(0, remaining(clock).toSeconds());
        return Math.toIntExact(Math.min(perOperationSeconds, remainingSeconds));
    }
}
