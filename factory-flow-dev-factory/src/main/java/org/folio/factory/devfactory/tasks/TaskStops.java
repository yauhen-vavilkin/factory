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
            + " Factory stopped the run. Start a new run with narrower instructions, and tell the agent to keep"
            + " the change small and to limit command output.";
    /** Why a coding attempt stopped when the agent changed no file, so there is nothing to verify or deliver. */
    public static final String NO_CHANGES_REASON = "The agent finished without changing any file. Start a new"
            + " run with clearer instructions.";

    /** Why an attempt stopped when the engine ran its coding step again after the agent had started. */
    public static final String REPLAYED_CODING_REASON = "Factory restarted or repeated this step while the agent was"
            + " coding, so the work of this run is lost. Start a new run.";

    /** The start of {@link #startingBuildFailedReason}: the cause, so a list row that shows one sentence has it. */
    public static final String STARTING_BUILD_FAILED_START = "The build of the base branch failed before the agent"
            + " started, so nothing was spent.";

    /**
     * The start of {@link #codingInstallFailedReason} and {@link #feedbackInstallFailedCause}: the cause. It says
     * nothing about spend, because a run that continues after an answer has already spent.
     */
    public static final String CODING_INSTALL_FAILED_START = "The dependency install in the agent's container"
            + " failed, so the agent did not start.";

    /** The start of {@link #lockfileNotPinnedReason}: the cause, so a list row that shows one sentence has it. */
    public static final String LOCKFILE_NOT_PINNED_START = "The build of the base branch made a yarn.lock that"
            + " Factory could not use, so the agent did not start and nothing was spent.";

    /** The start of {@link #deadlineReason}, by which a page knows the cause is a limit it can link to. */
    public static final String DEADLINE_START = "The run used all of its time before ";

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
        return STARTING_BUILD_FAILED_START + " " + found(summary) + "Open the run to see the build output, then fix"
                + " the base branch or the check command in config/developer-repositories.yaml.";
    }

    /**
     * Why a task attempt stopped when the dependency install in the agent's container failed; {@code error} is
     * yarn's last error or the exit code. The starting build installed the same dependencies in the build image.
     */
    public static String codingInstallFailedReason(String error) {
        return CODING_INSTALL_FAILED_START + " " + found(error) + "Check that the agent's container can reach the npm"
                + " registry and that its image has the Node version the module needs, then start a new run.";
    }

    /**
     * {@link #codingInstallFailedReason} for a PR feedback round, whose pull request may hold the change that breaks
     * the install. No new run, because the round offers its own buttons under it.
     */
    public static String feedbackInstallFailedCause(String error) {
        return CODING_INSTALL_FAILED_START + " " + found(error) + "Fix package.json or yarn.lock on the pull request"
                + " branch, or check that the agent's container can reach the npm registry.";
    }

    /** Why an attempt stopped when the yarn.lock that the starting build generated was unusable. */
    public static String lockfileNotPinnedReason(String error) {
        return LOCKFILE_NOT_PINNED_START + " " + found(error) + "Start a new run, and if it stops the same way, add a"
                + " yarn.lock to the base branch.";
    }

    /**
     * Why the checks could not run when the dependency install of the agent's changes failed, after "Checks could
     * not run: " on a page; {@code error} is yarn's last error. No next step, as in {@link #secretInChangesCause}.
     */
    public static String checksInstallFailedCause(String error) {
        return ("the dependency install of the agent's changes failed. " + found(error)).strip();
    }

    /** {@link #checksInstallFailedCause} with the next step of a task attempt. */
    public static String checksInstallFailedReason(String error) {
        return checksInstallFailedCause(error) + " Start a new run and tell the agent to update yarn.lock with every"
                + " change to package.json and to use only packages that the npm registry has.";
    }

    /** What Factory found in the output, as a sentence followed by a space; blank when it found nothing. */
    private static String found(String summary) {
        return summary == null || summary.isBlank() ? ""
                : summary.strip() + (summary.strip().endsWith(".") ? " " : ". ");
    }

    /**
     * Why an attempt stopped when its whole-attempt time ran out before {@code stage}; the page links the limits
     * under it. Time in the queue and waiting for a person does not count.
     */
    public static String deadlineReason(String stage) {
        return DEADLINE_START + stage + ", so Factory stopped it. Start a new run with a lower effort or narrower"
                + " instructions, or raise the time limit of the whole run.";
    }

    /** Why a coding attempt stopped when the agent did not finish in time; the page links the limits under it. */
    public static String timeLimitReason(long minutes) {
        return TIME_LIMIT_START + minutes + (minutes == 1 ? " minute" : " minutes")
                + ", so Factory stopped it. Start a new run with a lower effort or narrower instructions,"
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
        return secretInChangesCause(file) + " Start a new run.";
    }

    /** Why a coding attempt stopped when the agent used all of its turns. */
    public static String turnLimitReason(long turns) {
        return TURN_LIMIT_START + turns + (turns == 1 ? " turn" : " turns") + " before it finished."
                + " Start a new run with narrower instructions.";
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
