package org.folio.factory.app.web;

import static org.folio.factory.devfactory.feedback.FeedbackArtifacts.*;

import lombok.RequiredArgsConstructor;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.devfactory.feedback.FeedbackActions;
import org.folio.factory.devfactory.feedback.FeedbackState;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

@Component
@RequiredArgsConstructor
public class FeedbackUi {
    private static final String MANUAL_NOTE = "Runs the tests on commits you pushed to the PR branch yourself.";

    private final FeedbackActions actions;

    /**
     * Puts the PR feedback part of the run page into the model; empty when the run has no PR feedback, so the
     * page shows no such part.
     */
    public Optional<Summary> populate(PipelineExecution execution, List<Artifact> artifacts, List<AuditEvent> events,
                                      AttemptUsageSummary.Projection projection, Model model) {
        if (!VERSION.equals(execution.getFlowVersion())
                || artifacts.stream().noneMatch(artifact -> artifact.getName().equals(STATE))) {
            return Optional.empty();
        }
        var view = actions.view(execution.getId());
        var phase = view.state().phase();
        if (phase == FeedbackState.Phase.SKIPPED) {
            return Optional.empty();
        }
        boolean live = execution.getStatus() == ExecutionStatus.RUNNING;
        var history = History.from(artifacts);
        var data = feedbackControls(view);
        var rounds = FeedbackRows.rounds(history.rounds(),
                new FeedbackRows.RoundFacts(projection, live, FeedbackRows.commentUrls(events)));
        data.put("comments", FeedbackRows.newComments(view.snapshot(), history.processed()));
        data.put("rounds", rounds);
        model.addAttribute("feedback", data);
        return Optional.of(new Summary(String.valueOf(data.get("phase")), phase == FeedbackState.Phase.READY,
                !rounds.isEmpty()));
    }

    /**
     * What the status card says about PR feedback: the phase in words, whether it waits for new comments (the card
     * then names the pull request), and whether rounds ran, so the amount is the total.
     */
    public record Summary(String headline, boolean waiting, boolean rounds) {
    }

    private static Map<String, Object> feedbackControls(FeedbackActions.View view) {
        var state = view.state();
        var data = new LinkedHashMap<String, Object>();
        data.put("version", view.version());
        data.put("reviewId", view.reviewId());
        data.put("phase", state.phase() == FeedbackState.Phase.FINISHED
                && !"MERGED".equals(state.completionReason()) ? "Task finished" : label(state.phase()));
        data.put("reason", state.phase() == FeedbackState.Phase.FINISHED
                ? state.taskSummary() : state.reason());
        data.put("refresh", view.reviewId() != null && state.pr() != null
                && !view.actions().isEmpty()
                && !view.actions().contains(FeedbackActions.Action.RETRY_CLEANUP));
        data.put("fix", view.actions().contains(FeedbackActions.Action.FIX));
        data.put("answer", view.actions().contains(FeedbackActions.Action.ANSWER));
        data.put("snapshotId", view.snapshot() == null ? "" : view.snapshot().id());
        data.put("actions", view.actions().stream()
                .filter(action -> action != FeedbackActions.Action.FIX
                        && action != FeedbackActions.Action.ANSWER)
                .map(action -> Map.of("value", action.name(), "label", actionLabel(action),
                        "note", action == FeedbackActions.Action.MANUAL_CONTINUE ? MANUAL_NOTE : ""))
                .toList());
        return data;
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
