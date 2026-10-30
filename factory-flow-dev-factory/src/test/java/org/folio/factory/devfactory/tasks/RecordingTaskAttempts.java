package org.folio.factory.devfactory.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Recording fake for task policy behavior in flow-module tests. */
public final class RecordingTaskAttempts implements TaskAttemptPolicy {

    public final List<String> stops = new ArrayList<>();
    public final List<String> authorityChecks = new ArrayList<>();
    public final List<TaskAttemptPolicy.PublicationIntent> intents = new ArrayList<>();
    public int paidStartClaims;
    public AttemptDeadline deadline;
    public boolean ownsSlot = true;
    public RuntimeException authorityFailure;
    /** When positive, the authority failure first fires on that 1-based check; earlier checks pass. */
    public int authorityFailureOnCall;

    private final TaskExecutionContext context;
    private boolean claimUsed;

    public RecordingTaskAttempts(TaskExecutionContext context) {
        this.context = context;
    }

    public static RecordingTaskAttempts taskRun(String repositoryKey) {
        return new RecordingTaskAttempts(context(repositoryKey));
    }

    public static RecordingTaskAttempts taskRun() {
        return new RecordingTaskAttempts(context(null));
    }

    public static RecordingTaskAttempts none() {
        return new RecordingTaskAttempts(null);
    }

    private static TaskExecutionContext context(String repositoryKey) {
        return new TaskExecutionContext(
                UUID.randomUUID(),
                Map.of("key", "TASK-1", "summary", "Summary", "description", "Description",
                        "components", List.of(), "comments", List.of(), "links", List.of()),
                repositoryKey,
                TaskExecutionContext.Kind.INITIAL,
                "input-fingerprint", "destination-fingerprint");
    }

    @Override
    public java.util.Optional<TaskExecutionContext> find(UUID executionId) {
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

    /** Sets the deadline returned for every subsequent deadline call. */
    public RecordingTaskAttempts withDeadline(AttemptDeadline deadline) {
        this.deadline = deadline;
        return this;
    }

    @Override
    public java.util.Optional<AttemptDeadline> deadline(UUID executionId) {
        return java.util.Optional.ofNullable(deadline);
    }

    @Override
    public boolean ownsActiveSlot(UUID executionId) {
        return context == null || ownsSlot;
    }

    @Override
    public void beforePublication(UUID executionId, org.folio.factory.devfactory.runtime.CodingRequest request,
                                  org.folio.factory.devfactory.delivery.DeliveryTarget destination) {
        if (context == null) {
            return;
        }
        authorityChecks.add(executionId + ":" + destination.repository());
        if (authorityFailure != null && authorityChecks.size() >= Math.max(authorityFailureOnCall, 1)) {
            throw authorityFailure;
        }
    }

    @Override
    public void recordPublicationIntent(UUID executionId, TaskAttemptPolicy.PublicationIntent intent) {
        if (context == null) {
            return;
        }
        intents.add(intent);
    }
}
