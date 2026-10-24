package org.folio.factory.app.pilot.startup;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bounds for the explicit pilot startup smoke: one tiny tool-using model call
 * from the pinned coding image, five turns and two minutes by default. The
 * smoke is never repeated on an unchanged restart; these bounds are part of the
 * reusable evidence identity.
 */
@ConfigurationProperties(prefix = "factory.pilot.startup")
public record PilotStartupProperties(Integer smokeMaxTurns, Duration smokeTimeout) {

    public static final int DEFAULT_SMOKE_MAX_TURNS = 5;
    public static final Duration DEFAULT_SMOKE_TIMEOUT = Duration.ofMinutes(2);

    public PilotStartupProperties {
        smokeMaxTurns = smokeMaxTurns == null || smokeMaxTurns <= 0
                ? DEFAULT_SMOKE_MAX_TURNS
                : smokeMaxTurns;
        smokeTimeout = smokeTimeout == null || smokeTimeout.isZero() || smokeTimeout.isNegative()
                ? DEFAULT_SMOKE_TIMEOUT
                : smokeTimeout;
    }

    public long smokeTimeoutSeconds() {
        return smokeTimeout.toSeconds();
    }
}
