package org.folio.factory.core.registry.model;

import java.util.List;

public record RetryPolicy(int maxAttempts, List<Long> backoffSeconds) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(3, List.of(30L, 120L, 300L));

    public RetryPolicy {
        if (maxAttempts <= 0) {
            maxAttempts = 3;
        }
        backoffSeconds = backoffSeconds == null || backoffSeconds.isEmpty()
                ? List.of(30L, 120L, 300L)
                : List.copyOf(backoffSeconds);
    }

    /**
     * Backoff before retry attempt {@code attempt} (1-based). Attempts beyond the
     * configured list reuse the last entry.
     */
    public long backoffFor(int attempt) {
        int index = Math.min(Math.max(attempt, 1), backoffSeconds.size()) - 1;
        return backoffSeconds.get(index);
    }
}
