package org.folio.factory.app.pilot.execution;

import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotRoutingReason;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.devfactory.pilot.AttemptDeadline;
import org.folio.factory.devfactory.pilot.PilotAttemptPolicy;
import org.folio.factory.devfactory.pilot.PilotExecutionContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The flow's pilot policy port over the durable admission state. Only an
 * attempt bound to the execution grants pilot policy, so legacy executions
 * resolve to no-pilot and keep their interactive behavior. The paid start is
 * claimed once per attempt, the first stop reason wins, and the persisted
 * whole-attempt deadline plus the installation's active slot bound later side
 * effects.
 */
@Component
public class PersistedPilotAttemptPolicy implements PilotAttemptPolicy {

    static final int MAX_REASON_LENGTH = 500;

    private final PilotAttemptRepository attempts;
    private final PilotTaskRepository tasks;
    private final PilotInstallationRepository installations;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    public PersistedPilotAttemptPolicy(PilotAttemptRepository attempts, PilotTaskRepository tasks,
                                       PilotInstallationRepository installations,
                                       PlatformTransactionManager transactionManager) {
        this.attempts = attempts;
        this.tasks = tasks;
        this.installations = installations;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Optional<PilotExecutionContext> find(UUID executionId) {
        return attempts.findByExecutionId(executionId).map(this::context);
    }

    private PilotExecutionContext context(PilotAttempt attempt) {
        return new PilotExecutionContext(attempt.getId(), approvedSnapshot(attempt), repositoryKey(attempt),
                PilotExecutionContext.Kind.valueOf(attempt.getKind().name()),
                attempt.getInputFingerprint(), attempt.getScopeFingerprint(),
                attempt.getDestinationFingerprint());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> approvedSnapshot(PilotAttempt attempt) {
        String snapshot = attempt.getApprovedSnapshot();
        if (snapshot == null || snapshot.isBlank()) {
            return Map.of();
        }
        return json.readValue(snapshot, Map.class);
    }

    private String repositoryKey(PilotAttempt attempt) {
        return tasks.findById(attempt.getTaskId())
                .map(PilotTask::getRoutingReason)
                .map(PilotRoutingReason::eligibleRepositoryKey)
                .orElse(null);
    }

    @Override
    public boolean claimPaidStart(UUID executionId) {
        try {
            Boolean claimed = transactions.execute(status -> attempts.findByExecutionId(executionId)
                    .filter(attempt -> attempt.getPaidStartAt() == null)
                    .map(attempt -> {
                        attempt.setPaidStartAt(Instant.now());
                        attempts.save(attempt);
                        return true;
                    })
                    .orElse(false));
            return Boolean.TRUE.equals(claimed);
        } catch (OptimisticLockingFailureException e) {
            return false;
        }
    }

    @Override
    public void recordStop(UUID executionId, String stage, String reason) {
        try {
            transactions.executeWithoutResult(status -> attempts.findByExecutionId(executionId)
                    .filter(attempt -> attempt.getResultReason() == null)
                    .ifPresent(attempt -> {
                        attempt.setResultReason(truncated(stage, reason));
                        attempts.save(attempt);
                    }));
        } catch (OptimisticLockingFailureException e) {
            // The first recorded stop reason stays authoritative.
        }
    }

    static String truncated(String stage, String reason) {
        String value = (stage == null ? "" : stage) + ": " + (reason == null ? "" : reason);
        return value.length() <= MAX_REASON_LENGTH ? value : value.substring(0, MAX_REASON_LENGTH);
    }

    @Override
    public Optional<AttemptDeadline> deadline(UUID executionId) {
        return attempts.findByExecutionId(executionId)
                .map(PilotAttempt::getDeadlineAt)
                .filter(Objects::nonNull)
                .map(AttemptDeadline::new);
    }

    @Override
    public boolean ownsActiveSlot(UUID executionId) {
        return attempts.findByExecutionId(executionId)
                .filter(attempt -> attempt.getState().isLive())
                .map(PilotAttempt::getId)
                .flatMap(ownedId -> installations.findById(1)
                        .map(PilotInstallation::getActiveAttemptId)
                        .map(ownedId::equals))
                .orElse(false);
    }
}
