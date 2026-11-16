package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.web.UiFormat;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The time of an attempt as the task page and the run page show it. While a step with a time limit of its own
 * works (the coding, the checks, a PR feedback round), it is the time of that step with the limit, for example
 * "12 min of 30 min"; while another step works it is the time of the attempt alone. {@code start} makes the time
 * tick and is null when it stands still; {@code limit} and {@code title} are null when the time has no limit.
 */
public record AttemptClock(String time, Instant start, String limit, String title) {

    /** The steps that Factory stops at the time per step; the other steps have only the time per attempt. */
    private static final Set<String> LIMITED_STEPS =
            Set.of("implement", "continue-implementation", "verify", "pr-follow-up");

    /** The time per step and the time per attempt, as the Configuration tab's "Limits for every run" says them. */
    public record Limits(long stepSeconds, Duration attempt) {
    }

    /** What the clock is read from: the active time so far, and the step that started last and when. */
    public record Facts(long activeSeconds, String step, Instant stepStartedAt, boolean ticking, Instant now) {
    }

    public static AttemptClock of(Facts facts, Limits limits) {
        if (!facts.ticking()) {
            return new AttemptClock(UiFormat.duration(facts.activeSeconds()), null, null, null);
        }
        if (facts.stepStartedAt() == null || !LIMITED_STEPS.contains(facts.step())) {
            var start = facts.now().minusSeconds(facts.activeSeconds()).truncatedTo(ChronoUnit.SECONDS);
            return new AttemptClock(UiFormat.duration(facts.activeSeconds()), start, null, null);
        }
        var seconds = Math.max(0, Duration.between(facts.stepStartedAt(), facts.now()).toSeconds());
        return new AttemptClock(UiFormat.duration(seconds), facts.stepStartedAt(),
                "of " + UiFormat.duration(limits.stepSeconds()),
                "The whole run may take " + UiFormat.duration(limits.attempt().toSeconds()) + ".");
    }

    /**
     * Where the time of the working step starts: its STEP_STARTED event, so the time never goes back while the
     * step works. A PR feedback round starts the step again. Empty before the first step.
     */
    public static Optional<AuditEvent> stepStart(List<AuditEvent> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            var event = events.get(i);
            if (event.getEventType() == AuditEventType.STEP_STARTED) {
                return Optional.of(event);
            }
        }
        return Optional.empty();
    }

    /** Leaves out a ticking time, so the revision a page polls does not change while the time ticks. */
    @Override
    public String toString() {
        return start == null ? "AttemptClock[" + time + "]" : "AttemptClock[ticking " + limit + "]";
    }
}
