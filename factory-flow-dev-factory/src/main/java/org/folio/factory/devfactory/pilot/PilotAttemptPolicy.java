package org.folio.factory.devfactory.pilot;

import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.runtime.CodingRequest;

import java.util.Optional;
import java.util.UUID;

/**
 * Execution policy for pilot attempts, persisted by the application. The
 * persisted execution binding grants pilot policy — never the trigger payload;
 * an unresolved execution keeps the legacy interactive behavior.
 */
public interface PilotAttemptPolicy {

    /**
     * The exact publication identity of one attempt, persisted before the first
     * remote mutation so a lost push or pull-request response can be reconciled
     * against what was intended instead of guessed. The application resolves and
     * stores the attempt and execution ids alongside this identity.
     */
    record PublicationIntent(
            String repository, String baseBranch, String headBranch,
            String baseSha, String treeSha, String patchSha256) {
    }

    Optional<PilotExecutionContext> find(UUID executionId);

    /**
     * Reserves the one paid initial coding invocation of the pilot attempt bound
     * to the execution. A replayed or retried engine step cannot start a second
     * one.
     */
    boolean claimPaidStart(UUID executionId);

    /**
     * Records that the pilot attempt stopped in the given flow stage. The first
     * reason stays authoritative.
     */
    void recordStop(UUID executionId, String stage, String reason);

    /**
     * The whole-attempt budget persisted when preparation started, or empty for
     * executions without a pilot deadline.
     */
    Optional<AttemptDeadline> deadline(UUID executionId);

    /**
     * Whether the attempt bound to the execution still owns the installation's
     * active slot. A released or superseded attempt must start no further side
     * effects. An execution with no persisted attempt row returns false; the
     * standalone {@link #none()} policy always owns its slot.
     */
    boolean ownsActiveSlot(UUID executionId);

    /**
     * Rechecks current publication authority immediately before a remote
     * mutation: the normalized consumed input, the task's scope eligibility and
     * the destination must still match the approved admission. A mismatch, a
     * revoked destination or an unavailable recheck must stop publication; legacy
     * executions are a no-op.
     */
    default void beforePublication(UUID executionId, CodingRequest request, DeliveryTarget destination) {
    }

    /**
     * Persists the exact publication identity before the first remote mutation
     * of the attempt. The first recorded intent stays authoritative; legacy
     * executions are a no-op.
     */
    default void recordPublicationIntent(UUID executionId, PublicationIntent intent) {
    }

    /** Standalone workers may run without the application's pilot persistence. */
    static PilotAttemptPolicy none() {
        return new PilotAttemptPolicy() {
            @Override
            public Optional<PilotExecutionContext> find(UUID executionId) {
                return Optional.empty();
            }

            @Override
            public boolean claimPaidStart(UUID executionId) {
                return true;
            }

            @Override
            public void recordStop(UUID executionId, String stage, String reason) {
            }

            @Override
            public Optional<AttemptDeadline> deadline(UUID executionId) {
                return Optional.empty();
            }

            @Override
            public boolean ownsActiveSlot(UUID executionId) {
                return true;
            }
        };
    }
}
