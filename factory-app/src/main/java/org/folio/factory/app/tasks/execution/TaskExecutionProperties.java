package org.folio.factory.app.tasks.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The whole-attempt bounds of a task attempt: the deadline from preparation
 * through delivery (60 minutes by default). Queue and human-review waiting are
 * excluded. Consumed only by the task wiring, never by the engine.
 * {@code enabled} is the opt-in that gates
 * the task outcome and dispatch wiring and, with it, the launcher's refusal of new
 * paid starts while destructive retention is enabled.
 */
@ConfigurationProperties(prefix = "factory.tasks.execution")
public record TaskExecutionProperties(Duration deadline, boolean enabled) {

    private static final Duration DEFAULT_DEADLINE = Duration.ofMinutes(60);

    public TaskExecutionProperties {
        deadline = deadline == null || deadline.isZero() || deadline.isNegative()
                ? DEFAULT_DEADLINE
                : deadline;
    }
}
