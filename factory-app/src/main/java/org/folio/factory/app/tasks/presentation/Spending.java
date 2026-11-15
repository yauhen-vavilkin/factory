package org.folio.factory.app.tasks.presentation;

import java.util.List;
import java.util.UUID;

/**
 * What the Developer Flow's task attempts of a period cost, for the Dashboard: four figures, a note on attempts
 * without a price (null when every attempt has one), the most expensive tasks, and one line for the other tasks
 * (null when every task has its row).
 */
public record Spending(List<Figure> figures, String unpricedNote, List<Row> rows, String rest) {

    /** One figure: the amount and what it counts. */
    public record Figure(String value, String label) {
    }

    /**
     * One task: its cost in the period with its two parts as bar widths in percent of the most expensive task, and
     * the chip the task has on My tasks. {@code amountNote} is true when the amount is words, not a figure.
     */
    public record Row(UUID taskId, String key, String jiraUrl, String title, String attempts, String amount,
                      boolean amountNote, int pullRequestWidth, int otherWidth, String barLabel, String chipLabel,
                      String chipClass, String chipUrl) {
    }
}
