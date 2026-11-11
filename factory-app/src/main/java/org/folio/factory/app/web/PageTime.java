package org.folio.factory.app.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * An absolute time on a page, rendered by the {@code time} fragment. Factory runs on the developer's machine but
 * stores UTC, so the page prints the UTC text and {@code time.js} rewrites it to the browser's local time in the
 * same format; without script the text stays and says UTC.
 */
public record PageTime(Instant at, Format format) {

    /** The three ways a page prints a time; {@code time.js} knows them by their lower-case names. */
    public enum Format {
        TIMESTAMP("yyyy-MM-dd HH:mm:ss"),
        CLOCK("HH:mm:ss"),
        DAY("MMM d, HH:mm");

        private final DateTimeFormatter utc;

        Format(String pattern) {
            utc = DateTimeFormatter.ofPattern(pattern, Locale.US).withZone(ZoneOffset.UTC);
        }
    }

    /** Whole seconds, so the {@code datetime} attribute and the text say the same time. */
    public PageTime {
        at = at.truncatedTo(ChronoUnit.SECONDS);
    }

    /** {@code 2026-11-10 22:42:07 UTC}, or null when there is no time. */
    public static PageTime timestamp(Instant at) {
        return at == null ? null : new PageTime(at, Format.TIMESTAMP);
    }

    /** {@code 22:42:07 UTC} for dense logs, or null when there is no time. */
    public static PageTime clock(Instant at) {
        return at == null ? null : new PageTime(at, Format.CLOCK);
    }

    /** {@code Nov 10, 22:42 UTC}, or null when there is no time. */
    public static PageTime day(Instant at) {
        return at == null ? null : new PageTime(at, Format.DAY);
    }

    /** The ISO-8601 instant of the {@code datetime} attribute. */
    public String datetime() {
        return at.toString();
    }

    public String text() {
        return format.utc.format(at) + " UTC";
    }

    /** The full UTC time, for the title. */
    public String title() {
        return Format.TIMESTAMP.utc.format(at) + " UTC";
    }

    /** The format name {@code time.js} reads from {@code data-local}. */
    public String local() {
        return format.name().toLowerCase(Locale.ROOT);
    }
}
