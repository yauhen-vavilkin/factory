package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.web.AttemptCharge.Category;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskSpendingTest {

    private static final UUID RUN = UUID.randomUUID();

    @Test
    void everyAttemptHasItsTokensInTheColumnsOfTheTaskAndTheTotalAddsThemUp() {
        var first = attempt(1, Instant.now(), usage(Map.of(Category.INPUT, 100L, Category.OUTPUT, 10L)));
        var second = attempt(2, Instant.now(), usage(Map.of(Category.INPUT, 2_000L, Category.CACHE_READ, 50L,
                Category.OUTPUT, 30L)));
        var view = view(List.of(first, second), usage(Map.of(Category.INPUT, 2_100L, Category.CACHE_READ, 50L,
                Category.CACHE_WRITE, 0L, Category.OUTPUT, 40L)));

        var spending = TaskSpending.of(view, "$0.70", "Incomplete");

        // Cache write has no tokens in the task, so it has no column.
        assertThat(spending.tokenColumns()).containsExactly("Input", "Cache read", "Output");
        assertThat(rows(view)).extracting(row -> row.tokens().counts())
                .containsExactly(List.of("2,000", "50", "30"), List.of("100", "—", "10"));
        assertThat(spending.total()).isEqualTo(new TaskSpending.Total("2 attempts", "<1 min",
                new TaskSpending.Tokens(List.of("2,100", "50", "40")), "$0.70", "Incomplete"));
    }

    @Test
    void oneAttemptHasNoTotalRow() {
        var only = attempt(1, Instant.now(), usage(Map.of(Category.INPUT, 100L)));
        var view = view(List.of(only), usage(Map.of(Category.INPUT, 100L)));

        assertThat(TaskSpending.of(view, "$0.01", null).total()).isNull();
    }

    @Test
    void anAttemptThatNeverStartedHasNoTokensAndDoesNotCountForTheTotalRow() {
        var worked = attempt(1, Instant.now(), usage(Map.of(Category.INPUT, 100L)));
        var queued = attempt(2, null, usage(Map.of()));
        var view = view(List.of(worked, queued), usage(Map.of(Category.INPUT, 100L)));

        assertThat(rows(view).getFirst().tokens().counts()).containsExactly("—");
        assertThat(TaskSpending.of(view, "$0.01", null).total()).isNull();
    }

    @Test
    void theTotalCountsAnAttemptThatWaitsInLineAfterTwoThatStarted() {
        var first = attempt(1, Instant.now(), usage(Map.of(Category.INPUT, 100L)));
        var second = attempt(2, Instant.now(), usage(Map.of(Category.INPUT, 100L)));
        var queued = attempt(3, null, usage(Map.of()));
        var view = view(List.of(first, second, queued), usage(Map.of(Category.INPUT, 200L)));

        assertThat(TaskSpending.of(view, "$0.02", null).total().attempts()).isEqualTo("3 attempts");
    }

    @Test
    void anAttemptThatWorksAndHasNotReportedTokensYetHasNone() {
        var failed = attempt(1, Instant.now(), usage(Map.of(Category.INPUT, 100L)));
        var working = new TaskView.AttemptHistoryEntry(UUID.randomUUID(), 2, TaskAttempt.Kind.RETRY, null,
                TaskAttempt.State.RUNNING, "implement", null, null, List.of(), RUN, Instant.now(), Instant.now(),
                true, 0, new TaskUsage(0, Map.of(), new TreeMap<>(), true, true, List.of()), null);
        var view = view(List.of(failed, working), usage(Map.of(Category.INPUT, 100L)));

        assertThat(rows(view).getFirst().tokens().counts()).containsExactly("—");
        var total = TaskSpending.of(view, "$0.01", null).total();
        assertThat(total.tokens().counts()).containsExactly("100");
        // The time of the working attempt ticks in its row, so the total does not show a sum that stands still.
        assertThat(total.time()).isEqualTo("—");
    }

    @Test
    void aTaskWithoutReportedTokensHasNoTokenColumns() {
        var started = attempt(1, Instant.now(), usage(Map.of()));
        var view = view(List.of(started), usage(Map.of(Category.INPUT, 0L)));

        assertThat(TaskSpending.of(view, null, null).tokenColumns()).isEmpty();
        assertThat(rows(view).getFirst().tokens().counts()).isEmpty();
    }

    @Test
    void aTotalWithoutAPriceSaysThatTheCostIsUnknown() {
        var first = attempt(1, Instant.now(), usage(Map.of(Category.INPUT, 100L)));
        var second = attempt(2, Instant.now(), usage(Map.of(Category.INPUT, 100L)));
        var view = view(List.of(first, second), usage(Map.of(Category.INPUT, 200L)));

        assertThat(TaskSpending.of(view, null, null).total().cost()).isEqualTo("cost unknown");
    }

    @Test
    void theTotalTimeAddsTheMinutesTheRowsShow() {
        var first = attempt(1, 150, usage(Map.of(Category.INPUT, 100L)));
        var second = attempt(2, 110, usage(Map.of(Category.INPUT, 100L)));
        var view = view(List.of(first, second), usage(Map.of(Category.INPUT, 200L)));

        // 2 min and 1 min in the rows: 3 min, though the seconds add up to more than 4 minutes.
        assertThat(rows(view)).extracting(row -> row.clock().time()).containsExactly("1 min", "2 min");
        assertThat(TaskSpending.of(view, null, null).total().time()).isEqualTo("3 min");
    }

    @Test
    void theTokenCountsStayOutOfTheTextThePagePolls() {
        assertThat(new TaskSpending.Tokens(List.of("100")))
                .hasToString(new TaskSpending.Tokens(List.of("2,000")).toString());
    }

    private static List<AttemptRow> rows(TaskView view) {
        return TaskRows.attemptRows(view, new AttemptStatus("Failed", "chip", null, "Attempt failed"));
    }

    /** Usage without a price, so each attempt row says "cost unknown". */
    private static TaskUsage usage(Map<Category, Long> tokens) {
        var byCategory = new EnumMap<Category, Long>(Category.class);
        byCategory.putAll(tokens);
        return new TaskUsage(tokens.values().stream().mapToLong(Long::longValue).sum(), byCategory,
                new TreeMap<String, BigDecimal>(), false, false, List.of());
    }

    private static TaskView.AttemptHistoryEntry attempt(int ordinal, Instant startedAt, TaskUsage usage) {
        return attempt(ordinal, startedAt, 0, usage);
    }

    /** An attempt that started and whose agent worked for this many seconds. */
    private static TaskView.AttemptHistoryEntry attempt(int ordinal, long activeSeconds, TaskUsage usage) {
        return attempt(ordinal, Instant.now(), activeSeconds, usage);
    }

    private static TaskView.AttemptHistoryEntry attempt(int ordinal, Instant startedAt, long activeSeconds,
                                                        TaskUsage usage) {
        return new TaskView.AttemptHistoryEntry(UUID.randomUUID(), ordinal, TaskAttempt.Kind.INITIAL, null,
                TaskAttempt.State.STOPPED, null, "DEVELOPMENT_FAILED: nothing changed", null, List.of(),
                startedAt == null ? null : RUN, Instant.now(), startedAt, startedAt != null, activeSeconds, usage,
                null);
    }

    private static TaskView view(List<TaskView.AttemptHistoryEntry> attempts, TaskUsage usage) {
        return new TaskView(UUID.randomUUID(), "MODBAR-1", null, "Task", "MODBAR", null,
                TaskView.TaskState.STOPPED, null, null, null, null, null, null, null, true, false, 1, null, null,
                attempts, usage, new TaskView.AllowedActions(false, true));
    }
}
