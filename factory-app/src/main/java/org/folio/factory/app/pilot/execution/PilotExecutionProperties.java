package org.folio.factory.app.pilot.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The M1 sprint pilot's whole-attempt bounds: forty coding turns and a
 * sixty-minute deadline from preparation through delivery are the defaults.
 * Queue and human-review waiting are excluded. Consumed only by the pilot
 * wiring, never by the engine.
 */
@ConfigurationProperties(prefix = "factory.pilot.execution")
public record PilotExecutionProperties(Duration deadline) {

    public static final Duration DEFAULT_DEADLINE = Duration.ofMinutes(60);

    public PilotExecutionProperties {
        deadline = deadline == null || deadline.isZero() || deadline.isNegative()
                ? DEFAULT_DEADLINE
                : deadline;
    }
}
