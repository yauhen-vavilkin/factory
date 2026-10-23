package org.folio.factory.app.pilot.execution;

import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.core.trigger.PreparedManualTrigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.NoSuchElementException;
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
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    public PilotExecutionLauncher(PipelineRouter router, PilotTaskRepository tasks,
                                  PilotAttemptRepository attempts, PilotInstallationRepository installations,
                                  PilotExecutionProperties properties,
                                  PlatformTransactionManager transactionManager) {
        this.router = router;
        this.tasks = tasks;
        this.attempts = attempts;
        this.installations = installations;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * @return the bound execution id, or null when the attempt was withdrawn
     *         because its task left the pilot scope before start
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
        PreparedManualTrigger prepared = router.prepareManual(FLOW_ID, payload, "pilot-attempt=" + attemptId);
        return transactions.execute(status -> launchLocked(attemptId, prepared));
    }

    private UUID launchLocked(UUID attemptId, PreparedManualTrigger prepared) {
        PilotInstallation installation = installations.lockById(1).orElseGet(PilotInstallation::initial);
        if (!installation.isSyncComplete() || installation.getSyncProblem() != null) {
            throw new IllegalStateException("Pilot dispatch is blocked: pilot source sync is incomplete"
                    + (installation.getSyncProblem() == null ? "" : ": " + installation.getSyncProblem())
                    + "; refresh discovery first");
        }
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
        PilotTask task = tasks.lockById(attempt.getTaskId())
                .orElseThrow(() -> new NoSuchElementException("No pilot task " + attempt.getTaskId()));
        if (!task.isInScope()) {
            attempt.setState(PilotAttempt.State.WITHDRAWN);
            attempt.setResultReason("task left the pilot scope before start");
            attempts.save(attempt);
            return null;
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
}
