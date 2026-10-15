package org.folio.factory.devfactory.feedback;

import static org.folio.factory.devfactory.feedback.FeedbackState.Phase.*;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.verification.VerificationReceipt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded current operation. Earlier versions are the round history. */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class FeedbackState {
    public enum Phase {
        INIT,
        READY,
        PREPARE,
        CODE,
        VERIFY,
        PREPARE_PUBLISH,
        PUBLISH,
        QUESTION,
        FAILURE,
        REFRESH_REQUIRED,
        INTERRUPTED,
        CLEANUP_BLOCKED,
        FINISH,
        FINISHED,
        STOPPED,
        SKIPPED
    }

    private Phase phase = Phase.INIT;
    Phase resumePhase;
    Phase cleanupResumePhase;
    String lastCleanedInvocation = "";
    List<String> cleanupSteps = new ArrayList<>();
    int round;
    String attemptId = "";
    String reason = "";
    String failureKind = "";
    String answer = "";
    String question = "";
    List<String> options = List.of();
    PrFeedbackGateway.PullRequest pr;
    List<PrFeedbackGateway.Comment> selected = List.of();
    String snapshotId = "";
    String startedAt = "";
    String roundStartedAt = "";
    String finishedAt = "";
    String summary = "";
    String taskSummary = "";

    /** MERGED or CLOSED_WITHOUT_MERGE for new terminal decisions; blank in historical states. */
    String completionReason = "";

    boolean manual;
    String commitSha = "";
    String runtimeName = "";
    VerificationReceipt verification;
    Map<String, Object> usage = Map.of();
    Map<String, Object> rate = Map.of();

    public FeedbackState() {}

    public Phase phase() {
        return phase;
    }

    public Phase resumePhase() {
        return resumePhase;
    }

    public Phase cleanupResumePhase() {
        return cleanupResumePhase;
    }

    public String lastCleanedInvocation() {
        return lastCleanedInvocation;
    }

    public List<String> cleanupSteps() {
        return List.copyOf(cleanupSteps);
    }

    public int round() {
        return round;
    }

    public String attemptId() {
        return attemptId;
    }

    public String reason() {
        return reason;
    }

    public String failureKind() {
        return failureKind;
    }

    public String answer() {
        return answer;
    }

    public String question() {
        return question;
    }

    public List<String> options() {
        return options;
    }

    public PrFeedbackGateway.PullRequest pr() {
        return pr;
    }

    public List<PrFeedbackGateway.Comment> selected() {
        return selected;
    }

    public String snapshotId() {
        return snapshotId;
    }

    public String startedAt() {
        return startedAt;
    }

    public String roundStartedAt() {
        return roundStartedAt;
    }

    public String finishedAt() {
        return finishedAt;
    }

    public String summary() {
        return summary;
    }

    public String taskSummary() {
        return taskSummary;
    }

    public String completionReason() {
        return completionReason;
    }

    public boolean manual() {
        return manual;
    }

    public String commitSha() {
        return commitSha;
    }

    public String runtimeName() {
        return runtimeName;
    }

    public VerificationReceipt verification() {
        return verification;
    }

    public Map<String, Object> usage() {
        return usage;
    }

    public Map<String, Object> rate() {
        return rate;
    }

    public boolean waiting() {
        return switch (phase) {
            case READY,
                    QUESTION,
                    FAILURE,
                    REFRESH_REQUIRED,
                    INTERRUPTED,
                    CLEANUP_BLOCKED,
                    STOPPED ->
                    true;
            default -> false;
        };
    }

    /** Called only after the command's artifact version and review have been checked. */
    void apply(
            FeedbackActions.Action action,
            PrFeedbackGateway.Snapshot snapshot,
            String expectedSnapshotId,
            List<String> selectedIds,
            String response) {
        if (action != FeedbackActions.Action.RETRY_CLEANUP
                && !FeedbackActions.allowed(this).contains(action))
            throw new IllegalArgumentException("Action is not available in this state");
        switch (action) {
            case FIX -> selectFeedback(snapshot, expectedSnapshotId, selectedIds);
            case ANSWER -> answerQuestion(response);
            case RETRY_CODE -> {
                question = "Independent verification or coding failure: " + reason;
                answer = "Repair the preserved candidate using the saved diagnostics. Keep the"
                        + " authorized scope.";
                startCoding();
            }
            case RETRY_CHECKS -> phase = VERIFY;
            case RETRY_OPERATION -> phase = resumePhase;
            case MANUAL_CONTINUE -> startRound(true, List.of());
            case RETRY_CLEANUP -> {
                // An unexpected engine escalation must also pass strict resource reconciliation.
                if (phase != CLEANUP_BLOCKED) {
                    cleanupResumePhase = phase == CODE ? INTERRUPTED : phase;
                    phase = CLEANUP_BLOCKED;
                }
            }
            case FINISH -> throw new IllegalArgumentException("Finish task is no longer available");
        }
    }

    private void selectFeedback(
            PrFeedbackGateway.Snapshot snapshot,
            String expectedSnapshotId,
            List<String> selectedIds) {
        if (snapshot == null || !snapshot.id().equals(expectedSnapshotId) || !snapshot.pr().open()) {
            throw new IllegalArgumentException("Refresh the open PR before selecting feedback");
        }
        Set<String> ids = selectedIds == null ? Set.of() : new LinkedHashSet<>(selectedIds);
        var selection = snapshot.comments().stream().filter(comment -> ids.contains(comment.id())).toList();
        if (ids.isEmpty() || selection.size() != ids.size()) {
            throw new IllegalArgumentException("Select current comments");
        }
        snapshotId = snapshot.id();
        pr = snapshot.pr();
        startRound(false, selection);
    }

    private void answerQuestion(String response) {
        if (response == null || response.isBlank() || response.length() > 16000) {
            throw new IllegalArgumentException("A bounded, nonempty answer is required");
        }
        answer = response;
        startCoding();
    }

    private void startRound(boolean manual, List<PrFeedbackGateway.Comment> selection) {
        round++;
        this.manual = manual;
        selected = selection;
        phase = PREPARE;
        startAttempt();
        roundStartedAt = startedAt;
    }

    private void startCoding() {
        phase = CODE;
        startAttempt();
    }

    private void startAttempt() {
        attemptId = UUID.randomUUID().toString();
        startedAt = Instant.now().toString();
        finishedAt = "";
    }

    void skip() {
        require(Phase.INIT);
        phase = Phase.SKIPPED;
    }

    void initialized() {
        require(Phase.INIT);
        phase = Phase.READY;
    }

    void prepared() {
        require(Phase.PREPARE);
        reason = "";
        summary = "";
        commitSha = "";
        usage = Map.of();
        verification = null;
        resumePhase = null;
        question = "";
        options = List.of();
        phase = manual ? Phase.VERIFY : Phase.CODE;
    }

    void publishPrepared() {
        require(Phase.PREPARE_PUBLISH);
        phase = Phase.PUBLISH;
    }

    void ready() {
        if (phase != Phase.PUBLISH && phase != Phase.PREPARE_PUBLISH)
            throw new IllegalStateException("Publication is not ready");
        phase = Phase.READY;
        finishedAt = Instant.now().toString();
    }

    void refreshRequired(String message) {
        phase = Phase.REFRESH_REQUIRED;
        reason = message;
    }

    void merged() {
        phase = Phase.FINISH;
        completionReason = "MERGED";
        reason = "";
    }

    void closed() {
        phase = Phase.STOPPED;
        completionReason = "CLOSED_WITHOUT_MERGE";
        taskSummary = "PR closed without merge";
        reason = taskSummary;
    }

    void finished() {
        require(Phase.FINISH);
        String prefix = "MERGED".equals(completionReason) ? "PR merged" : "Task finished";
        taskSummary = reason.isBlank() ? prefix : prefix + " with unresolved items: " + reason;
        phase = Phase.FINISHED;
        if (finishedAt.isBlank()) finishedAt = Instant.now().toString();
    }

    void cleanupBlocked(Phase resume, String message) {
        if (phase != Phase.CLEANUP_BLOCKED) cleanupResumePhase = resume;
        phase = Phase.CLEANUP_BLOCKED;
        reason = message;
    }

    void cleaned(String invocation) {
        require(Phase.CLEANUP_BLOCKED);
        phase = cleanupResumePhase == null ? Phase.INTERRUPTED : cleanupResumePhase;
        cleanupResumePhase = null;
        lastCleanedInvocation = invocation;
        reason = "Resources cleaned up. " + (reason == null ? "" : reason);
    }

    void interrupted(String invocation) {
        require(Phase.CODE);
        phase = Phase.INTERRUPTED;
        lastCleanedInvocation = invocation;
        reason =
                "A previous coding attempt was interrupted. Its latest changes and cost may be"
                        + " incomplete. Start a new attempt explicitly.";
    }

    void failed(String message) {
        if (phase != Phase.CLEANUP_BLOCKED && phase != Phase.REFRESH_REQUIRED) {
            resumePhase = phase;
            failureKind = switch (phase) {
                case PUBLISH -> "PUBLICATION";
                case CODE -> "CODING";
                case PREPARE_PUBLISH -> "VERIFICATION";
                default -> "OPERATION";
            };
            phase = Phase.FAILURE;
        }
        reason = message;
    }

    void coded(CodingOutcome outcome) {
        require(Phase.CODE);
        summary = outcome.summary();
        switch (outcome.status()) {
            case COMPLETED -> {
                phase = Phase.VERIFY;
                reason = "";
            }
            case NEEDS_DECISION -> {
                phase = Phase.QUESTION;
                question = outcome.decision().question();
                options = outcome.decision().options();
                reason = question;
                failureKind = outcome.decision().kind().name();
            }
            case FAILED -> {
                phase = Phase.FAILURE;
                failureKind = "CODING";
                reason = outcome.failure().message();
            }
        }
    }

    void verified(VerificationReceipt receipt) {
        require(Phase.VERIFY);
        verification = receipt;
        if (receipt.result().equals("PASS")) {
            phase = Phase.PREPARE_PUBLISH;
            reason = "";
        } else {
            phase = Phase.FAILURE;
            failureKind = receipt.failureKind().name();
            reason = "Independent checks failed: " + receipt.failureKind();
        }
    }

    private void require(Phase expected) {
        if (phase != expected)
            throw new IllegalStateException("Expected " + expected + " but was " + phase);
    }
}
