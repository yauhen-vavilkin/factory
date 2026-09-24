package org.folio.factory.devfactory.feedback;

import java.util.*;

/** Bounded current operation. Earlier versions are the round history. */
public class FeedbackState {
    public enum Phase { INIT, READY, PREPARE, CODE, VERIFY, PREPARE_PUBLISH, PUBLISH,
        QUESTION, FAILURE, REFRESH_REQUIRED, INTERRUPTED, CLEANUP_BLOCKED, FINISH, FINISHED, STOPPED, SKIPPED }
    public Phase phase = Phase.INIT;
    public Phase resumePhase;
    public Phase cleanupResumePhase;
    public String lastCleanedInvocation = "";
    public List<String> cleanupSteps = new ArrayList<>();
    public int round;
    public String attemptId = "";
    public String reason = "";
    public String failureKind = "";
    public String answer = "";
    public String question = "";
    public List<String> options = List.of();
    public PrFeedbackGateway.PullRequest pr;
    public List<PrFeedbackGateway.Comment> selected = List.of();
    public String snapshotId = "";
    public String startedAt = "";
    public String roundStartedAt = "";
    public String finishedAt = "";
    public String summary = "";
    public String taskSummary = "";
    /** MERGED or CLOSED_WITHOUT_MERGE for new terminal decisions; blank in historical states. */
    public String completionReason = "";
    public boolean manual;
    public String commitSha = "";
    public String runtimeName = "";
    public org.folio.factory.devfactory.verification.VerificationReceipt verification;
    public Map<String, Object> usage = Map.of();
    public Map<String, Object> rate = Map.of();
    public FeedbackState() { }
    public boolean waiting() { return switch (phase) {
        case READY, QUESTION, FAILURE, REFRESH_REQUIRED, INTERRUPTED, CLEANUP_BLOCKED, STOPPED -> true;
        default -> false;
    }; }
}
