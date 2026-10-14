package org.folio.factory.devfactory.feedback;

import static org.folio.factory.devfactory.feedback.FeedbackArtifacts.*;
import static org.folio.factory.devfactory.feedback.FeedbackState.Phase.*;

import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Network reads happen before short, version-checked command transactions. */
public class FeedbackActions {
    public enum Action {
        FIX,
        ANSWER,
        RETRY_CODE,
        RETRY_CHECKS,
        RETRY_OPERATION,
        MANUAL_CONTINUE,
        RETRY_CLEANUP,
        FINISH
    }

    public record Command(
            UUID reviewId,
            int stateVersion,
            Action action,
            String snapshotId,
            List<String> selectedIds,
            String answer) {}

    public record View(
            FeedbackState state,
            int version,
            UUID reviewId,
            List<Action> actions,
            PrFeedbackGateway.Snapshot snapshot) {}

    private final ArtifactStore artifacts;
    private final PipelineExecutionRepository executions;
    private final HitlReviewRepository reviews;
    private final HitlDecisionService decisions;
    private final FeedbackDecisionPolicy policy;
    private final PrFeedbackGateway github;
    private final TransactionTemplate transactions;

    public FeedbackActions(
            ArtifactStore artifacts,
            PipelineExecutionRepository executions,
            HitlReviewRepository reviews,
            HitlDecisionService decisions,
            FeedbackDecisionPolicy policy,
            PrFeedbackGateway github,
            PlatformTransactionManager tx) {
        this.artifacts = artifacts;
        this.executions = executions;
        this.reviews = reviews;
        this.decisions = decisions;
        this.policy = policy;
        this.github = github;
        this.transactions = new TransactionTemplate(tx);
    }

    public View view(UUID id) {
        var e = executions.findById(id).orElseThrow();
        if (!e.getFlowId().equals("dev-factory"))
            throw new IllegalArgumentException("Not a Developer Flow");
        var artifact = artifacts.getLatest(id, STATE).orElseThrow();
        var state = JSON.readValue(artifact.getContent(), FeedbackState.class);
        var pending =
                reviews.findByExecutionIdOrderByCreatedAtAsc(id).stream()
                        .filter(
                                r ->
                                        r.getStatus() == HitlReviewStatus.PENDING
                                                && r.getStepIndex() == e.getCurrentStepIndex())
                        .findFirst();
        boolean ready =
                e.getStatus() == ExecutionStatus.AWAITING_HITL
                        || e.getStatus() == ExecutionStatus.FAILED_ESCALATED;
        var snapshot =
                artifacts
                        .getLatest(id, SNAPSHOT)
                        .map(a -> JSON.readValue(a.getContent(), PrFeedbackGateway.Snapshot.class))
                        .orElse(null);
        List<Action> availableActions = List.of();
        if (ready && pending.isPresent()) {
            availableActions = e.getStatus() == ExecutionStatus.FAILED_ESCALATED
                    ? List.of(Action.RETRY_CLEANUP) : allowed(state);
        }
        return new View(state, artifact.getVersion(), pending.map(HitlReview::getId).orElse(null),
                availableActions, snapshot);
    }

    public View refresh(UUID id, int expectedVersion) {
        var before = view(id);
        if (before.version() != expectedVersion
                || before.reviewId() == null
                || before.actions().isEmpty()
                || before.actions().contains(Action.RETRY_CLEANUP))
            throw new IllegalStateException("Feedback state changed; reload");
        if (before.state().pr == null)
            throw new IllegalStateException("No delivered PR is available");
        var snapshot = github.comments(before.state().pr);
        transactions.executeWithoutResult(
                status -> {
                    lock(id);
                    var current = view(id);
                    if (current.version() != expectedVersion
                            || !Objects.equals(current.reviewId(), before.reviewId()))
                        throw new IllegalStateException(
                                "Feedback state changed while reading comments");
                    artifacts.putMarkdown(
                            id, SNAPSHOT, JSON.writeValueAsString(snapshot), "pr-feedback:refresh");
                });
        return view(id);
    }

    public void command(UUID id, Command command, String reviewer) {
        if (command.action() == Action.FINISH)
            throw new IllegalArgumentException(
                    "An open PR cannot be finished; Factory completes after merge");
        if (requiresOpenPr(command.action())) {
            var before = view(id);
            if (before.state().pr == null
                    || github.status(before.state().pr) != PrFeedbackGateway.Status.OPEN)
                throw new IllegalStateException("PR is no longer open; reload the execution");
        }
        transactions.executeWithoutResult(
                status -> {
                    lock(id);
                    var current = view(id);
                    if (command.action() == null
                            || current.version() != command.stateVersion()
                            || !Objects.equals(current.reviewId(), command.reviewId())
                            || current.reviewId() == null)
                        throw new IllegalStateException(
                                "Feedback action is stale; reload the execution");
                    if (!current.actions().contains(command.action()))
                        throw new IllegalArgumentException("Action is not available in this state");
                    var state = current.state();
                    state.apply(
                            command.action(),
                            current.snapshot(),
                            command.snapshotId(),
                            command.selectedIds(),
                            command.answer());
                    policy.authorize(
                            current.reviewId(),
                            () ->
                                    decisions.decide(
                                            current.reviewId(),
                                            HitlDecision.APPROVE,
                                            reviewer,
                                            command.action().name(),
                                            null));
                    artifacts.putMarkdown(
                            id,
                            STATE,
                            JSON.writeValueAsString(state),
                            "pr-feedback:" + command.action().name());
                });
    }

    /** Called by the bounded PR monitor after a trusted GitHub status read. */
    public void reconcilePr(UUID id) {
        var execution = executions.findById(id).orElseThrow();
        if (execution.getStatus() != ExecutionStatus.AWAITING_HITL
                || !VERSION.equals(execution.getFlowVersion())) return;
        var before = view(id);
        if (before.reviewId() == null
                || before.state().pr == null
                || before.state().phase() == CLEANUP_BLOCKED) return;
        var review = reviews.findById(before.reviewId()).orElseThrow();
        if (!"pr-feedback".equals(review.getGateId())) return;
        var observed = github.status(before.state().pr);
        if (observed == null)
            throw new IllegalStateException("GitHub returned an unknown PR status");
        if (observed == PrFeedbackGateway.Status.OPEN) return;
        transactions.executeWithoutResult(
                tx -> {
                    lock(id);
                    var current = view(id);
                    if (executions.findById(id).orElseThrow().getStatus()
                                    != ExecutionStatus.AWAITING_HITL
                            || current.version() != before.version()
                            || !Objects.equals(current.reviewId(), before.reviewId())
                            || current.state().phase() == CLEANUP_BLOCKED) return;
                    var state = current.state();
                    if (observed == PrFeedbackGateway.Status.MERGED) {
                        state.merged();
                        policy.authorize(
                                current.reviewId(),
                                HitlDecision.APPROVE,
                                () ->
                                        decisions.decide(
                                                current.reviewId(),
                                                HitlDecision.APPROVE,
                                                "factory-pr-monitor",
                                                "PR merged",
                                                null));
                    } else {
                        state.closed();
                        policy.authorize(
                                current.reviewId(),
                                HitlDecision.REJECT,
                                () ->
                                        decisions.decide(
                                                current.reviewId(),
                                                HitlDecision.REJECT,
                                                "factory-pr-monitor",
                                                state.taskSummary,
                                                null));
                    }
                    artifacts.putMarkdown(
                            id, STATE, JSON.writeValueAsString(state), "pr-feedback:reconcile-pr");
                });
    }

    public String patch(UUID id) {
        view(id);
        return artifacts
                .getLatest(id, CANDIDATE)
                .map(a -> JSON.readTree(a.getContent()).path("patch").asString(""))
                .orElse("");
    }

    public String diagnostics(UUID id) {
        var current = view(id);
        return current.state().reason
                + "\n\n"
                + artifacts
                        .getLatest(id, VERIFICATION)
                        .map(a -> JSON.readTree(a.getContent()).path("output").asString(""))
                        .orElse("");
    }

    private void lock(UUID id) {
        executions.lockById(id).orElseThrow();
    }

    private static boolean requiresOpenPr(Action action) {
        return action != null && action != Action.RETRY_CLEANUP && action != Action.FINISH;
    }

    public static List<Action> allowed(FeedbackState state) {
        if (state.phase() == CLEANUP_BLOCKED) return List.of(Action.RETRY_CLEANUP);
        if (!state.waiting()) return List.of();
        var actions = new ArrayList<Action>();
        if (state.pr != null) {
            actions.add(Action.FIX);
            actions.add(Action.MANUAL_CONTINUE);
        }
        switch (state.phase()) {
            case QUESTION -> {
                if (!"REPOSITORY_MISMATCH".equals(state.failureKind)) actions.add(Action.ANSWER);
            }
            case INTERRUPTED -> actions.add(Action.RETRY_CODE);
            case FAILURE -> {
                if (Set.of("CODING", "CANDIDATE", "CANDIDATE_COMPILE").contains(state.failureKind)) {
                    actions.add(Action.RETRY_CODE);
                }
                if (!Set.of("CODING", "OPERATION").contains(state.failureKind)) {
                    actions.add(Action.RETRY_CHECKS);
                }
                if (state.resumePhase != null && state.resumePhase != CODE && state.resumePhase != FINISH) {
                    actions.add(Action.RETRY_OPERATION);
                }
            }
            default -> {}
        }
        return List.copyOf(actions);
    }
}
