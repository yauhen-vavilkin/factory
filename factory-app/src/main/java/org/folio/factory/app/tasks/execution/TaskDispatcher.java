package org.folio.factory.app.tasks.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Serial dispatch over the durable task queue. The queue is separate from
 * engine executions: dispatchNext creates exactly one execution for the next
 * attempt in stable order and is idempotent — while an attempt owns the
 * installation's active slot, every call returns that attempt's bound execution,
 * regardless of the router's dedup window. The active attempt is first
 * reconciled through the outcome service: dispatch proceeds only after a
 * terminal outcome releases the slot; a held or paused attempt keeps blocking
 * the queue.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskDispatcher {

    private final TaskAttemptRepository attempts;
    private final TaskInstallationRepository installations;
    private final TaskExecutionLauncher launcher;
    private final TaskExecutionProperties properties;
    private final ObjectProvider<TaskOutcomeService> outcomes;
    /** Why the queue head could not start, until a dispatch succeeds or finds nothing to start. */
    private volatile String deferral;

    /**
     * @return the execution bound to the active attempt, the newly launched
     *         execution for the next queued attempt, or empty when the queue is
     *         empty
     */
    public Optional<UUID> dispatchNext() {
        try {
            Optional<UUID> executionId = dispatchNextOrThrow();
            deferral = null;
            return executionId;
        } catch (RuntimeException e) {
            deferral = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            throw e;
        }
    }

    /**
     * Why queued work is not starting, for the page: execution is switched off, or
     * the last dispatch was refused (retention on, a failed preflight, Docker down).
     * Null when the queue can move.
     */
    public String queueProblem() {
        if (!properties.enabled()) {
            return "Task execution is off. Set FACTORY_TASKS_EXECUTION_ENABLED=true in .env and run"
                    + " docker compose up -d.";
        }
        return deferral == null ? null : deferral + " Factory tries again on its next dispatch poll.";
    }

    private Optional<UUID> dispatchNextOrThrow() {
        TaskInstallation installation = installations.findById(1).orElse(null);
        if (installation != null && installation.getActiveAttemptId() != null) {
            TaskOutcomeService outcomeService = outcomes.getIfAvailable();
            if (outcomeService == null
                    || !outcomeService.refresh(installation.getActiveAttemptId()).released()) {
                return Optional.of(activeBinding(installation.getActiveAttemptId()));
            }
        }
        // A head that stops before start leaves RESERVED, so the next pass takes the next attempt.
        while (true) {
            List<TaskAttempt> candidates =
                    attempts.findDispatchable(TaskAttempt.State.RESERVED, PageRequest.of(0, 1));
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            UUID executionId = launcher.launch(candidates.getFirst().getId());
            if (executionId != null) {
                return Optional.of(executionId);
            }
        }
    }

    /** False while FACTORY_TASKS_EXECUTION_ENABLED is off: queued work then waits and nothing launches. */
    public boolean executionEnabled() {
        return properties.enabled();
    }

    /**
     * Best-effort dispatch for admission and scheduling call sites: a refusal
     * (retention enabled, stale sync) or a launch failure only logs, and the
     * attempt stays queued for the scheduled poller to retry. It never fails the
     * caller's admission or poll tick.
     */
    public void dispatchNextQuietly() {
        if (!properties.enabled()) {
            return;
        }
        try {
            dispatchNext();
        } catch (RuntimeException e) {
            log.warn("Task dispatch deferred ({}); the attempt stays queued and the"
                    + " scheduled poller retries", e.getMessage());
        }
    }

    private UUID activeBinding(UUID attemptId) {
        TaskAttempt active = attempts.findById(attemptId)
                .orElseThrow(() -> new IllegalStateException("Active task attempt " + attemptId + " is missing"));
        if (active.getExecutionId() == null) {
            throw new IllegalStateException("Active task attempt " + attemptId + " has no execution binding");
        }
        return active.getExecutionId();
    }
}
