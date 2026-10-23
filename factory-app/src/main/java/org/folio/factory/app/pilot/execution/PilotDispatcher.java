package org.folio.factory.app.pilot.execution;

import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
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
 * regardless of the router's dedup window. Only the attempt's terminal outcome
 * (S05) frees the slot for the next task.
 */
@Component
public class PilotDispatcher {

    private final PilotAttemptRepository attempts;
    private final PilotInstallationRepository installations;
    private final PilotExecutionLauncher launcher;

    public PilotDispatcher(PilotAttemptRepository attempts, PilotInstallationRepository installations,
                           PilotExecutionLauncher launcher) {
        this.attempts = attempts;
        this.installations = installations;
        this.launcher = launcher;
    }

    /**
     * @return the execution bound to the active attempt, the newly launched
     *         execution for the next queued attempt, or empty when the queue is
     *         empty or the head of the queue was withdrawn as out of scope
     */
    public Optional<UUID> dispatchNext() {
        PilotInstallation installation = installations.findById(1).orElse(null);
        if (installation != null && installation.getActiveAttemptId() != null) {
            return Optional.of(activeBinding(installation.getActiveAttemptId()));
        }
        List<PilotAttempt> candidates =
                attempts.findDispatchable(PilotAttempt.State.RESERVED, PageRequest.of(0, 1));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(launcher.launch(candidates.getFirst().getId()));
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
