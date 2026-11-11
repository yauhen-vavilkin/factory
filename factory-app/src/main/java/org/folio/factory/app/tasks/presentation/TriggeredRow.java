package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.web.PageTime;

import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One task under Runs: its latest attempt as a chip, cost and age, and the one action its state allows.
 * chipUrl is set when the chip is a pull request and opens GitHub; costTitle says why a cost may be too low.
 * detail is one line (the first sentence of a failure) and detailTitle its whole text.
 */
public record TriggeredRow(UUID taskId, String key, String jiraUrl, String title, String chipLabel,
                           String chipClass, String chipUrl, String detail, String detailTitle, String cost,
                           String costTitle, String when,
                           PageTime whenAt, RunAction action, String actionUrl, UUID markStoppedAttemptId) {

    /** The whole meta line, for its title when a narrow row cuts it. */
    public String metaTitle() {
        return Stream.of(key, when, detailTitle).filter(Objects::nonNull).collect(Collectors.joining(" · "));
    }
}
