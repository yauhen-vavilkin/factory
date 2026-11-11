package org.folio.factory.app.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;

/** The periods the Dashboard counts runs in and the Executions list filters by, by the time a run started. */
public enum RunPeriod {
    DAY(1), WEEK(7), TWO_WEEKS(14), MONTH(30), QUARTER(90);

    static final RunPeriod DEFAULT = TWO_WEEKS;

    private final int days;

    RunPeriod(int days) {
        this.days = days;
    }

    /** The period of that many days; empty for a blank or unknown value, which the pages read as their default. */
    static Optional<RunPeriod> of(String days) {
        return Arrays.stream(values()).filter(period -> String.valueOf(period.days).equals(days)).findFirst();
    }

    public int days() {
        return days;
    }

    /** "24 hours" or "7 days". */
    public String label() {
        return this == DAY ? "24 hours" : days + " days";
    }

    /** When the period starts, counted back from {@code now}. */
    OffsetDateTime since(Instant now) {
        return now.minus(days, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC);
    }
}
