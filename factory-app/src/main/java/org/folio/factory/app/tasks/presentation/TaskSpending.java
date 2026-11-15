package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.web.AttemptCharge;
import org.folio.factory.app.web.UiFormat;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Spending table of the task page: what each attempt that started used and cost, newest first as in Attempts,
 * and their sum when there are two or more. {@code tokenColumns} names the kinds of tokens the task used; every
 * row has one cell for each of them, in the same order.
 */
public record TaskSpending(List<String> tokenColumns, List<Row> rows, Row total) {

    private static final String NONE = "—";

    /**
     * One attempt, or the sum, which has no run and no model. {@code costTitle} says why the cost is missing or
     * may be too low; null when it is complete.
     */
    public record Row(String label, String runUrl, String model, List<String> tokens, String cost,
                      String costTitle) {
    }

    /**
     * Empty while no attempt has reported tokens. {@code attempts} are the Attempts rows and {@code task} the
     * status card, so the table shows the same costs as the rest of the page.
     */
    public static Optional<TaskSpending> of(TaskView view, List<AttemptRow> attempts, TaskDetail task) {
        return of(view, attempts, task.spent(), task.spentTitle());
    }

    /** The same from the amount of the status card and its title; an amount of null is an unknown cost. */
    static Optional<TaskSpending> of(TaskView view, List<AttemptRow> attempts, String spent, String spentTitle) {
        var categories = Arrays.stream(AttemptCharge.Category.values())
                .filter(category -> view.usage().tokensByCategory().getOrDefault(category, 0L) > 0)
                .toList();
        if (categories.isEmpty()) {
            return Optional.empty();
        }
        var entries = view.attempts().stream()
                .collect(Collectors.toMap(TaskView.AttemptHistoryEntry::ordinal, Function.identity()));
        var rows = attempts.stream()
                .filter(attempt -> entries.get(attempt.ordinal()).startedAt() != null)
                .map(attempt -> row(attempt, entries.get(attempt.ordinal()), categories))
                .toList();
        var total = rows.size() < 2 ? null : new Row("Total", null, null, tokens(view.usage(), categories),
                spent == null ? UiFormat.COST_UNKNOWN : spent, spentTitle);
        var columns = categories.stream().map(AttemptCharge.Category::label).toList();
        return Optional.of(new TaskSpending(columns, rows, total));
    }

    private static Row row(AttemptRow attempt, TaskView.AttemptHistoryEntry entry,
                           List<AttemptCharge.Category> categories) {
        var runUrl = entry.executionId() == null ? null : "/executions/" + entry.executionId();
        var model = attempt.settings() == null ? NONE : attempt.settings();
        return new Row("Attempt " + attempt.ordinal(), runUrl, model, tokens(entry.usage(), categories),
                attempt.costText(), attempt.costNoteTitle());
    }

    /** One cell for each column; "—" where the usage does not report that kind of tokens. */
    private static List<String> tokens(TaskUsage usage, List<AttemptCharge.Category> categories) {
        return categories.stream()
                .map(category -> usage.tokensByCategory().get(category))
                .map(count -> count == null ? NONE : UiFormat.count(count))
                .toList();
    }
}
