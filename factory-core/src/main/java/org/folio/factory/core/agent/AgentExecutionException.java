package org.folio.factory.core.agent;

/**
 * Failure of an agent worker step. The engine counts it against the flow's retry
 * budget and escalates to a human when the budget is exhausted.
 */
public class AgentExecutionException extends RuntimeException {

    public AgentExecutionException(String message) {
        super(message);
    }

    public AgentExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
