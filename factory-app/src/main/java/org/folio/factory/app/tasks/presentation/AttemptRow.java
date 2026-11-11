package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.web.PageTime;

import java.util.UUID;

/**
 * One row of Attempts on the task page. The meta line shows {@code settings}, {@code started} and {@code details}
 * (each null when the attempt has none); {@code meta} is the whole line for its title. {@code costText} is what
 * the row shows, the other cost fields its parts.
 */
public record AttemptRow(int ordinal, String kind, String meta, String settings, PageTime started, String details,
                         String cost,
                         String costNote, String costNoteTitle, String costText, String chipLabel,
                         String chipClass, String result, UUID executionId) {
}
