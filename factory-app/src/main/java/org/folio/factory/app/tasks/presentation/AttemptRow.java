package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.web.PageTime;

import java.util.UUID;

/**
 * One row of Attempts on the task page. The meta line shows {@code settings}, {@code started}, {@code clock},
 * {@code note} and {@code quote}, each null when the attempt has none; only the quote of the instructions may be
 * cut. {@code meta} is the whole line without the start time, for its title. {@code costText} is what the row
 * shows, the other cost fields its parts; {@code status} is the chip, the same as on the run page.
 */
public record AttemptRow(int ordinal, String kind, String meta, String settings, PageTime started,
                         AttemptClock clock, String note, String quote, String cost, String costNoteTitle,
                         String costText, AttemptStatus status, String result, UUID executionId) {
}
