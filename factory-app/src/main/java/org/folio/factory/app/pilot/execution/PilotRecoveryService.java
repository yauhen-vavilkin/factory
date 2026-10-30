package org.folio.factory.app.pilot.execution;

import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Startup and periodic reconciliation of the one reserved pilot attempt: it
 * re-examines the installation's active attempt through the outcome service so
 * a terminal execution, an expired budget or an ownership loss resolves into a
 * released slot, an installation pause, or a visible held attempt. Disabled
 * unless pilot execution processing is enabled.
 */
@Component
@ConditionalOnProperty(prefix = "factory.pilot.execution", name = "enabled", havingValue = "true")
public class PilotRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PilotRecoveryService.class);

    private final PilotInstallationRepository installations;
    private final PilotOutcomeService outcomes;

    public PilotRecoveryService(PilotInstallationRepository installations, PilotOutcomeService outcomes) {
        this.installations = installations;
        this.outcomes = outcomes;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reconcileActive();
    }

    @Scheduled(fixedDelayString = "${factory.pilot.execution.recovery-poll-interval-ms:60000}")
    public void scheduledReconcile() {
        reconcileActive();
    }

    public void reconcileActive() {
        installations.findById(1)
                .map(PilotInstallation::getActiveAttemptId)
                .ifPresent(this::reconcile);
    }

    private void reconcile(UUID attemptId) {
        try {
            PilotOutcomeService.AttemptOutcome outcome = outcomes.refresh(attemptId);
            if (outcome.state() != PilotOutcomeService.AttemptOutcome.State.PROCESSING) {
                log.info("Reconciled active pilot attempt {} to {}{}", attemptId, outcome.state(),
                        outcome.uncertainty() == null ? "" : ": " + outcome.uncertainty());
            }
        } catch (RuntimeException e) {
            log.warn("Could not reconcile active pilot attempt {}: {}", attemptId, e.getMessage());
        }
    }
}
