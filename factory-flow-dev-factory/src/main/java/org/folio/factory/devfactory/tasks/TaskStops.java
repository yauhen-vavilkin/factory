package org.folio.factory.devfactory.tasks;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import java.util.Set;

/**
 * Stop states and shared-failure signatures produced by the flow workers and
 * classified by the application's outcome service, so both sides read one
 * authority instead of duplicating string knowledge.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class TaskStops {

    /** The whole-attempt budget was exhausted before a stage could run. */
    public static final String DEADLINE_STATE = "DEADLINE_EXCEEDED";
    /** Uncertain ownership or cleanup; the attempt must be inspected, not retried blindly. */
    public static final String INSPECTION_STATE = "NEEDS_INSPECTION";
    /** Start of the stop reason when the coding agent asked a question in a task run. */
    public static final String DECISION_REASON = "coding requested a human decision: ";
    /** Fixed operator-guidance suffix of the flow's shared environment failures. */
    public static final String SHARED_ENVIRONMENT_MARKER = "check Developer Flow configuration";
    /** Coding-runtime failure codes that indicate a shared provider or runtime outage. */
    public static final Set<String> SHARED_PROVIDER_FAILURE_CODES =
            Set.of("PROVIDER_FAILED", "RUNTIME_ERROR", "RUNTIME_VERSION");

    /** True when the recorded stop reason, "stage: reason", is the agent's question. */
    public static boolean asksForAnswer(String reason) {
        if (reason == null) {
            return false;
        }
        var separator = reason.indexOf(": ");
        return separator > 0 && reason.startsWith(DECISION_REASON, separator + 2);
    }

    /** True when the recorded failure looks like one shared provider/auth/environment outage. */
    public static boolean sharedFailure(String reason, String codingFailureCode) {
        if (codingFailureCode != null && SHARED_PROVIDER_FAILURE_CODES.contains(codingFailureCode)) {
            return true;
        }
        return reason != null && reason.contains(SHARED_ENVIRONMENT_MARKER);
    }
}
