package org.folio.factory.app.tasks.presentation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.execution.StopReasons;
import org.folio.factory.app.tasks.execution.TaskOutcomeService;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.devfactory.tasks.TaskStops;

import java.util.List;

/**
 * Where an attempt stopped without a pull request, for the Dashboard. It reads the stored reason with the checks of
 * {@link AttemptFailure} and {@link TaskStops}, so older wordings count under the same cause as on the task page.
 */
@Getter
@RequiredArgsConstructor
public enum StopCause {

    TIME_LIMIT("Time limit"),
    TURN_LIMIT("Turn limit"),
    ASKED("Asked a question"),
    NO_CHANGES("No changes"),
    CHECKS_FAILED("Checks failed"),
    FACTORY_OR_ENVIRONMENT("Stopped by Factory or the environment"),
    OTHER("Other");

    /** The stages of a stored reason that Factory's setup or GitHub stopped. */
    private static final List<String> ENVIRONMENT_STAGES = List.of("BLOCKED_ENVIRONMENT: ", "DELIVERY_BLOCKED: ");
    /** The starts of the page words of a stop that Factory or the environment made. */
    private static final List<String> ENVIRONMENT_STARTS = List.of(AttemptFailure.CHECKS_NOT_RUN_WORDS,
            TaskStops.STARTING_BUILD_FAILED_START, TaskStops.REPLAYED_CODING_REASON, StopReasons.ENGINE_GAVE_UP_START);

    private final String label;

    /** The cause of an attempt in {@code state} that stopped with {@code storedReason}. */
    static StopCause of(TaskAttempt.State state, String storedReason) {
        var stored = storedReason == null ? "" : storedReason.strip();
        if (state == TaskAttempt.State.STOPPED && TaskStops.asksForAnswer(stored)) {
            return ASKED;
        }
        if (state == TaskAttempt.State.NEEDS_INSPECTION || byEnvironment(stored)) {
            return FACTORY_OR_ENVIRONMENT;
        }
        return ofWords(AttemptFailure.reason(stored));
    }

    private static boolean byEnvironment(String stored) {
        return stored.startsWith(TaskOutcomeService.SHARED) || TaskStops.sharedFailure(stored, null)
                || stored.startsWith(TaskOutcomeService.RUN_ENDED)
                || ENVIRONMENT_STAGES.stream().anyMatch(stored::startsWith);
    }

    /** The cause in the words the task page shows, which also map the wordings of older runs. */
    private static StopCause ofWords(String reason) {
        if (reason.startsWith(TaskStops.TIME_LIMIT_START) || reason.startsWith(TaskStops.DEADLINE_START)) {
            return TIME_LIMIT;
        }
        if (reason.startsWith(TaskStops.TURN_LIMIT_START)) {
            return TURN_LIMIT;
        }
        if (reason.equals(TaskStops.NO_CHANGES_REASON)) {
            return NO_CHANGES;
        }
        if (reason.startsWith(AttemptFailure.CHECKS_FAILED_WORDS)) {
            return CHECKS_FAILED;
        }
        return ENVIRONMENT_STARTS.stream().anyMatch(reason::startsWith) ? FACTORY_OR_ENVIRONMENT : OTHER;
    }
}
