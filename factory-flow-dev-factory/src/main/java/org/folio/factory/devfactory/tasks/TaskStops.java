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
    /** Why a coding attempt stopped when a command printed more than the output limit of Factory. */
    public static final String OUTPUT_LIMIT_REASON = "A command run by the agent printed more output than Factory"
            + " accepts, so Factory stopped the attempt. Start a new attempt and tell the agent to limit command"
            + " output, for example with \"| tail -n 100\".";

    /** Why a coding attempt stopped when the agent did not finish in time. */
    public static String timeLimitReason(long minutes) {
        return "The agent did not finish in " + minutes + (minutes == 1 ? " minute" : " minutes")
                + ", so Factory stopped it. Start a new attempt with a lower effort or narrower instructions,"
                + " or raise the time limit (see Configuration).";
    }

    /** Why a coding attempt stopped when the agent used all of its turns. */
    public static String turnLimitReason(long turns) {
        return "The agent reached its limit of " + turns + " turns before it finished."
                + " Start a new attempt with narrower instructions.";
    }

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
