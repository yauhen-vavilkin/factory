package org.folio.factory.app.tasks.presentation;

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
    private static final List<String> LAYER_PREFIXES =
            List.of("shared: ", "Development stopped: ", "Implementation failed: ");
    private static final Pattern LEADING_STAGE = Pattern.compile("^([A-Za-z][A-Za-z_-]*): (.+)$", Pattern.DOTALL);
    private static final Pattern OLD_TIME_LIMIT =
            Pattern.compile("^Claude Code did not finish within the time limit of (\\d+) minutes?\\b");
    private static final Pattern OLD_TURN_LIMIT =
            Pattern.compile("^Claude Code (?:reached the (\\d+)-turn limit|used all (\\d+) turns)\\b");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z\"])");

    /** "Attempt 3 failed" and the reason of that attempt. */
    public static AttemptFailure of(int ordinal, String storedReason) {
        return new AttemptFailure("Attempt " + ordinal + " failed", reason(storedReason));
    }

    /**
     * One or two plain sentences that end with a full stop. A reason this method does not know passes through
     * without the layer prefixes; a stage other than the implementation stays as "Verification failed: ".
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
        var label = "VERIFIED".equals(stage.group(1)) ? null : TaskLabels.stageLabel(stage.group(1));
        var cause = withoutLayerPrefixes(stage.group(2).strip());
        if (label == null || IMPLEMENTATION.equals(label)) {
            return sentence(knownCause(cause));
        }
        return cause.isEmpty() ? label + " failed." : label + " failed: " + withFullStop(cause);
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
        if (text.startsWith(Processes.OUTPUT_LIMIT_EXCEEDED)) {
            return TaskStops.OUTPUT_LIMIT_REASON;
        }
        if (text.startsWith(TaskOutcomeService.RUN_ENDED)) {
            return RUN_ENDED;
        }
        if (text.startsWith(TaskOutcomeService.MARKED_STOPPED)) {
            return MARKED_STOPPED + text.substring(TaskOutcomeService.MARKED_STOPPED.length());
        }
        Matcher time = OLD_TIME_LIMIT.matcher(text);
        if (time.find()) {
            return TaskStops.timeLimitReason(Long.parseLong(time.group(1)));
        }
        Matcher turns = OLD_TURN_LIMIT.matcher(text);
        if (!turns.find()) {
            return text;
        }
        var count = turns.group(1) != null ? turns.group(1) : turns.group(2);
        return TaskStops.turnLimitReason(Long.parseLong(count));
    }

    private static String sentence(String text) {
        return text.isEmpty() ? NO_REASON : withFullStop(Character.toUpperCase(text.charAt(0)) + text.substring(1));
    }

    private static String withFullStop(String text) {
        return text.matches("(?s).*[.!?]\"?$") ? text : text + ".";
    }
}
