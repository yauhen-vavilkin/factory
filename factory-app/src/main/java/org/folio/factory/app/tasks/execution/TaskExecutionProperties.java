package org.folio.factory.app.tasks.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * The whole-attempt bounds of a task attempt: forty coding turns and a
 * sixty-minute deadline from preparation through delivery are the defaults.
 * Queue and human-review waiting are excluded. Consumed only by the task
 * wiring, never by the engine. {@code enabled} is the S04 opt-in that gates
 * the task outcome/recovery wiring and, with it, the launcher's refusal of new
 * paid starts while destructive retention is enabled.
 */
@ConfigurationProperties(prefix = "factory.tasks.execution")
public record TaskExecutionProperties(Duration deadline, boolean enabled) {

    public static final Duration DEFAULT_DEADLINE = Duration.ofMinutes(60);

    @ConstructorBinding
    public TaskExecutionProperties {
        deadline = deadline == null || deadline.isZero() || deadline.isNegative()
                ? DEFAULT_DEADLINE
                : deadline;
    }

    public TaskExecutionProperties(Duration deadline) {
        this(deadline, false);
    }
}
