package org.folio.factory.core.registry.model;

import java.util.List;

public record RetryPolicy(int maxAttempts, List<Long> backoffSeconds) {

    public static final int DEFAULT_ATTEMPTS = 3;
    public static final List<Long> DEFAULT_BACKOFF = List.of(30L, 120L, 300L);
    // Declared after the defaults: the constructor reads them while the class initialises.
    public static final RetryPolicy DEFAULT = new RetryPolicy(DEFAULT_ATTEMPTS, DEFAULT_BACKOFF);

    public RetryPolicy {
        if (maxAttempts <= 0) {
            maxAttempts = DEFAULT_ATTEMPTS;
        }
        backoffSeconds = backoffSeconds == null || backoffSeconds.isEmpty()
                ? DEFAULT_BACKOFF
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
