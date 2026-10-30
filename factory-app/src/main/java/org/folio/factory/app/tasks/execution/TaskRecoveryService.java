package org.folio.factory.app.tasks.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Startup and periodic reconciliation of the one reserved task attempt: it
 * re-examines the installation's active attempt through the outcome service so
 * a terminal execution, an expired budget or an ownership loss resolves into a
 * released slot, an installation pause, or a visible held attempt. Disabled
 * unless task execution processing is enabled.
 */
@Component
@ConditionalOnProperty(prefix = "factory.tasks.execution", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class TaskRecoveryService {

    private final TaskInstallationRepository installations;
    private final TaskOutcomeService outcomes;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reconcileActive();
    }

    @Scheduled(fixedDelayString = "${factory.tasks.execution.recovery-poll-interval-ms:60000}")
    public void scheduledReconcile() {
        reconcileActive();
    }

    public void reconcileActive() {
        installations.findById(1)
                .map(TaskInstallation::getActiveAttemptId)
                .ifPresent(this::reconcile);
    }

    private void reconcile(UUID attemptId) {
        try {
            TaskOutcomeService.AttemptOutcome outcome = outcomes.refresh(attemptId);
            if (outcome.state() != TaskOutcomeService.AttemptOutcome.State.PROCESSING) {
                log.info("Reconciled active task attempt {} to {}{}", attemptId, outcome.state(),
                        outcome.uncertainty() == null ? "" : ": " + outcome.uncertainty());
            }
        } catch (RuntimeException e) {
            log.warn("Could not reconcile active task attempt {}: {}", attemptId, e.getMessage());
        }
    }
}
