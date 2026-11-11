package org.folio.factory.app.tasks.presentation;

import java.util.List;
import java.util.UUID;

/**
 * The task page: head, status card, the action it offers and the technical details. reason and reasonNote are
 * set when the latest attempt failed: the failure sentence and what the attempt left behind.
 */
public record TaskDetail(UUID taskId, String key, String jiraUrl, String title, String repository,
                         long version, String inputFingerprint, String destinationFingerprint,
                         String stateLabel, String stateClass, String headline, String reason, String reasonNote,
                         List<String> draftReasons,
                         String spent, String spentNote, boolean retryAllowed, int nextAttempt,
                         String runUrl, String runLabel, String prUrl, String prAction,
                         UUID markStoppedAttemptId, String project, String destination, Usage usage) {

    /** Token figures of all attempts, shown inside Technical details. */
    public record Usage(boolean present, String tokens, List<Figure> categories, String cost, String note) {
    }

    public record Figure(String label, String value) {
    }
}
