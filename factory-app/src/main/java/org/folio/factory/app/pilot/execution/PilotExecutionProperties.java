package org.folio.factory.app.pilot.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * The M1 sprint pilot's whole-attempt bounds: forty coding turns and a
 * sixty-minute deadline from preparation through delivery are the defaults.
 * Queue and human-review waiting are excluded. Consumed only by the pilot
 * wiring, never by the engine. {@code enabled} is the S04 opt-in that gates
 * the pilot outcome/recovery wiring and, with it, the launcher's refusal of new
 * paid starts while destructive retention is enabled.
 */
@ConfigurationProperties(prefix = "factory.pilot.execution")
public record PilotExecutionProperties(Duration deadline, boolean enabled) {

    public static final Duration DEFAULT_DEADLINE = Duration.ofMinutes(60);

    @ConstructorBinding
    public PilotExecutionProperties {
        deadline = deadline == null || deadline.isZero() || deadline.isNegative()
                ? DEFAULT_DEADLINE
                : deadline;
    }

    public PilotExecutionProperties(Duration deadline) {
        this(deadline, false);
    }
}
