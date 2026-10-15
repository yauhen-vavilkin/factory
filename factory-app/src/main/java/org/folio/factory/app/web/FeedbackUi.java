package org.folio.factory.app.web;

import static org.folio.factory.devfactory.feedback.FeedbackArtifacts.*;

import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.devfactory.feedback.FeedbackActions;
import org.folio.factory.devfactory.feedback.FeedbackState;
import org.folio.factory.devfactory.feedback.PrFeedbackGateway;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

@Component
public class FeedbackUi {
    private final FeedbackActions actions;

    public FeedbackUi(FeedbackActions actions) {
        this.actions = actions;
    }

    public void populate(
            PipelineExecution execution,
            List<Artifact> artifacts,
            List<AuditEvent> events,
            Model model) {
        var projection = new AttemptUsageSummary.Projection(events);
        if (!VERSION.equals(execution.getFlowVersion())) return;

        boolean live = execution.getStatus() == ExecutionStatus.RUNNING;
        populateUsage(projection, live, model);
        if (artifacts.stream().noneMatch(artifact -> artifact.getName().equals(STATE))) return;
        var view = actions.view(execution.getId());
        if (view.state().phase() == FeedbackState.Phase.SKIPPED) return;

        var history = History.from(artifacts);
        var data = feedbackControls(view);
        data.put("comments", comments(view.snapshot(), history.processed()));
        data.put("rounds", roundCards(history.rounds(), projection, live));
        model.addAttribute("feedback", data);
    }

    private static void populateUsage(
            AttemptUsageSummary.Projection projection, boolean live, Model model) {
        var initial = projection.summary(-1, live);
        model.addAttribute("attemptUsage", projection.summary(null, live));
        model.addAttribute("initialUsage", initial);
        var totals = new LinkedHashMap<String, Object>();
        totals.put("present", initial.get("present"));
        totals.put("coding", true);
        totals.put("total", initial.get("tokensDisplay"));
        ((Map<?, ?>) initial.get("breakdown")).forEach(
                (category, count) -> totals.put(
                        category.toString(), UiFormat.count(((Number) count).longValue())));
        model.addAttribute("tokenTotals", totals);
        model.addAttribute("estimatedCost", null);
        model.addAttribute("usageIncomplete", initial.get("partial"));
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
                .map(action -> Map.of("value", action.name(), "label", actionLabel(action)))
                .toList());
        return data;
    }

    private static List<Map<String, Object>> comments(
            PrFeedbackGateway.Snapshot snapshot, Set<String> processed) {
        if (snapshot == null) return List.of();
        var comments = new ArrayList<Map<String, Object>>();
        for (var comment : snapshot.comments()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", comment.id());
            row.put("author", comment.author());
            row.put("body", comment.body());
            row.put("location", comment.path().isBlank() ? "PR discussion"
                    : comment.path() + (comment.line() == null ? "" : ":" + comment.line()));
            row.put("outdated", comment.outdated());
            row.put("processed", processed.contains(comment.id()));
            comments.add(row);
        }
        return comments;
    }

    private static List<Map<String, Object>> roundCards(
            TreeMap<Integer, FeedbackState> rounds,
            AttemptUsageSummary.Projection projection,
            boolean live) {
        var cards = new ArrayList<Map<String, Object>>();
        int correction = 0;
        int manual = 0;
        for (var entry : rounds.entrySet()) {
            var state = entry.getValue();
            var card = new LinkedHashMap<String, Object>();
            card.put("round", entry.getKey());
            card.put("open", entry.getKey().equals(rounds.lastKey()));
            card.put("title", state.manual()
                    ? "Manual verification " + (++manual) : "Round " + (++correction));
            card.put("manual", state.manual());
            card.put("phase", roundPhase(state));
            card.put("summary", state.summary());
            card.put("reason", state.reason());
            card.put("commit", state.commitSha());
            card.put("selected", state.selected().stream()
                    .map(comment -> Map.of("author", comment.author(), "body", comment.body()))
                    .toList());
            card.put("usage", projection.summary(entry.getKey(), live));
            card.put("runtime", state.runtimeName());
            card.put("verification", state.verification());
            card.put("activity", projection.activity(entry.getKey()));
            cards.add(card);
        }
        return cards;
    }

    private static String roundPhase(FeedbackState state) {
        if (state.phase() != FeedbackState.Phase.READY) return label(state.phase());
        if (!state.commitSha().isBlank()) return "Changes published";
        return state.manual() ? "Manual changes verified" : "No changes needed";
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

    static String duration(long s) {
        return s / 3600 + "h " + s % 3600 / 60 + "m " + s % 60 + "s";
    }

    static String label(FeedbackState.Phase p) {
        return switch (p) {
            case READY -> "Awaiting PR feedback";
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
