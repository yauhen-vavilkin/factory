package org.folio.factory.devfactory.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the stop texts the operator reads; they predate TaskGuard and must stay word for word. */
class TaskGuardTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC);
    private static final UUID EXECUTION = UUID.randomUUID();

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "preparation | the task attempt no longer owns the installation's active slot;"
            + " a stopped, superseded or released attempt must not continue",
        "coding | the task attempt no longer owns the installation's active slot;"
            + " a stopped, superseded or released attempt must not continue",
        "candidate freeze | the task attempt no longer owns the installation's active slot;"
            + " a stopped, superseded or released attempt must not continue",
        "verification | the task attempt no longer owns the installation's active slot;"
            + " a stopped, superseded or released attempt must not continue",
        "publication | the task attempt no longer owns the installation's active slot;"
            + " a stopped, superseded or released attempt must not publish"})
    void anAttemptThatLostTheSlotStopsForInspection(String stage, String reason) {
        var attempts = RecordingTaskAttempts.taskRun();
        attempts.ownsSlot = false;

        var stop = new TaskGuard(attempts, NOW).stop(EXECUTION, "step", stage);

        assertThat(stop).contains(new Stop("NEEDS_INSPECTION", reason));
        assertThat(attempts.stops).containsExactly("step: " + reason);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "preparation | the whole-attempt deadline expired before preparation;"
            + " queue and human-review waiting are excluded from the budget",
        "coding | the whole-attempt deadline expired before coding;"
            + " queue and human-review waiting are excluded from the budget",
        "candidate freeze | the whole-attempt deadline expired before candidate freeze;"
            + " queue and human-review waiting are excluded from the budget",
        "verification | the whole-attempt deadline expired before verification;"
            + " queue and human-review waiting are excluded from the budget",
        "publication | the whole-attempt deadline expired before publication;"
            + " queue and human-review waiting are excluded from the budget"})
    void anExpiredDeadlineStopsBeforeTheStage(String stage, String reason) {
        var attempts = RecordingTaskAttempts.taskRun()
                .withDeadline(new AttemptDeadline(Instant.parse("2026-10-23T10:00:00Z")));

        var stop = new TaskGuard(attempts, NOW).stop(EXECUTION, "step", stage);

        assertThat(stop).contains(new Stop("DEADLINE_EXCEEDED", reason));
        assertThat(attempts.stops).containsExactly("step: " + reason);
    }

    @Test
    void anAttemptWithTheSlotAndTimeLeftGoesOn() {
        var attempts = RecordingTaskAttempts.taskRun()
                .withDeadline(new AttemptDeadline(Instant.parse("2026-10-23T10:00:01Z")));

        assertThat(new TaskGuard(attempts, NOW).stop(EXECUTION, "step", "coding")).isEmpty();
        assertThat(attempts.stops).isEmpty();
    }

    @Test
    void aRunWithoutATaskAttemptIsNeverStopped() {
        var attempts = RecordingTaskAttempts.none().withDeadline(
                new AttemptDeadline(Instant.parse("2026-10-23T09:00:00Z")));
        attempts.ownsSlot = false;

        assertThat(new TaskGuard(attempts, NOW).stop(EXECUTION, "step", "publication")).isEmpty();
        assertThat(attempts.stops).isEmpty();
    }

    @Test
    void timeoutsAreCutToWhatIsLeftOfTheAttempt() {
        var withoutDeadline = new TaskGuard(RecordingTaskAttempts.taskRun(), NOW);
        var withTenMinutes = new TaskGuard(RecordingTaskAttempts.taskRun()
                .withDeadline(new AttemptDeadline(Instant.parse("2026-10-23T10:10:00Z"))), NOW);
        var expired = new TaskGuard(RecordingTaskAttempts.taskRun()
                .withDeadline(new AttemptDeadline(Instant.parse("2026-10-23T09:59:00Z"))), NOW);

        assertThat(withoutDeadline.capTimeout(EXECUTION, 1800)).isEqualTo(1800);
        assertThat(withTenMinutes.capTimeout(EXECUTION, 1800)).isEqualTo(600);
        assertThat(withTenMinutes.capTimeout(EXECUTION, 300)).isEqualTo(300);
        assertThat(expired.capTimeout(EXECUTION, 1800)).isZero();
    }
}
