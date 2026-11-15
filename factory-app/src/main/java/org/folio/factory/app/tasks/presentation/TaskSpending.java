package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.web.AttemptCharge;
import org.folio.factory.app.web.UiFormat;

import java.util.Arrays;
import java.util.List;

/**
 * What the Attempts rows of the task page say about usage: the token columns, and the row that adds the attempts
 * up. A token category with no tokens in the whole task has no column. total is null until two attempts started.
 */
public record TaskSpending(List<String> tokenColumns, Total total) {

    private static final String NONE = "—";

    /** The token counts of one attempt or of the task, one per column; "—" where none were reported. */
    public record Tokens(List<String> counts) {

        /**
         * Leaves the counts out: they change with every turn of the agent, and the revision the task page polls
         * would reload the page every few seconds while it works. They are fresh again when the cost or the state
         * changes.
         */
        @Override
        public String toString() {
            return "Tokens";
        }
    }

    /** The attempts added up. costTitle says why the cost is missing or may be too low; null when it is complete. */
    public record Total(String attempts, String time, Tokens tokens, String cost, String costTitle) { }

    public static TaskSpending of(TaskView view, TaskDetail task) {
        return of(view, task.spent(), task.spentTitle());
    }

    static TaskSpending of(TaskView view, String spent, String spentTitle) {
        var columns = categories(view).stream().map(AttemptCharge.Category::label).toList();
        long started = view.attempts().stream().filter(attempt -> attempt.startedAt() != null).count();
        return new TaskSpending(columns, started < 2 ? null : total(view, spent, spentTitle));
    }

    /** The counts of {@code usage} in the token columns of {@code view}. */
    static Tokens tokens(TaskView view, TaskUsage usage) {
        return new Tokens(categories(view).stream()
                .map(category -> usage.tokensByCategory().get(category))
                .map(count -> count == null ? NONE : UiFormat.count(count))
                .toList());
    }

    private static List<AttemptCharge.Category> categories(TaskView view) {
        return Arrays.stream(AttemptCharge.Category.values())
                .filter(category -> view.usage().tokensByCategory().getOrDefault(category, 0L) > 0)
                .toList();
    }

    private static Total total(TaskView view, String spent, String spentTitle) {
        int count = view.attempts().size();
        return new Total(count + " attempts", time(view), tokens(view, view.usage()),
                spent == null ? UiFormat.COST_UNKNOWN : spent, spentTitle);
    }

    /**
     * The times in the Attempts rows added up, each in whole minutes as its row shows it, so the sum agrees with
     * the rows above it. "—" when no attempt reached the agent, and while the agent works: the time of that row
     * ticks, and a sum that stands still would fall behind it.
     */
    private static String time(TaskView view) {
        var paid = view.attempts().stream().filter(TaskView.AttemptHistoryEntry::paidStart).toList();
        if (paid.isEmpty() || paid.stream().anyMatch(TaskRows::ticks)) {
            return NONE;
        }
        return TaskLabels.minutes(paid.stream().mapToLong(attempt -> attempt.activeSeconds() / 60 * 60).sum());
    }
}
