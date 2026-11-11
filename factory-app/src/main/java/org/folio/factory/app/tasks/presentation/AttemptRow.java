package org.folio.factory.app.tasks.presentation;

import java.util.UUID;

/** One row of Attempts on the task page; {@code costText} is what the row shows, the other cost fields its parts. */
public record AttemptRow(int ordinal, String kind, String meta, String cost,
                         String costNote, String costNoteTitle, String costText, String chipLabel,
                         String chipClass, String result, UUID executionId) {
}
