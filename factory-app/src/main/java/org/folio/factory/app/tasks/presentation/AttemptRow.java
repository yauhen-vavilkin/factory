package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.web.PageTime;

import java.util.UUID;

/**
 * One row of Runs on the task page. {@code result} is one sentence that says how the attempt ended. The
 * strip under it shows {@code settings}, {@code started} or {@code note}, {@code clock} and
 * {@code tokens}; {@code quote} is the first line of the developer's instructions and may be cut. {@code costText}
 * is what the row shows, the other cost fields its parts; {@code status} is the chip, the same as on the run page.
 */
public record AttemptRow(int ordinal, String kind, String settings, PageTime started,
                         AttemptClock clock, String note, String quote, String cost, String costNoteTitle,
                         String costText, AttemptStatus status, String result, UUID executionId,
                         TaskSpending.Tokens tokens) {
}
