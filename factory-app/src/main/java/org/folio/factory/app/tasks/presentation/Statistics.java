package org.folio.factory.app.tasks.presentation;

import java.util.List;

/**
 * The Statistics tab of the Developer Flow for the runs that started in a period and their tasks: how the tasks and
 * the runs ended, what they cost, and what each model and effort cost.
 */
public record Statistics(Tasks tasks, Runs runs, Money money, List<ModelRow> models) {

    /** One figure: the value and what it counts. */
    public record Figure(String value, String label) {
    }

    /** One part of the tasks bar and its legend key; {@code key} is the part of their class names. */
    public record Bucket(String key, long count, String label) {
    }

    /**
     * The tasks: how many, the figures, the buckets every task is in exactly one of, and the words of the bar for
     * a screen reader.
     */
    public record Tasks(long count, List<Figure> figures, List<Bucket> buckets, String barLabel) {
    }

    /** The runs: how many, how many ended in each way, and the runs per task. */
    public record Runs(long count, List<Figure> figures) {
    }

    /** The amounts, and a note on runs without a price (null when every run has one). */
    public record Money(List<Figure> figures, String unpricedNote) {
    }

    /**
     * One model and effort: its runs, the pull requests they opened, the average of the runs with a price ("—" when
     * none has one) and what they cost. {@code spentNote} is true when {@code spent} is words, not a figure.
     */
    public record ModelRow(String label, String runs, String pullRequests, String perRun, String spent,
                           boolean spentNote) {
    }
}
