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
    public static final String OUTPUT_LIMIT_REASON = "A command printed more output than Factory accepts, so"
            + " Factory stopped the attempt. Start a new attempt with narrower instructions, and tell the agent to keep"
            + " the change small and to limit command output.";
    /** Why a coding attempt stopped when the agent changed no file, so there is nothing to verify or deliver. */
    public static final String NO_CHANGES_REASON = "The agent finished without changing any file. Start a new"
            + " attempt with clearer instructions.";

    /** Why an attempt stopped when the engine ran its coding step again after the agent had started. */
    public static final String REPLAYED_CODING_REASON = "Factory restarted or repeated this step while the agent was"
            + " coding, so the work of this attempt is lost. Start a new attempt.";

    /** The start of {@link #startingBuildFailedReason}: the cause, so a list row that shows one sentence has it. */
    public static final String STARTING_BUILD_FAILED_START = "The build of the base branch failed before the agent"
            + " started, so nothing was spent.";

    /** The start of {@link #deadlineReason}, by which a page knows the cause is a limit it can link to. */
    public static final String DEADLINE_START = "The attempt used all of its time before ";

    /** The start of {@link #timeLimitReason}, by which a page knows the cause is a limit it can link to. */
    public static final String TIME_LIMIT_START = "The agent did not finish in ";
    /** The start of {@link #turnLimitReason}. */
    public static final String TURN_LIMIT_START = "The agent reached its limit of ";

    private static final int MAX_FILE_SHOWN = 200;

    /**
     * Why an attempt stopped when the starting build of the base branch failed; {@code summary} is what Factory
     * found in the build output, or blank.
     */
    public static String startingBuildFailedReason(String summary) {
        var found = summary == null || summary.isBlank() ? ""
                : summary.strip() + (summary.strip().endsWith(".") ? " " : ". ");
        return STARTING_BUILD_FAILED_START + " " + found + "Open the run to see the build output, then fix the base"
                + " branch or the check command in config/developer-repositories.yaml.";
    }

    /**
     * Why an attempt stopped when its whole-attempt time ran out before {@code stage}; the page links the limits
     * under it. Time in the queue and waiting for a person does not count.
     */
    public static String deadlineReason(String stage) {
        return DEADLINE_START + stage + ", so Factory stopped it. Start a new attempt with a lower effort or narrower"
                + " instructions, or raise the time limit of the whole attempt.";
    }

    /** Why a coding attempt stopped when the agent did not finish in time; the page links the limits under it. */
    public static String timeLimitReason(long minutes) {
        return TIME_LIMIT_START + minutes + (minutes == 1 ? " minute" : " minutes")
                + ", so Factory stopped it. Start a new attempt with a lower effort or narrower instructions,"
                + " or raise the time limit.";
    }

    /**
     * Why Factory did not keep the agent's changes: a line they add to {@code file} holds the API key of a
     * configured model, which a pull request would publish. No next step, because a PR feedback round offers its
     * own buttons under it. The caller redacts the result.
     */
    public static String secretInChangesCause(String file) {
        var shown = file.length() > MAX_FILE_SHOWN ? file.substring(0, MAX_FILE_SHOWN) + "…" : file;
        return "The agent wrote the API key of a configured model into " + shown
                + ", so Factory did not keep its changes.";
    }

    /** {@link #secretInChangesCause} with the next step of a task attempt. The caller redacts the result. */
    public static String secretInChangesReason(String file) {
        return secretInChangesCause(file) + " Start a new attempt.";
    }

    /** Why a coding attempt stopped when the agent used all of its turns. */
    public static String turnLimitReason(long turns) {
        return TURN_LIMIT_START + turns + (turns == 1 ? " turn" : " turns") + " before it finished."
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
