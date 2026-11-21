package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskLabelsTest {

    @Test
    void anAttemptThatStoppedBeforeTheAgentAndReportedNoUsageSpentNothing() {
        assertThat(TaskLabels.finishedCost(attempt(false, usage(true)))).isEqualTo("$0.00");
    }

    @Test
    void anAttemptWhoseUsageIsNotCompleteHasNoCostRatherThanNothingSpent() {
        // Usage that older runs reported without an attempt id is not counted, so the attempt may have spent money.
        assertThat(TaskLabels.finishedCost(attempt(false, usage(false)))).isNull();
    }

    @Test
    void anAttemptThatReachedTheAgentHasNoCostRatherThanNothingSpent() {
        assertThat(TaskLabels.finishedCost(attempt(true, usage(true)))).isNull();
    }

    private static TaskUsage usage(boolean complete) {
        return new TaskUsage(0, Map.of(), new TreeMap<>(), complete, false, List.of());
    }

    private static TaskView.AttemptHistoryEntry attempt(boolean paidStart, TaskUsage usage) {
        return new TaskView.AttemptHistoryEntry(UUID.randomUUID(), 1, TaskAttempt.Kind.INITIAL, null,
                TaskAttempt.State.STOPPED, null, "BLOCKED_ENVIRONMENT: the build failed", null, List.of(), null,
                Instant.now(), Instant.now(), paidStart, 0, usage, null);
    }
}
