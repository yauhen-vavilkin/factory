package org.folio.factory.devfactory.pilot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Recording fake for pilot policy behavior in flow-module tests. */
public final class RecordingPilotAttempts implements PilotAttemptPolicy {

    public final List<String> stops = new ArrayList<>();
    public int paidStartClaims;

    private final PilotExecutionContext context;
    private boolean claimUsed;

    public RecordingPilotAttempts(PilotExecutionContext context) {
        this.context = context;
    }

    public static RecordingPilotAttempts pilot(String repositoryKey) {
        return new RecordingPilotAttempts(context(repositoryKey));
    }

    public static RecordingPilotAttempts pilot() {
        return new RecordingPilotAttempts(context(null));
    }

    public static RecordingPilotAttempts none() {
        return new RecordingPilotAttempts(null);
    }

    private static PilotExecutionContext context(String repositoryKey) {
        return new PilotExecutionContext(
                UUID.randomUUID(),
                Map.of("key", "TASK-1", "summary", "Summary", "description", "Description",
                        "components", List.of(), "comments", List.of(), "links", List.of()),
                repositoryKey,
                PilotExecutionContext.Kind.INITIAL,
                "input-fingerprint", "scope-fingerprint", "destination-fingerprint");
    }

    @Override
    public java.util.Optional<PilotExecutionContext> find(UUID executionId) {
        return java.util.Optional.ofNullable(context);
    }

    @Override
    public boolean claimPaidStart(UUID executionId) {
        if (context == null) {
            return true;
        }
        paidStartClaims++;
        if (claimUsed) {
            return false;
        }
        claimUsed = true;
        return true;
    }

    @Override
    public void recordStop(UUID executionId, String stage, String reason) {
        stops.add(stage + ": " + reason);
    }
}
