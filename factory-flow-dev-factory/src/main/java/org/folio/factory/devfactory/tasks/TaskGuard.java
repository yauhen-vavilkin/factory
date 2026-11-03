package org.folio.factory.devfactory.tasks;

import lombok.RequiredArgsConstructor;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * The checks a task attempt passes before each side effect: it still owns the installation's active slot,
 * and its whole-attempt deadline has not expired. Executions without a task attempt are never stopped.
 */
@RequiredArgsConstructor
public final class TaskGuard {
    private static final String PUBLICATION = "publication";

    private final TaskAttemptPolicy policy;
    private final Clock clock;

    public TaskAttemptPolicy policy() {
        return policy;
    }

    public boolean isTaskRun(UUID executionId) {
        return policy.find(executionId).isPresent();
    }

    /** Records and returns the stop when a task attempt must not start the named stage. */
    public Optional<Stop> stop(UUID executionId, String stepId, String stage) {
        if (!isTaskRun(executionId)) {
            return Optional.empty();
        }
        var stop = findStop(executionId, stage);
        stop.ifPresent(found -> policy.recordStop(executionId, stepId, found.reason()));
        return stop;
    }

    /** The per-operation limit, cut to what is left of the whole-attempt budget. */
    public int capTimeout(UUID executionId, int perOperationSeconds) {
        return policy.deadline(executionId)
                .map(deadline -> deadline.capSeconds(clock, perOperationSeconds))
                .orElse(perOperationSeconds);
    }

    public void recordStop(UUID executionId, String stepId, String reason) {
        policy.recordStop(executionId, stepId, reason);
    }

    private Optional<Stop> findStop(UUID executionId, String stage) {
        if (!policy.ownsActiveSlot(executionId)) {
            var forbidden = PUBLICATION.equals(stage) ? "publish" : "continue";
            return Optional.of(new Stop(TaskStops.INSPECTION_STATE,
                    "the task attempt no longer owns the installation's active slot;"
                            + " a stopped, superseded or released attempt must not " + forbidden));
        }
        if (policy.deadline(executionId).filter(deadline -> deadline.expired(clock)).isPresent()) {
            return Optional.of(new Stop(TaskStops.DEADLINE_STATE,
                    "the whole-attempt deadline expired before " + stage
                            + "; queue and human-review waiting are excluded from the budget"));
        }
        return Optional.empty();
    }
}
