package org.folio.factory.app.pilot.admission;

import java.util.List;
import java.util.UUID;

/**
 * Immutable admission commands of My tasks: running selected tasks and an
 * explicit Retry. Selections carry the task
 * version and input/destination identity the user selected against; admission
 * revalidates all of them against the current task before reserving an attempt.
 * runSettings is the checked snapshot from {@link PilotRunSettings} (null: the defaults).
 */
public final class PilotCommands {

    private PilotCommands() {
    }

    public record Selection(UUID taskId, long expectedVersion, String inputFingerprint,
                            String destinationFingerprint) {
    }

    public record RunSelected(String requestId, List<Selection> selections, String runSettings) {
        public RunSelected {
            selections = selections == null ? List.of() : List.copyOf(selections);
        }

        public RunSelected(String requestId, List<Selection> selections) {
            this(requestId, selections, null);
        }
    }

    public record Retry(String requestId, UUID taskId, long expectedVersion, String inputFingerprint,
                        String destinationFingerprint, String runSettings) {
        public Retry(String requestId, UUID taskId, long expectedVersion, String inputFingerprint,
                     String destinationFingerprint) {
            this(requestId, taskId, expectedVersion, inputFingerprint, destinationFingerprint, null);
        }
    }
}
