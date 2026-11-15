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
    private static final String UNKNOWN_TITLE =
            "The runtime did not report usage, or the usage has no price, so the cost is not known.";

    @Test
    void eachAttemptThatStartedHasItsRowNewestFirstAndTheTotalAddsThemUp() {
        var first = attempt(1, Instant.now(), usage(Map.of(Category.INPUT, 100L, Category.OUTPUT, 10L)));
        var second = attempt(2, Instant.now(), usage(Map.of(Category.INPUT, 2_000L, Category.CACHE_READ, 50L,
                Category.OUTPUT, 30L)));
        var view = view(List.of(first, second), usage(Map.of(Category.INPUT, 2_100L, Category.CACHE_READ, 50L,
                Category.CACHE_WRITE, 0L, Category.OUTPUT, 40L)));

        var spending = TaskSpending.of(view, rows(view), "$0.70", "Incomplete").orElseThrow();

        // Cache write has no tokens in the task, so it has no column.
        assertThat(spending.tokenColumns()).containsExactly("Input", "Cache read", "Output");
        assertThat(spending.rows()).containsExactly(
                new TaskSpending.Row("Attempt 2", "/executions/" + RUN, "—", List.of("2,000", "50", "30"),
                        "cost unknown", UNKNOWN_TITLE),
                new TaskSpending.Row("Attempt 1", "/executions/" + RUN, "—", List.of("100", "—", "10"),
                        "cost unknown", UNKNOWN_TITLE));
        assertThat(spending.total()).isEqualTo(new TaskSpending.Row("Total", null, null,
                List.of("2,100", "50", "40"), "$0.70", "Incomplete"));
    }

    @Test
    void oneAttemptHasNoTotalRow() {
        var only = attempt(1, Instant.now(), usage(Map.of(Category.INPUT, 100L)));
        var view = view(List.of(only), usage(Map.of(Category.INPUT, 100L)));

        var spending = TaskSpending.of(view, rows(view), "$0.01", null).orElseThrow();

        assertThat(spending.rows()).hasSize(1);
        assertThat(spending.total()).isNull();
    }

    @Test
    void anAttemptThatNeverStartedHasNoRow() {
        var worked = attempt(1, Instant.now(), usage(Map.of(Category.INPUT, 100L)));
        var queued = attempt(2, null, usage(Map.of()));
        var view = view(List.of(worked, queued), usage(Map.of(Category.INPUT, 100L)));

        var spending = TaskSpending.of(view, rows(view), "$0.01", null).orElseThrow();

        assertThat(spending.rows()).extracting(TaskSpending.Row::label).containsExactly("Attempt 1");
        assertThat(spending.total()).isNull();
    }

    @Test
    void anAttemptThatWorksAndHasNotReportedTokensYetHasItsRowWithoutThem() {
        var failed = attempt(1, Instant.now(), usage(Map.of(Category.INPUT, 100L)));
        var working = new TaskView.AttemptHistoryEntry(UUID.randomUUID(), 2, TaskAttempt.Kind.RETRY, null,
                TaskAttempt.State.RUNNING, "implement", null, null, List.of(), RUN, Instant.now(), Instant.now(),
                true, 0, new TaskUsage(0, Map.of(), new TreeMap<>(), true, true, List.of()), null);
        var view = view(List.of(failed, working), usage(Map.of(Category.INPUT, 100L)));

        var spending = TaskSpending.of(view, rows(view), "$0.01", null).orElseThrow();

        assertThat(spending.rows().getFirst()).isEqualTo(new TaskSpending.Row("Attempt 2", "/executions/" + RUN,
                "—", List.of("—"), "$0.00 so far", null));
        assertThat(spending.total().tokens()).containsExactly("100");
    }

    @Test
    void aTaskWithoutReportedTokensHasNoSpending() {
        var started = attempt(1, Instant.now(), usage(Map.of()));
        var view = view(List.of(started), usage(Map.of(Category.INPUT, 0L)));

        assertThat(TaskSpending.of(view, rows(view), null, null)).isEmpty();
    }

    @Test
    void aTotalWithoutAPriceSaysThatTheCostIsUnknown() {
        var first = attempt(1, Instant.now(), usage(Map.of(Category.INPUT, 100L)));
        var second = attempt(2, Instant.now(), usage(Map.of(Category.INPUT, 100L)));
        var view = view(List.of(first, second), usage(Map.of(Category.INPUT, 200L)));

        var total = TaskSpending.of(view, rows(view), null, null).orElseThrow().total();

        assertThat(total.cost()).isEqualTo("cost unknown");
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
        return new TaskView.AttemptHistoryEntry(UUID.randomUUID(), ordinal, TaskAttempt.Kind.INITIAL, null,
                TaskAttempt.State.STOPPED, null, "DEVELOPMENT_FAILED: nothing changed", null, List.of(),
                startedAt == null ? null : RUN, Instant.now(), startedAt, startedAt != null, 0, usage, null);
    }

    private static TaskView view(List<TaskView.AttemptHistoryEntry> attempts, TaskUsage usage) {
        return new TaskView(UUID.randomUUID(), "MODBAR-1", null, "Task", "MODBAR", null,
                TaskView.TaskState.STOPPED, null, null, null, null, null, null, null, true, false, 1, null, null,
                attempts, usage, new TaskView.AllowedActions(false, true));
    }
}
