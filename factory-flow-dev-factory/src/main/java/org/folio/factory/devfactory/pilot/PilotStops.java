package org.folio.factory.devfactory.pilot;

import java.util.Set;

/**
 * Stop states and shared-failure signatures produced by the flow workers and
 * classified by the application's outcome service, so both sides read one
 * authority instead of duplicating string knowledge.
 */
public final class PilotStops {

    /** The whole-attempt budget was exhausted before a stage could run. */
    public static final String DEADLINE_STATE = "DEADLINE_EXCEEDED";
    /** Uncertain ownership or cleanup; the attempt must be inspected, not retried blindly. */
    public static final String INSPECTION_STATE = "NEEDS_INSPECTION";
    /** Fixed operator-guidance suffix of the flow's shared environment failures. */
    public static final String SHARED_ENVIRONMENT_MARKER = "check Developer Flow configuration";
    /** Coding-runtime failure codes that indicate a shared provider or runtime outage. */
    public static final Set<String> SHARED_PROVIDER_FAILURE_CODES =
            Set.of("PROVIDER_FAILED", "RUNTIME_ERROR", "RUNTIME_VERSION");

    private PilotStops() {
    }

    /** True when the recorded failure looks like one shared provider/auth/environment outage. */
    public static boolean sharedFailure(String reason, String codingFailureCode) {
        if (codingFailureCode != null && SHARED_PROVIDER_FAILURE_CODES.contains(codingFailureCode)) {
            return true;
        }
        return reason != null && reason.contains(SHARED_ENVIRONMENT_MARKER);
    }
}
