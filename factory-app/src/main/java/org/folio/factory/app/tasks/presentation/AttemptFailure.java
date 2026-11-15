package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.tasks.execution.StopReasons;
import org.folio.factory.app.tasks.execution.TaskOutcomeService;
import org.folio.factory.devfactory.runtime.Processes;
import org.folio.factory.devfactory.tasks.TaskStops;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The words of a failed attempt, the same on the task page, the run page and a Runs row: a short headline and
 * the reason. The flow layers add prefixes to a stored reason ("shared: ", the stage, "Development stopped: ");
 * the reason drops them, also in reasons stored by older runs, and says the known causes in plain words.
 */
public record AttemptFailure(String headline, String reason) {

    private static final String NO_REASON = "No reason was recorded.";
    private static final String RUN_ENDED = "The run stopped before a step could say why. Open the run to see"
            + " where it stopped, then start a new attempt.";
    private static final String MARKED_STOPPED = "You marked the attempt as stopped";
    private static final String IMPLEMENTATION = "Implementation";
    private static final String OLD_NO_CHANGES = "Candidate contains no changes";
    private static final String OLD_STARTING_BUILD_FAILED = "Starting build failed before model spend";
    /** Older runs named the attempt id in this text. */
    private static final String OLD_REPLAYED_CODING = "Paid coding was already started for task attempt";
    private static final Pattern OLD_DEADLINE = Pattern.compile("^the whole-attempt deadline expired before ([^;]+);");
    private static final String LIMITS_URL = "/developer/configuration#limits";
    private static final List<String> LAYER_PREFIXES =
            List.of("shared: ", "Development stopped: ", "Implementation failed: ");
    private static final Pattern LEADING_STAGE = Pattern.compile("^([A-Za-z][A-Za-z_-]*): (.+)$", Pattern.DOTALL);
    /** The wordings of a time limit that older runs stored, also the one that ended "(see Configuration)". */
    private static final Pattern OLD_TIME_LIMIT = Pattern.compile("^(?:Claude Code did not finish within the time"
            + " limit of|" + Pattern.quote(TaskStops.TIME_LIMIT_START.strip()) + ") (\\d+) minutes?\\b");
    /** The wordings of a turn limit that older runs stored, also "limit of 1 turns" before the grammar fix. */
    private static final Pattern OLD_TURN_LIMIT = Pattern.compile("^(?:Claude Code (?:reached the (\\d+)-turn limit"
            + "|used all (\\d+) turns)\\b|The agent reached its limit of (\\d+) turns? before it finished\\.)");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z\"])");

    /** "Attempt 3 failed" and the reason of that attempt. */
    public static AttemptFailure of(int ordinal, String storedReason) {
        return new AttemptFailure("Attempt " + ordinal + " failed", reason(storedReason));
    }

    /**
     * One or two plain sentences that end with a full stop. A reason this method does not know passes through
     * without the layer prefixes; a stage other than the implementation stays as "Publication failed: ", and
     * failed checks read "Checks failed: " or "Checks could not run: ".
     */
    public static String reason(String storedReason) {
        if (storedReason == null || storedReason.isBlank()) {
            return NO_REASON;
        }
        var text = withoutLayerPrefixes(storedReason.strip());
        var stage = LEADING_STAGE.matcher(text);
        if (!stage.matches() || !isStage(stage.group(1))) {
            return sentence(knownCause(text));
        }
        var failed = failedWords(stage.group(1));
        var cause = withoutLayerPrefixes(stage.group(2).strip());
        if (failed == null) {
            return sentence(knownCause(cause));
        }
        return cause.isEmpty() ? failed + "." : failed + ": " + withFullStop(cause);
    }

    /** What failed, in front of the cause; null when the cause says it alone (implementation, no stage). */
    private static String failedWords(String stage) {
        if (StopReasons.CHECKS_FAILED.equals(stage)) {
            return "Checks failed";
        }
        if (StopReasons.CHECKS_NOT_RUN.equals(stage)) {
            return "Checks could not run";
        }
        var label = "VERIFIED".equals(stage) ? null : TaskLabels.stageLabel(stage);
        return label == null || IMPLEMENTATION.equals(label) ? null : label + " failed";
    }

    /**
     * The Limits of the Configuration tab for a reason of {@link #reason} that says the agent ran out of time or
     * turns, so the page can link to where they are set; null for any other reason.
     */
    public static String limitsUrl(String reason) {
        var limit = reason != null && (reason.startsWith(TaskStops.TIME_LIMIT_START)
                || reason.startsWith(TaskStops.TURN_LIMIT_START) || reason.startsWith(TaskStops.DEADLINE_START));
        return limit ? LIMITS_URL : null;
    }

    /** The first sentence of the reason, for a list row that has room for one line. */
    public static String firstSentence(String reason) {
        return SENTENCE_END.split(reason, 2)[0];
    }

    private static boolean isStage(String token) {
        return TaskLabels.stageLabel(token) != null || token.matches("[A-Z_]+");
    }

    private static String withoutLayerPrefixes(String text) {
        var rest = text;
        var prefix = layerPrefix(rest);
        while (prefix.isPresent()) {
            rest = rest.substring(prefix.get().length()).strip();
            prefix = layerPrefix(rest);
        }
        return rest;
    }

    private static Optional<String> layerPrefix(String text) {
        return LAYER_PREFIXES.stream().filter(text::startsWith).findFirst();
    }

    /**
     * The causes said in plain words: the ones whose producers said them in other words before (their sentences
     * live in TaskStops), and the ones Factory stores in its own words.
     */
    private static String knownCause(String text) {
        var older = olderWording(text);
        if (older != null) {
            return older;
        }
        Matcher time = OLD_TIME_LIMIT.matcher(text);
        if (time.find()) {
            return TaskStops.timeLimitReason(Long.parseLong(time.group(1)));
        }
        Matcher turns = OLD_TURN_LIMIT.matcher(text);
        if (!turns.find()) {
            return factoryCause(text);
        }
        var count = turns.group(1) != null ? turns.group(1) : turns.group(2) != null ? turns.group(2) : turns.group(3);
        return TaskStops.turnLimitReason(Long.parseLong(count));
    }

    /** The fixed sentences of TaskStops that older runs stored in other words; null for any other text. */
    private static String olderWording(String text) {
        if (text.startsWith(Processes.OUTPUT_LIMIT_EXCEEDED)) {
            return TaskStops.OUTPUT_LIMIT_REASON;
        }
        if (text.startsWith(OLD_NO_CHANGES)) {
            return TaskStops.NO_CHANGES_REASON;
        }
        if (text.startsWith(OLD_STARTING_BUILD_FAILED)) {
            return TaskStops.startingBuildFailedReason("");
        }
        if (text.startsWith(OLD_REPLAYED_CODING)) {
            return TaskStops.REPLAYED_CODING_REASON;
        }
        Matcher deadline = OLD_DEADLINE.matcher(text);
        return deadline.find() ? TaskStops.deadlineReason(deadline.group(1)) : null;
    }

    /** The causes Factory stores in its own words; the reason of a question is the question alone. */
    private static String factoryCause(String text) {
        if (text.startsWith(TaskStops.DECISION_REASON)) {
            return text.substring(TaskStops.DECISION_REASON.length());
        }
        if (text.startsWith(TaskOutcomeService.RUN_ENDED)) {
            return RUN_ENDED;
        }
        if (text.startsWith(TaskOutcomeService.MARKED_STOPPED)) {
            return MARKED_STOPPED + text.substring(TaskOutcomeService.MARKED_STOPPED.length());
        }
        return text;
    }

    private static String sentence(String text) {
        return text.isEmpty() ? NO_REASON : withFullStop(Character.toUpperCase(text.charAt(0)) + text.substring(1));
    }

    private static String withFullStop(String text) {
        return text.matches("(?s).*[.!?]\"?$") ? text : text + ".";
    }
}
