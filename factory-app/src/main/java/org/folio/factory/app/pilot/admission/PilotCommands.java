package org.folio.factory.app.pilot.admission;

import java.util.List;
import java.util.UUID;

/**
 * Immutable admission commands shared by the manual selection UI, the API and
 * (from S07) automatic admission. Selections carry the task version and
 * input/destination identity the user selected against; admission revalidates
 * all of them against the current task before reserving an attempt.
 */
public final class PilotCommands {

    private PilotCommands() {
    }

    public record Selection(UUID taskId, long expectedVersion, String inputFingerprint,
                            String destinationFingerprint) {
    }

    public record RunSelected(String requestId, String expectedAuthorityFingerprint, List<Selection> selections) {
        public RunSelected {
            selections = selections == null ? List.of() : List.copyOf(selections);
        }
    }
}
