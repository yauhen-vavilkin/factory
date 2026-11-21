package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.devfactory.tasks.TaskStops;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Attempts and tasks in the shapes the task projection builds, for the tests of the statistics buckets. */
final class AttemptCases {

    static final String PULL_REQUEST = "https://github.com/folio-org/mod-bar/pull/6";
    static final String FAILED = "DEVELOPMENT_FAILED: " + TaskStops.NO_CHANGES_REASON;
    static final String ASKED = "clarify-implementation: " + TaskStops.DECISION_REASON + "Which API?";

    private AttemptCases() {
    }

    static TaskView.AttemptHistoryEntry attempt(int ordinal, TaskAttempt.State state, String reason,
                                                String pullRequestUrl) {
        var started = state == TaskAttempt.State.RESERVED ? null : Instant.now();
        return new TaskView.AttemptHistoryEntry(UUID.randomUUID(), ordinal,
                ordinal == 1 ? TaskAttempt.Kind.INITIAL : TaskAttempt.Kind.RETRY, null, state, null, reason,
                pullRequestUrl, null, started == null ? null : UUID.randomUUID(), Instant.now(), started,
                started != null, 0, usage(), null);
    }

    /** A task whose state and reason follow its last attempt, as the projection sets them. */
    static TaskView task(TaskView.AttemptHistoryEntry... attempts) {
        var last = attempts[attempts.length - 1];
        return new TaskView(UUID.randomUUID(), "MODBAR-1", null, "Task", "MODBAR", null, state(last), null,
                last.reason(), null, null, null, last.pullRequestUrl(), null, true, false, 1, null, null,
                List.of(attempts), usage(), new TaskView.AllowedActions(false, true));
    }

    private static TaskView.TaskState state(TaskView.AttemptHistoryEntry attempt) {
        return switch (attempt.state()) {
            case RESERVED, QUEUED, RUNNING -> TaskView.TaskState.WORKING;
            case STOPPED -> TaskView.TaskState.STOPPED;
            case NEEDS_INSPECTION -> TaskView.TaskState.UNCERTAIN;
            case WITHDRAWN -> TaskView.TaskState.WITHDRAWN;
            case PR_READY -> TaskView.TaskState.PR_READY;
        };
    }

    private static TaskUsage usage() {
        return new TaskUsage(0, Map.of(), new TreeMap<String, BigDecimal>(), false, false, List.of());
    }
}
