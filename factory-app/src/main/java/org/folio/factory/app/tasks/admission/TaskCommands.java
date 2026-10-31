package org.folio.factory.app.tasks.admission;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;

/**
 * Immutable admission commands of My tasks: running selected tasks and an
 * explicit Retry. Selections carry the task
 * version and input/destination identity the user selected against; admission
 * revalidates all of them against the current task before reserving an attempt.
 * runSettings is the checked snapshot from {@link TaskRunSettings} (null: the defaults).
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class TaskCommands {

    public record Selection(UUID taskId, long expectedVersion, String inputFingerprint,
                            String destinationFingerprint) {
    }

    public record RunSelected(String requestId, List<Selection> selections, String runSettings) {
        public RunSelected {
            selections = selections == null ? List.of() : List.copyOf(selections);
        }
    }

    public record Retry(String requestId, UUID taskId, long expectedVersion, String inputFingerprint,
                        String destinationFingerprint, String runSettings) {
    }
}
