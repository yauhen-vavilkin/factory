package org.folio.factory.app.pilot.execution;

import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Serial dispatch over the durable pilot queue. The queue is separate from
 * engine executions: dispatchNext creates exactly one execution for the next
 * attempt in stable order and is idempotent — while an attempt owns the
 * installation's active slot, every call returns that attempt's bound execution,
 * regardless of the router's dedup window. The active attempt is first
 * reconciled through the outcome service: dispatch proceeds only after a
 * terminal outcome releases the slot; a held or paused attempt keeps blocking
 * the queue.
 */
@Component
public class PilotDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PilotDispatcher.class);

    private final PilotAttemptRepository attempts;
    private final PilotInstallationRepository installations;
    private final PilotExecutionLauncher launcher;
    private final PilotExecutionProperties properties;
    private final ObjectProvider<PilotOutcomeService> outcomes;
    /** Why the queue head could not start, until a dispatch succeeds or finds nothing to start. */
    private volatile String deferral;

    public PilotDispatcher(PilotAttemptRepository attempts, PilotInstallationRepository installations,
                           PilotExecutionLauncher launcher, PilotExecutionProperties properties,
                           ObjectProvider<PilotOutcomeService> outcomes) {
        this.attempts = attempts;
        this.installations = installations;
        this.launcher = launcher;
        this.properties = properties;
        this.outcomes = outcomes;
    }

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
            return "Pilot execution is off. Set FACTORY_PILOT_EXECUTION_ENABLED=true in .env and run"
                    + " docker compose up -d.";
        }
        return deferral == null ? null : deferral + " Factory tries again on its next dispatch poll.";
    }

    private Optional<UUID> dispatchNextOrThrow() {
        PilotInstallation installation = installations.findById(1).orElse(null);
        if (installation != null && installation.getActiveAttemptId() != null) {
            PilotOutcomeService outcomeService = outcomes.getIfAvailable();
            if (outcomeService == null
                    || !outcomeService.refresh(installation.getActiveAttemptId()).released()) {
                return Optional.of(activeBinding(installation.getActiveAttemptId()));
            }
        }
        // A head that stops before start leaves RESERVED, so the next pass takes the next attempt.
        while (true) {
            List<PilotAttempt> candidates =
                    attempts.findDispatchable(PilotAttempt.State.RESERVED, PageRequest.of(0, 1));
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            UUID executionId = launcher.launch(candidates.getFirst().getId());
            if (executionId != null) {
                return Optional.of(executionId);
            }
        }
    }

    /** False while FACTORY_PILOT_EXECUTION_ENABLED is off: queued work then waits and nothing launches. */
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
            log.warn("Pilot dispatch deferred ({}); the attempt stays queued and the"
                    + " scheduled poller retries", e.getMessage());
        }
    }

    private UUID activeBinding(UUID attemptId) {
        PilotAttempt active = attempts.findById(attemptId)
                .orElseThrow(() -> new IllegalStateException("Active pilot attempt " + attemptId + " is missing"));
        if (active.getExecutionId() == null) {
            throw new IllegalStateException("Active pilot attempt " + attemptId + " has no execution binding");
        }
        return active.getExecutionId();
    }
}
