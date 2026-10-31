package org.folio.factory.devfactory.feedback;

import static org.folio.factory.devfactory.feedback.FeedbackArtifacts.*;
import static org.folio.factory.devfactory.feedback.FeedbackState.Phase.*;

import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.domain.PipelineExecution;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.folio.factory.devfactory.DevFactoryConfiguration;

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
        var execution = executions.findById(id).orElseThrow();
        if (!execution.getFlowId().equals(DevFactoryConfiguration.FLOW_ID)) {
            throw new IllegalArgumentException("Not a Developer Flow");
        }
        var artifact = artifacts.getLatest(id, STATE).orElseThrow();
        var state = JSON.readValue(artifact.getContent(), FeedbackState.class);
        var pending = findPendingReview(id, execution);
        var snapshot = findSnapshot(id).orElse(null);
        return new View(state, artifact.getVersion(), pending.map(HitlReview::getId).orElse(null),
                availableActions(execution, pending.isPresent(), state), snapshot);
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
        if (command.action() == Action.FINISH) {
            throw new IllegalArgumentException("An open PR cannot be finished; Factory completes after merge");
        }
        if (requiresOpenPr(command.action())) {
            requireOpenPr(id);
        }
        transactions.executeWithoutResult(status -> applyCommand(id, command, reviewer));
    }

    /** Called by the bounded PR monitor after a trusted GitHub status read. */
    public void reconcilePr(UUID id) {
        var before = findWatchedView(id);
        if (before.isEmpty()) {
            return;
        }
        var observed = github.status(before.get().state().pr);
        if (observed == null) {
            throw new IllegalStateException("GitHub returned an unknown PR status");
        }
        if (observed == PrFeedbackGateway.Status.OPEN) {
            return;
        }
        transactions.executeWithoutResult(tx -> recordPrOutcome(id, before.get(), observed));
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

    private Optional<HitlReview> findPendingReview(UUID id, PipelineExecution execution) {
        return reviews.findByExecutionIdOrderByCreatedAtAsc(id).stream()
                .filter(review -> review.getStatus() == HitlReviewStatus.PENDING
                        && review.getStepIndex() == execution.getCurrentStepIndex())
                .findFirst();
    }

    private Optional<PrFeedbackGateway.Snapshot> findSnapshot(UUID id) {
        return artifacts.getLatest(id, SNAPSHOT)
                .map(artifact -> JSON.readValue(artifact.getContent(), PrFeedbackGateway.Snapshot.class));
    }

    private static List<Action> availableActions(PipelineExecution execution, boolean pending, FeedbackState state) {
        var ready = execution.getStatus() == ExecutionStatus.AWAITING_HITL
                || execution.getStatus() == ExecutionStatus.FAILED_ESCALATED;
        if (!ready || !pending) {
            return List.of();
        }
        return execution.getStatus() == ExecutionStatus.FAILED_ESCALATED
                ? List.of(Action.RETRY_CLEANUP)
                : allowed(state);
    }

    private void requireOpenPr(UUID id) {
        var before = view(id);
        if (before.state().pr == null || github.status(before.state().pr) != PrFeedbackGateway.Status.OPEN) {
            throw new IllegalStateException("PR is no longer open; reload the execution");
        }
    }

    private void applyCommand(UUID id, Command command, String reviewer) {
        lock(id);
        var current = view(id);
        if (command.action() == null
                || current.version() != command.stateVersion()
                || !Objects.equals(current.reviewId(), command.reviewId())
                || current.reviewId() == null) {
            throw new IllegalStateException("Feedback action is stale; reload the execution");
        }
        if (!current.actions().contains(command.action())) {
            throw new IllegalArgumentException("Action is not available in this state");
        }
        var state = current.state();
        state.apply(command.action(), current.snapshot(), command.snapshotId(), command.selectedIds(),
                command.answer());
        policy.authorize(current.reviewId(), HitlDecision.APPROVE, () -> decisions.decide(
                current.reviewId(), HitlDecision.APPROVE, reviewer, command.action().name(), null));
        artifacts.putMarkdown(id, STATE, JSON.writeValueAsString(state), "pr-feedback:" + command.action().name());
    }

    /** The view of an execution that waits at the PR feedback gate with a PR to watch. */
    private Optional<View> findWatchedView(UUID id) {
        var execution = executions.findById(id).orElseThrow();
        if (execution.getStatus() != ExecutionStatus.AWAITING_HITL || !VERSION.equals(execution.getFlowVersion())) {
            return Optional.empty();
        }
        var before = view(id);
        if (before.reviewId() == null || before.state().pr == null || before.state().phase() == CLEANUP_BLOCKED) {
            return Optional.empty();
        }
        var review = reviews.findById(before.reviewId()).orElseThrow();
        return "pr-feedback".equals(review.getGateId()) ? Optional.of(before) : Optional.empty();
    }

    private void recordPrOutcome(UUID id, View before, PrFeedbackGateway.Status observed) {
        lock(id);
        var current = view(id);
        if (isChangedSince(id, before, current)) {
            return;
        }
        var state = current.state();
        if (observed == PrFeedbackGateway.Status.MERGED) {
            state.merged();
            policy.authorize(current.reviewId(), HitlDecision.APPROVE, () -> decisions.decide(
                    current.reviewId(), HitlDecision.APPROVE, "factory-pr-monitor", "PR merged", null));
        } else {
            state.closed();
            policy.authorize(current.reviewId(), HitlDecision.REJECT, () -> decisions.decide(
                    current.reviewId(), HitlDecision.REJECT, "factory-pr-monitor", state.taskSummary, null));
        }
        artifacts.putMarkdown(id, STATE, JSON.writeValueAsString(state), "pr-feedback:reconcile-pr");
    }

    private boolean isChangedSince(UUID id, View before, View current) {
        return executions.findById(id).orElseThrow().getStatus() != ExecutionStatus.AWAITING_HITL
                || current.version() != before.version()
                || !Objects.equals(current.reviewId(), before.reviewId())
                || current.state().phase() == CLEANUP_BLOCKED;
    }

    private void lock(UUID id) {
        executions.lockById(id).orElseThrow();
    }

    private static boolean requiresOpenPr(Action action) {
        return action != null && action != Action.RETRY_CLEANUP && action != Action.FINISH;
    }

    public static List<Action> allowed(FeedbackState state) {
        if (state.phase() == CLEANUP_BLOCKED) {
            return List.of(Action.RETRY_CLEANUP);
        }
        if (!state.waiting()) {
            return List.of();
        }
        var actions = new ArrayList<Action>();
        if (state.pr != null) {
            actions.add(Action.FIX);
            actions.add(Action.MANUAL_CONTINUE);
        }
        switch (state.phase()) {
            case QUESTION -> {
                if (!"REPOSITORY_MISMATCH".equals(state.failureKind)) {
                    actions.add(Action.ANSWER);
                }
            }
            case INTERRUPTED -> actions.add(Action.RETRY_CODE);
            case FAILURE -> addFailureActions(state, actions);
            default -> {}
        }
        return List.copyOf(actions);
    }

    private static void addFailureActions(FeedbackState state, List<Action> actions) {
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
}
