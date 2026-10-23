package org.folio.factory.devfactory.pilot;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttemptDeadlineTest {

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @Test
    void remainingCountsDownToThePersistedExpiry() {
        var deadline = new AttemptDeadline(Instant.parse("2026-10-23T10:00:00Z"));

        assertThat(deadline.remaining(at("2026-10-23T09:55:00Z"))).isEqualTo(Duration.ofMinutes(5));
        assertThat(deadline.remaining(at("2026-10-23T10:00:00Z"))).isZero();
        assertThat(deadline.remaining(at("2026-10-23T10:00:01Z"))).isEqualTo(Duration.ofSeconds(-1));
    }

    @Test
    void expiryIsExactAtThePersistedInstant() {
        var deadline = new AttemptDeadline(Instant.parse("2026-10-23T10:00:00Z"));

        assertThat(deadline.expired(at("2026-10-23T09:59:59Z"))).isFalse();
        assertThat(deadline.expired(at("2026-10-23T10:00:00Z"))).isTrue();
        assertThat(deadline.expired(at("2026-10-23T11:30:00Z"))).isTrue();
    }

    @Test
    void stageTimeoutsAreTheShorterOfPerOperationLimitAndRemaining() {
        var deadline = new AttemptDeadline(Instant.parse("2026-10-23T10:00:00Z"));

        assertThat(deadline.capSeconds(at("2026-10-23T09:55:00Z"), 1800)).isEqualTo(300);
        assertThat(deadline.capSeconds(at("2026-10-23T09:55:00Z"), 60)).isEqualTo(60);
        assertThat(deadline.capSeconds(at("2026-10-23T09:00:00Z"), 1800)).isEqualTo(1800);
        assertThat(deadline.capSeconds(at("2026-10-23T10:00:00Z"), 1800)).isZero();
        assertThat(deadline.capSeconds(at("2026-10-23T10:45:00Z"), 1800)).isZero();
    }

    @Test
    void eachStageIsBoundedByWhatRemainsOfTheWholeAttemptBudget() {
        var deadline = new AttemptDeadline(Instant.parse("2026-10-23T10:00:00Z"));

        assertThat(deadline.capSeconds(at("2026-10-23T09:50:00Z"), 300)).isEqualTo(300);
        assertThat(deadline.capSeconds(at("2026-10-23T09:54:00Z"), 300)).isEqualTo(300);
        assertThat(deadline.capSeconds(at("2026-10-23T09:57:00Z"), 300)).isEqualTo(180);
        assertThat(deadline.capSeconds(at("2026-10-23T09:59:00Z"), 300)).isEqualTo(60);
        assertThat(deadline.expired(at("2026-10-23T10:00:00Z"))).isTrue();
    }

    @Test
    void perOperationLimitsStayMeaningful() {
        var deadline = new AttemptDeadline(Instant.parse("2026-10-23T10:00:00Z"));

        assertThatThrownBy(() -> deadline.capSeconds(at("2026-10-23T09:55:00Z"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
