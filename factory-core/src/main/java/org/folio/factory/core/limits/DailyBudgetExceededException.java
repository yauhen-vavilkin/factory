package org.folio.factory.core.limits;

/**
 * Thrown by the router when today's execution count has reached the configured
 * daily budget ({@code factory.limits.max-executions-per-day}). Surfaced to API
 * clients as HTTP 429 by the app's exception handler.
 */
public class DailyBudgetExceededException extends RuntimeException {

    public DailyBudgetExceededException(String message) {
        super(message);
    }
}
