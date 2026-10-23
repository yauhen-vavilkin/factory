package org.folio.factory.devfactory.pilot;

import java.util.Optional;
import java.util.UUID;

/**
 * Execution policy for pilot attempts, persisted by the application. The
 * persisted execution binding grants pilot policy — never the trigger payload;
 * an unresolved execution keeps the legacy interactive behavior.
 */
public interface PilotAttemptPolicy {

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
