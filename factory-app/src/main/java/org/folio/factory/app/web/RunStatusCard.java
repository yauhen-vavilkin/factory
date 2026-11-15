package org.folio.factory.app.web;

import org.folio.factory.app.tasks.presentation.AttemptClock;
import org.folio.factory.app.tasks.presentation.AttemptFailure;
import org.folio.factory.app.tasks.presentation.AttemptStatus;
import org.folio.factory.app.tasks.presentation.FeedbackStanding;
import org.folio.factory.app.tasks.presentation.RunContext;
import org.folio.factory.app.tasks.presentation.Turn;
import org.folio.factory.app.web.DeveloperRunView.Status;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.PipelineExecution;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.folio.factory.app.web.UiFormat.blankToNull;

/**
 * The words of the run page's status card, its header and the line under it. A run that a task attempt started
 * takes its chip and failure from the attempt, as the task page does; any other run takes them from its product
 * status, in words.
 */
final class RunStatusCard {

    private static final String NO_FIGURE = "—";
    private static final Pattern PULL_NUMBER = Pattern.compile("/pull/(\\d+)");
    private static final Map<String, String> WORKING = Map.of(
            "Prepare task", "Preparing the task", "Implement changes", "Implementing changes",
            "Verify changes", "Verifying changes", "Create pull request", "Creating the pull request");

    private RunStatusCard() {
    }

    /** What the card is built from; {@code review} is the pending review of a run that waits for one, or null. */
    record Input(RunFacts facts, Optional<RunContext> context, PipelineExecution execution,
                 Optional<FeedbackUi.Summary> feedback, RunUsage usage, AttemptClock clock, HitlReview review) {

        boolean live() {
            var status = execution.getStatus();
            return status == ExecutionStatus.RUNNING || status == ExecutionStatus.PENDING;
        }
    }

    /** A status chip: its words, its classes and, for a pull request, its link. */
    record Chip(String label, String cssClass, String url) {
    }

    private record Action(String label, String url) {
        static final Action NONE = new Action(null, null);
    }

    private record Headline(String text, String reasonLabel, String reason, String question, String note) {
        static Headline of(String text) {
            return new Headline(text, null, null, null, null);
        }
    }

    /**
     * The run of a task attempt has the meta line of its task page, {@code taskRepository}, which the task knows
     * before the run checks anything out; any other run names the repository it checked out.
     */
    static DeveloperRunView.Header header(RunFacts facts, Optional<RunContext> context, PipelineExecution execution,
                                          String taskRepository) {
        var task = facts.task();
        var repository = blankToNull(task.repository().substring(task.repository().indexOf('/') + 1));
        var settings = context.map(RunContext::settings).orElse(null);
        settings = settings != null ? settings : blankToNull(facts.settings());
        if (context.isPresent()) {
            var run = context.get();
            return new DeveloperRunView.Header(run.pageTitle(), run.taskId(), run.key(), run.jiraUrl(), run.crumb(),
                    run.title(), taskRepository != null ? taskRepository : repository, settings);
        }
        var crumb = "run " + execution.getId().toString().substring(0, 4) + "…";
        var key = blankToNull(task.key());
        return new DeveloperRunView.Header(key == null ? "Run " + crumb.substring(4) : key + " · " + crumb, null,
                key, null, crumb, task.summary().isBlank() ? "Developer Flow run" : task.summary(), repository,
                settings);
    }

    /**
     * The run of a task attempt takes its chip and headline from the attempt, as the task page does; a PR feedback
     * round that works or stopped says its phase instead. A run that works shows its amount from the first render.
     */
    static Status status(Input in) {
        var chip = in.context().map(run -> attemptStatus(in, run))
                .map(status -> new Chip(status.chipLabel(), status.chipClass(), status.chipUrl()))
                .orElseGet(() -> chip(in.facts().productStatus(), in.facts().pullRequest()));
        var headline = headline(in);
        var usage = in.usage();
        var amount = amount(in);
        var rounds = in.feedback().map(FeedbackUi.Summary::rounds).orElse(false);
        var amountNote = amount == null ? unknownCost(in) : in.live() ? UiFormat.SO_FAR : rounds ? "total" : null;
        var action = action(in);
        return new Status(chip.label(), chip.cssClass(), chip.url(), headline.text(), headline.reasonLabel(),
                headline.reason(), AttemptFailure.limitsUrl(headline.reason()), headline.question(), headline.note(),
                in.context().map(RunContext::draftReasons).orElse(null), amount, amountNote,
                usage.partial() ? RunUsage.INCOMPLETE : null, in.clock(), action.label(), action.url());
    }

    /** The cost figure; "$0.00" while a run that works has reported nothing, null when there is no figure. */
    private static String amount(Input in) {
        var reported = in.usage().amount();
        if (reported != null) {
            return NO_FIGURE.equals(reported) ? null : reported;
        }
        return in.live() && !in.usage().column().reported() ? UiFormat.ZERO_COST : null;
    }

    /** Says in words that tokens were reported and have no price, in place of a figure. */
    private static String unknownCost(Input in) {
        return NO_FIGURE.equals(in.usage().amount()) || in.usage().column().reported() ? UiFormat.COST_UNKNOWN : null;
    }

    /**
     * The developer's turn when the page waits for an answer, a review, a choice of PR comments or the start of
     * the next attempt; otherwise the page says that Factory works or that nothing is needed.
     */
    static Turn turn(Input in, Status status) {
        var waits = status.actionUrl() != null
                || in.feedback().map(FeedbackUi.Summary::yourTurn).orElse(false)
                || in.context().map(RunContext::retryUrl).isPresent()
                || in.context().map(RunContext::status).filter(AttemptStatus::needsInspection).isPresent();
        if (waits) {
            return Turn.YOURS;
        }
        return in.live() ? Turn.WORKING : Turn.NONE;
    }

    /** The muted line under the card: where an older attempt stands, or the button to start the next attempt. */
    static DeveloperRunView.Standing standing(Optional<RunContext> context) {
        if (context.isEmpty()) {
            return null;
        }
        var run = context.get();
        var older = run.standing();
        if (older == null && run.retryLabel() == null) {
            return null;
        }
        return older == null ? new DeveloperRunView.Standing(null, null, null, null, run.retryLabel(), run.retryUrl())
                : new DeveloperRunView.Standing(older.lead(), older.latestText(), older.latestUrl(), older.tail(),
                        run.retryLabel(), run.retryUrl());
    }

    private static Headline headline(Input in) {
        var feedback = in.feedback();
        var named = in.context().isPresent() && ended(in) != FeedbackStanding.NONE;
        if (feedback.isPresent() && !feedback.get().waiting() && !named) {
            return Headline.of(feedback.get().headline());
        }
        return in.context().map(run -> attemptHeadline(in, run)).orElseGet(() -> runHeadline(in));
    }

    /** The attempt's status; its pull request reads as merged or closed once PR feedback has seen that. */
    private static AttemptStatus attemptStatus(Input in, RunContext run) {
        return run.status().ended(ended(in));
    }

    private static FeedbackStanding ended(Input in) {
        return in.feedback().map(FeedbackUi.Summary::ended).orElse(FeedbackStanding.NONE);
    }

    /** The headline of the attempt, with the reason of a failure, the question, or the problem a retry follows. */
    private static Headline attemptHeadline(Input in, RunContext run) {
        var text = attemptStatus(in, run).headline();
        if (run.failure() != null) {
            return new Headline(text, "Reason", run.failure().reason(), null, run.failureNote());
        }
        if (in.facts().asked()) {
            return new Headline(text, null, null, in.facts().reason(), null);
        }
        return in.live() ? retrying(text, in.facts()) : Headline.of(text);
    }

    /** A run that no task attempt started says what its artifacts say. */
    private static Headline runHeadline(Input in) {
        var facts = in.facts();
        if (in.feedback().isPresent()) {
            return Headline.of(pullRequestHeadline(facts.pullRequest()));
        }
        if (in.live()) {
            var current = facts.steps().current();
            return current == null ? Headline.of(AttemptStatus.STARTING)
                    : retrying(WORKING.getOrDefault(current, current), facts);
        }
        if (facts.asked()) {
            return new Headline("The agent stopped with a question", null, null, facts.reason(), null);
        }
        return finished(in);
    }

    private static Headline retrying(String text, RunFacts facts) {
        return switch (facts.reasonKind()) {
            case RETRY_PENDING -> new Headline(text, "Retry pending", AttemptFailure.reason(facts.reason()),
                    null, null);
            case RETRYING -> new Headline(text, "Retrying after", AttemptFailure.reason(facts.reason()),
                    null, null);
            default -> Headline.of(text);
        };
    }

    /** A run that does not work now and whose attempt has no failure of its own. */
    private static Headline finished(Input in) {
        var facts = in.facts();
        if (!facts.pullRequest().url().isBlank()) {
            return Headline.of(pullRequestHeadline(facts.pullRequest()));
        }
        if (in.execution().getStatus() == ExecutionStatus.AWAITING_HITL) {
            return Headline.of("Waiting for your review");
        }
        var chip = chip(facts.productStatus(), facts.pullRequest());
        if ("Failed".equals(chip.label())) {
            return new Headline("This run failed", "Reason", AttemptFailure.reason(facts.reason()), null, null);
        }
        return Headline.of(chip.label());
    }

    /** "Pull request #6 is open as a draft", in the words of the task page. */
    private static String pullRequestHeadline(RunFacts.PullRequest pullRequest) {
        var number = PULL_NUMBER.matcher(pullRequest.url());
        var label = number.find() ? "Pull request #" + number.group(1) : "The pull request";
        return label + (pullRequest.draft() ? " is open as a draft" : " is ready for review");
    }

    /**
     * The answer form on the task page while the task waits for it, the pull request of an attempt that needs
     * inspection (the draft reasons of the card are about it), or the review this run waits for. A failed attempt
     * whose engine gave up offers its next attempt instead of the engine's review.
     */
    private static Action action(Input in) {
        var answer = in.context().map(RunContext::answerUrl);
        if (answer.isPresent()) {
            return new Action("Answer on the task page", answer.get());
        }
        var pullRequest = in.facts().pullRequest().url();
        if (in.context().map(RunContext::status).filter(AttemptStatus::needsInspection).isPresent()
                && !pullRequest.isBlank()) {
            var number = PULL_NUMBER.matcher(pullRequest);
            return new Action("Open pull request" + (number.find() ? " #" + number.group(1) : ""), pullRequest);
        }
        if (in.review() != null && in.feedback().isEmpty() && in.context().map(RunContext::retryUrl).isEmpty()) {
            return new Action("Open review", "/reviews/" + in.review().getId());
        }
        return Action.NONE;
    }

    /** The product status in words, for a run that no task attempt started and for the run list. */
    static Chip chip(String productStatus, RunFacts.PullRequest pullRequest) {
        return switch (productStatus) {
            case "RUNNING", "PENDING" -> new Chip("In progress", "chip chip-info", null);
            case "AWAITING_HITL" -> new Chip("Waiting for review", "chip chip-warning", null);
            case "NEEDS_DECISION" -> new Chip("Asked a question", "chip chip-warning", null);
            case "AWAITING_PR", "DELIVERED", "COMPLETED" -> pullRequest.url().isBlank()
                    ? new Chip("Finished", "chip chip-success", null) : pullRequestChip(pullRequest);
            case "VERIFIED" -> new Chip("Verified", "chip chip-success", null);
            case "CANCELLED", "REJECTED" -> new Chip("Stopped", "chip chip-neutral", null);
            default -> new Chip("Failed", "chip chip-danger", null);
        };
    }

    private static Chip pullRequestChip(RunFacts.PullRequest pullRequest) {
        var number = PULL_NUMBER.matcher(pullRequest.url());
        var label = (pullRequest.draft() ? "Draft PR" : "PR") + (number.find() ? " #" + number.group(1) : "");
        return new Chip(label, pullRequest.draft() ? "chip chip-neutral" : "chip chip-success", pullRequest.url());
    }

}
