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
     *         empty or the head of the queue was withdrawn as out of scope
     */
    public Optional<UUID> dispatchNext() {
        PilotInstallation installation = installations.findById(1).orElse(null);
        if (installation != null && installation.getActiveAttemptId() != null) {
            PilotOutcomeService outcomeService = outcomes.getIfAvailable();
            if (outcomeService == null
                    || !outcomeService.refresh(installation.getActiveAttemptId()).released()) {
                return Optional.of(activeBinding(installation.getActiveAttemptId()));
            }
        }
        List<PilotAttempt> candidates =
                attempts.findDispatchable(PilotAttempt.State.RESERVED, PageRequest.of(0, 1));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(launcher.launch(candidates.getFirst().getId()));
    }

    /**
     * Best-effort dispatch for admission and scheduling call sites: a refusal
     * (readiness not passed, stale sync) or a launch failure only logs, and the
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
