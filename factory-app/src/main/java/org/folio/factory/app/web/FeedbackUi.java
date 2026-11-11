package org.folio.factory.app.web;

import static org.folio.factory.devfactory.feedback.FeedbackArtifacts.*;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.presentation.FeedbackStanding;
import org.folio.factory.app.tasks.presentation.TaskView;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.devfactory.feedback.FeedbackActions;
import org.folio.factory.devfactory.feedback.FeedbackState;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class FeedbackUi {
    private static final String MANUAL_NOTE =
            "Runs the checks on commits you pushed to the PR branch yourself. No agent, no cost.";

    private final FeedbackActions actions;
    private final ArtifactStore artifactStore;
    private final PipelineExecutionRepository executions;

    /**
     * Where PR feedback stands on the run of this task's pull request, as the stored state and comments say: My
     * tasks and the task page do not ask GitHub.
     */
    public FeedbackStanding standing(TaskView task) {
        if (task.pullRequestUrl() == null || task.attempts().isEmpty()) {
            return FeedbackStanding.NONE;
        }
        var executionId = task.attempts().getLast().executionId();
        return executionId == null ? FeedbackStanding.NONE
                : executions.findById(executionId).map(this::standing).orElse(FeedbackStanding.NONE);
    }

    private FeedbackStanding standing(PipelineExecution execution) {
        var artifacts = artifactStore.allForExecution(execution.getId());
        if (!hasFeedback(execution, artifacts)) {
            return FeedbackStanding.NONE;
        }
        var view = actions.view(execution.getId());
        var state = view.state();
        return switch (state.phase()) {
            case SKIPPED -> FeedbackStanding.NONE;
            case STOPPED -> FeedbackStanding.CLOSED;
            case FINISHED -> "MERGED".equals(state.completionReason()) ? FeedbackStanding.MERGED
                    : FeedbackStanding.NONE;
            default -> openStanding(execution, view, artifacts);
        };
    }

    private static FeedbackStanding openStanding(PipelineExecution execution, FeedbackActions.View view,
                                                 List<Artifact> artifacts) {
        var comments = FeedbackRows.newComments(view.snapshot(), History.from(artifacts).processed());
        if (feedback(view, comments, List.of()).yourTurn()) {
            return FeedbackStanding.WAITS_FOR_YOU;
        }
        var works = execution.getStatus() == ExecutionStatus.RUNNING && !view.state().waiting();
        return works ? FeedbackStanding.WORKING : FeedbackStanding.NONE;
    }

    private static boolean hasFeedback(PipelineExecution execution, List<Artifact> artifacts) {
        return VERSION.equals(execution.getFlowVersion())
                && artifacts.stream().anyMatch(artifact -> artifact.getName().equals(STATE));
    }

    /**
     * Puts the PR feedback part of the run page into the model; empty when the run has no PR feedback, so the
     * page shows no such part.
     */
    public Optional<Summary> populate(PipelineExecution execution, List<Artifact> artifacts, List<AuditEvent> events,
                                      AttemptUsageSummary.Projection projection, Model model) {
        if (!hasFeedback(execution, artifacts)) {
            return Optional.empty();
        }
        var view = actions.view(execution.getId());
        var phase = view.state().phase();
        if (phase == FeedbackState.Phase.SKIPPED) {
            return Optional.empty();
        }
        boolean live = execution.getStatus() == ExecutionStatus.RUNNING;
        var history = History.from(artifacts);
        var rounds = FeedbackRows.rounds(history.rounds(),
                new FeedbackRows.RoundFacts(projection, live, FeedbackRows.commentUrls(events)));
        var feedback = feedback(view, FeedbackRows.newComments(view.snapshot(), history.processed()), rounds);
        model.addAttribute("feedback", feedback);
        var waiting = phase == FeedbackState.Phase.READY || phase == FeedbackState.Phase.INIT;
        return Optional.of(new Summary(feedback.phase(), waiting, !rounds.isEmpty(), feedback.yourTurn()));
    }

    /** The rounds the agent worked on the run, with their titles on the run page; empty without PR feedback. */
    static List<FeedbackRows.RoundStart> roundStarts(PipelineExecution execution, List<Artifact> artifacts) {
        if (!VERSION.equals(execution.getFlowVersion())) {
            return List.of();
        }
        return FeedbackRows.starts(History.from(artifacts).rounds());
    }

    /**
     * What the status card says about PR feedback: the phase in words, whether it waits for new comments or is
     * still getting ready to (the card then names the pull request), and whether rounds ran, so the amount is the
     * total; {@code yourTurn} says that a question or new PR comments wait for the developer.
     */
    public record Summary(String headline, boolean waiting, boolean rounds, boolean yourTurn) {
    }

    /**
     * The PR feedback part of the run page: the phase in words and its reason, which forms it offers, the new PR
     * comments and the rounds. {@code reason} is null when there is none.
     */
    public record Feedback(int version, UUID reviewId, String phase, String reason, boolean refresh, boolean fix,
                           boolean answer, String snapshotId, List<ActionButton> actions,
                           List<FeedbackRows.Comment> comments, List<FeedbackRows.Round> rounds) {

        /** The agent's question or new PR comments wait for the developer. */
        public boolean yourTurn() {
            return answer || fixPending();
        }

        /** New PR comments wait for the developer to choose the ones to fix. */
        public boolean fixPending() {
            return fix && !comments.isEmpty();
        }

        /** Fix is the primary button, unless the agent's question waits for an answer too. */
        public String fixButtonClass() {
            return answer ? "btn btn-secondary btn-sm" : "btn btn-primary btn-sm";
        }

        /** Says how to get new comments when the page offers to check for them. */
        public String emptyNote() {
            return refresh ? "No new comments. Add review comments on GitHub, then press Check PR comments."
                    : "No new comments.";
        }
    }

    /** A button that sends one feedback action; {@code note} is null when the button needs none. */
    public record ActionButton(String value, String label, String note) {
    }

    private static Feedback feedback(FeedbackActions.View view, List<FeedbackRows.Comment> comments,
                                     List<FeedbackRows.Round> rounds) {
        var state = view.state();
        var finished = state.phase() == FeedbackState.Phase.FINISHED;
        var phase = finished && !"MERGED".equals(state.completionReason()) ? "Task finished" : label(state.phase());
        var reason = finished ? state.taskSummary() : state.reason();
        var refresh = view.reviewId() != null && state.pr() != null && !view.actions().isEmpty()
                && !view.actions().contains(FeedbackActions.Action.RETRY_CLEANUP);
        return new Feedback(view.version(), view.reviewId(), phase, reason == null || reason.isBlank() ? null : reason,
                refresh, view.actions().contains(FeedbackActions.Action.FIX),
                view.actions().contains(FeedbackActions.Action.ANSWER),
                view.snapshot() == null ? "" : view.snapshot().id(), buttons(view.actions()), comments, rounds);
    }

    /** Fix and Answer have forms of their own, so they get no button here. */
    private static List<ActionButton> buttons(List<FeedbackActions.Action> actions) {
        return actions.stream()
                .filter(action -> action != FeedbackActions.Action.FIX && action != FeedbackActions.Action.ANSWER)
                .map(action -> new ActionButton(action.name(), actionLabel(action),
                        action == FeedbackActions.Action.MANUAL_CONTINUE ? MANUAL_NOTE : null))
                .toList();
    }

    private record History(Set<String> processed, TreeMap<Integer, FeedbackState> rounds) {
        static History from(List<Artifact> artifacts) {
            var processed = new HashSet<String>();
            var rounds = new TreeMap<Integer, FeedbackState>();
            var workedRounds = new HashSet<Integer>();
            var states = artifacts.stream()
                    .filter(artifact -> artifact.getName().equals(STATE))
                    .sorted(Comparator.comparingInt(Artifact::getVersion))
                    .map(artifact -> JSON.readValue(artifact.getContent(), FeedbackState.class))
                    .toList();
            for (var state : states) {
                if (state.round() > 0) {
                    if (workStarted(state)) workedRounds.add(state.round());
                    switch (state.phase()) {
                        case FINISH, FINISHED, STOPPED -> {}
                        default -> rounds.put(state.round(), state);
                    }
                }
                if (feedbackApplied(state)) {
                    state.selected().forEach(comment -> processed.add(comment.id()));
                }
            }
            rounds.keySet().retainAll(workedRounds);
            return new History(processed, rounds);
        }

        private static boolean workStarted(FeedbackState state) {
            return switch (state.phase()) {
                case CODE, VERIFY, PREPARE_PUBLISH, PUBLISH, QUESTION -> true;
                default -> Set.of("CODING", "VERIFICATION", "PUBLICATION")
                        .contains(Objects.toString(state.failureKind(), ""));
            };
        }

        private static boolean feedbackApplied(FeedbackState state) {
            return !state.commitSha().isBlank()
                    || state.phase() == FeedbackState.Phase.READY
                    && state.verification() != null
                    && "PASS".equals(state.verification().result());
        }
    }

    static String label(FeedbackState.Phase p) {
        return switch (p) {
            case READY -> "Waiting for your review";
            case PREPARE -> "Preparing selected feedback";
            case CODE -> "Implementing corrections";
            case VERIFY -> "Running independent checks";
            case PREPARE_PUBLISH -> "Preparing commit";
            case PUBLISH -> "Updating PR";
            case QUESTION -> "Your answer is needed";
            case FAILURE -> "Action needed";
            case REFRESH_REQUIRED -> "Refresh PR and select comments again";
            case INTERRUPTED -> "Coding attempt interrupted";
            case CLEANUP_BLOCKED -> "Resource cleanup required";
            case FINISHED -> "PR merged";
            case STOPPED -> "PR closed without merge";
            default -> p.name();
        };
    }

    static String actionLabel(FeedbackActions.Action a) {
        return switch (a) {
            case FIX -> "Fix selected comments";
            case ANSWER -> "Answer and continue";
            case RETRY_CODE -> "Try fixing with the agent";
            case RETRY_CHECKS -> "Run checks again";
            case RETRY_OPERATION -> "Retry interrupted operation";
            case MANUAL_CONTINUE -> "Verify my manual changes";
            case RETRY_CLEANUP -> "Retry cleanup";
            case FINISH -> "Finish task";
        };
    }
}
