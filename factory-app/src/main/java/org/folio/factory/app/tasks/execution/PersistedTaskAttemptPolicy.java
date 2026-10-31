package org.folio.factory.app.tasks.execution;

import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.TaskRoutingReason;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.app.tasks.persistence.TaskPublicationIntent;
import org.folio.factory.app.tasks.persistence.TaskPublicationIntentRepository;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.tasks.AttemptDeadline;
import org.folio.factory.devfactory.tasks.TaskAttemptPolicy;
import org.folio.factory.devfactory.tasks.TaskExecutionContext;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The flow's task policy port over the durable admission state. Only an
 * attempt bound to the execution grants task policy, so legacy executions
 * resolve to no task policy and keep their interactive behavior. The paid start is
 * claimed once per attempt, the first stop reason wins, and the persisted
 * whole-attempt deadline plus the installation's active slot bound later side
 * effects. Publication is gated by {@link TaskPublicationGuard} and the exact
 * publication identity is persisted before the first remote mutation.
 */
@Component
public class PersistedTaskAttemptPolicy implements TaskAttemptPolicy {

    static final int MAX_REASON_LENGTH = 500;

    private final TaskAttemptRepository attempts;
    private final TaskRepository tasks;
    private final TaskInstallationRepository installations;
    private final TaskPublicationIntentRepository intents;
    private final TaskPublicationGuard publicationGuard;
    private final TransactionTemplate transactions;

    public PersistedTaskAttemptPolicy(TaskAttemptRepository attempts, TaskRepository tasks,
                                       TaskInstallationRepository installations,
                                       TaskPublicationIntentRepository intents,
                                       TaskPublicationGuard publicationGuard,
                                       PlatformTransactionManager transactionManager) {
        this.attempts = attempts;
        this.tasks = tasks;
        this.installations = installations;
        this.intents = intents;
        this.publicationGuard = publicationGuard;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Optional<TaskExecutionContext> find(UUID executionId) {
        return attempts.findByExecutionId(executionId).map(this::context);
    }

    private TaskExecutionContext context(TaskAttempt attempt) {
        return new TaskExecutionContext(attempt.getId(), attempt.approvedSnapshotMap(), repositoryKey(attempt),
                TaskRunSettings.settings(attempt.getRunSettings()));
    }

    private String repositoryKey(TaskAttempt attempt) {
        return tasks.findById(attempt.getTaskId())
                .map(Task::getRoutingReason)
                .map(TaskRoutingReason::eligibleRepositoryKey)
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
        return capReason((stage == null ? "" : stage) + ": " + (reason == null ? "" : reason));
    }

    /** A reason cut to the length of the result_reason column. */
    static String capReason(String reason) {
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }

    @Override
    public Optional<AttemptDeadline> deadline(UUID executionId) {
        return attempts.findByExecutionId(executionId)
                .map(TaskAttempt::getDeadlineAt)
                .filter(Objects::nonNull)
                .map(AttemptDeadline::new);
    }

    @Override
    public boolean ownsActiveSlot(UUID executionId) {
        return attempts.findByExecutionId(executionId)
                .filter(attempt -> attempt.getState().isLive())
                .map(TaskAttempt::getId)
                .flatMap(ownedId -> installations.findById(1)
                        .map(TaskInstallation::getActiveAttemptId)
                        .map(ownedId::equals))
                .orElse(false);
    }

    /** Delegates to the publication guard for task executions; no attempt, no-op. */
    @Override
    public void beforePublication(UUID executionId, CodingRequest request, DeliveryTarget destination) {
        if (attempts.findByExecutionId(executionId).isEmpty()) {
            return;
        }
        publicationGuard.requireCurrent(executionId, request, destination);
    }

    /** The first intent per attempt (and per execution) stays authoritative. */
    @Override
    public void recordPublicationIntent(UUID executionId, TaskAttemptPolicy.PublicationIntent intent) {
        if (attempts.findByExecutionId(executionId).isEmpty()) {
            return;
        }
        try {
            transactions.executeWithoutResult(status -> attempts.findByExecutionId(executionId)
                    .filter(attempt -> intents.findByAttemptId(attempt.getId()).isEmpty())
                    .ifPresent(attempt -> intents.save(TaskPublicationIntent.of(
                            attempt.getId(), executionId, intent.repository(), intent.baseBranch(),
                            intent.headBranch(), intent.baseSha(), intent.treeSha(),
                            intent.patchSha256()))));
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
            // A concurrent save or insert recorded the same first intent; it stays authoritative.
        }
    }
}
