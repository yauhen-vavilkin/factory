package org.folio.factory.app.pilot.presentation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One logical pilot task as the task screens read it: identity and Jira link,
 * the current projected state with reason and stage, delivery identity, the
 * full attempt history with per-attempt accounting, and the controls the
 * current state allows. A pure projection — every action is revalidated by its
 * own service at submit time.
 */
public record PilotTaskView(
        UUID taskId,
        String displayKey,
        String jiraUrl,
        String title,
        String projectKey,
        TaskState state,
        String reason,
        String stage,
        String destinationRepository,
        String pullRequestUrl,
        boolean inScope,
        String eligibility,
        String routingReason,
        long version,
        String inputFingerprint,
        String destinationFingerprint,
        Instant jiraCreatedAt,
        Instant updatedAt,
        List<AttemptHistoryEntry> attempts,
        PilotTaskUsage usage,
        AllowedActions allowedActions) {

    /**
     * Distinct current meanings: not yet delegated, pulled before start,
     * unroutable, work in flight, finished unsuccessfully, held with an
     * unresolved question, delivered, or held by the shared installation pause.
     */
    public enum TaskState {
        AWAITING_SELECTION, WITHDRAWN, UNSUPPORTED, WORKING, STOPPED, UNCERTAIN, PR_READY, PROCESSING_PAUSED
    }

    public record AttemptHistoryEntry(
            UUID attemptId,
            int ordinal,
            String kind,
            String admittedVia,
            String state,
            String stage,
            String reason,
            String pullRequestUrl,
            UUID executionId,
            String executionStatus,
            Instant startedAt,
            Instant deadlineAt,
            boolean paidStart,
            long activeSeconds,
            PilotTaskUsage usage) {
    }

    /**
     * Projected from the same state facts the admission, retry and mode services
     * revalidate: selection is possible only for a never-attempted or
     * all-withdrawn task, Retry only for a stopped latest attempt or a
     * withdrawal after earlier real work, and automatic admission only from
     * manual mode with a complete sync. Fingerprint drift, stale versions and
     * source problems are still rejected by the services on submit.
     */
    public record AllowedActions(boolean select, boolean retry, boolean enableAutomaticAdmission) {
    }
}
