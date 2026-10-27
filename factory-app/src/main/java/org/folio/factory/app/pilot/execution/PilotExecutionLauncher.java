package org.folio.factory.app.pilot.execution;

import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.admission.PilotRunSettings;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.core.retention.RetentionProperties;
import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.core.trigger.PreparedManualTrigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * Turns one queued pilot attempt into one engine execution. Payload preparation
 * and preflight run outside the transaction; inside it the installation is
 * locked, the task is locked and revalidated, the execution is created through
 * the prepared router seam with an attempt-specific dedup key, and the execution
 * id is bound to the attempt together with the installation's active-attempt
 * reservation — atomically, so a crash leaves no orphan execution or binding.
 */
@Service
public class PilotExecutionLauncher {

    static final String FLOW_ID = "dev-factory";

    private final PipelineRouter router;
    private final PilotTaskRepository tasks;
    private final PilotAttemptRepository attempts;
    private final PilotInstallationRepository installations;
    private final PilotExecutionProperties properties;
    private final RetentionProperties retention;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    public PilotExecutionLauncher(PipelineRouter router, PilotTaskRepository tasks,
                                  PilotAttemptRepository attempts, PilotInstallationRepository installations,
                                  PilotExecutionProperties properties, RetentionProperties retention,
                                  PlatformTransactionManager transactionManager) {
        this.router = router;
        this.tasks = tasks;
        this.attempts = attempts;
        this.installations = installations;
        this.properties = properties;
        this.retention = retention;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * @return the bound execution id, or null when the attempt stopped before
     *         start: a queued retry whose approved destination changed, or a task
     *         that cannot run any more (see {@link #stopNotRunnable})
     */
    public UUID launch(UUID attemptId) {
        PilotAttempt attempt = attempts.findById(attemptId)
                .orElseThrow(() -> new NoSuchElementException("No pilot attempt " + attemptId));
        if (attempt.getExecutionId() != null) {
            return attempt.getExecutionId();
        }
        PilotTask task = tasks.findById(attempt.getTaskId())
                .orElseThrow(() -> new NoSuchElementException("No pilot task " + attempt.getTaskId()));
        ObjectNode payload = json.createObjectNode();
        payload.put("issueKey", task.getDisplayKey());
        PreparedManualTrigger prepared;
        try {
            // The preflight checks the profile this attempt chose, not the default one.
            prepared = DeveloperPreflight.forRun(PilotRunSettings.settings(attempt.getRunSettings()),
                    () -> router.prepareManual(FLOW_ID, payload, "pilot-attempt=" + attemptId));
        } catch (DeveloperPreflight.TaskNotRunnableException e) {
            transactions.executeWithoutResult(status -> stopNotRunnable(attemptId, e.reason()));
            return null;
        }
        return transactions.execute(status -> launchLocked(attemptId, prepared));
    }

    /**
     * A queued task whose preflight fails because of the task itself (Jira no
     * longer returns it under its key, no repository matches it, its run profile
     * cannot be used) would block the queue forever, so it stops before start:
     * Could not complete, no cost, and Retry stays possible. A failure of Factory, Docker, Jira or GitHub instead
     * keeps the whole queue waiting.
     */
    private void stopNotRunnable(UUID attemptId, String reason) {
        installations.lockById(1);
        PilotAttempt attempt = attempts.findById(attemptId).orElse(null);
        if (attempt == null || attempt.getState() != PilotAttempt.State.RESERVED || attempt.getExecutionId() != null) {
            return;
        }
        attempt.setState(PilotAttempt.State.STOPPED);
        attempt.setResultReason("Could not start: " + reason);
        attempts.save(attempt);
    }

    private UUID launchLocked(UUID attemptId, PreparedManualTrigger prepared) {
        PilotInstallation installation = installations.lockById(1).orElseGet(PilotInstallation::initial);
        if (installation.getActiveAttemptId() != null && !installation.getActiveAttemptId().equals(attemptId)) {
            return attempts.findById(installation.getActiveAttemptId())
                    .map(PilotAttempt::getExecutionId)
                    .orElseThrow(() -> new IllegalStateException("Active pilot attempt "
                            + installation.getActiveAttemptId() + " has no execution binding"));
        }
        PilotAttempt attempt = attempts.findById(attemptId)
                .orElseThrow(() -> new NoSuchElementException("No pilot attempt " + attemptId));
        if (attempt.getExecutionId() != null) {
            return attempt.getExecutionId();
        }
        if (attempt.getState() != PilotAttempt.State.RESERVED) {
            throw new IllegalStateException("Pilot attempt " + attemptId + " is not dispatchable (state "
                    + attempt.getState() + ")");
        }
        // Retention purges attempt history and costs, so a genuinely new launch is
        // refused while it is on; returning an existing binding for live work stays
        // available for reconciliation.
        if (properties.enabled() && retention.enabled()) {
            throw new IllegalStateException("Pilot execution is refused: FACTORY_RETENTION_ENABLED=true would"
                    + " purge pilot attempt history and costs. Set FACTORY_RETENTION_ENABLED=false in .env"
                    + " and run docker compose up -d again.");
        }
        PilotTask task = tasks.lockById(attempt.getTaskId())
                .orElseThrow(() -> new NoSuchElementException("No pilot task " + attempt.getTaskId()));
        if (attempt.getKind() == PilotAttempt.Kind.RETRY) {
            String change = approvedRetryChange(attempt, task);
            if (change != null) {
                attempt.setState(PilotAttempt.State.STOPPED);
                attempt.setResultReason(change);
                attempts.save(attempt);
                return null;
            }
        }
        UUID executionId = router.routePrepared(prepared);
        attempt.setState(PilotAttempt.State.QUEUED);
        attempt.setExecutionId(executionId);
        // Preparation (and with it the whole-attempt budget) starts here: queue
        // waiting in RESERVED never consumes the budget and a replayed launch
        // cannot reset the persisted deadline.
        Instant startedAt = Instant.now();
        attempt.setStartedAt(startedAt);
        attempt.setDeadlineAt(startedAt.plus(properties.deadline()));
        attempts.save(attempt);
        installation.setActiveAttemptId(attemptId);
        installations.save(installation);
        return executionId;
    }

    /**
     * A queued retry keeps the destination it was explicitly approved for. If the
     * routing changed before start, the attempt stops instead of launching, and
     * only another explicit Retry may admit fresh work. Changes in Jira after
     * the click do not matter: the run uses the snapshot approved at the click.
     */
    private String approvedRetryChange(PilotAttempt attempt, PilotTask task) {
        if (!Objects.equals(attempt.getDestinationFingerprint(),
                PilotAdmissionService.destinationFingerprint(task))) {
            return "the approved destination changed after the retry was approved; the approved retry"
                    + " no longer matches; run a fresh explicit Retry";
        }
        return null;
    }
}
