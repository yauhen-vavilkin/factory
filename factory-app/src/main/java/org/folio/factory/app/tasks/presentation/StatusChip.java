package org.folio.factory.app.tasks.presentation;

/** One status filter chip of Available. */
public record StatusChip(String status, long count, boolean pressed) {
}
