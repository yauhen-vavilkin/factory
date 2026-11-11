package org.folio.factory.app.web;

import org.folio.factory.app.tasks.presentation.AttemptFailure;
import org.folio.factory.app.tasks.presentation.RunContext;
import org.folio.factory.app.web.DeveloperRunView.Status;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.PipelineExecution;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The words of the run page's status card, its header and the line under it. A run that a task attempt started
 * takes its chip and failure from the attempt, as the task page does; any other run takes them from its product
 * status, in words.
 */
final class RunStatusCard {

    private static final Pattern PULL_NUMBER = Pattern.compile("/pull/(\\d+)");
    private static final Map<String, String> WORKING = Map.of(
            "Prepare task", "Preparing the task", "Implement changes", "Implementing changes",
            "Verify changes", "Verifying changes", "Create pull request", "Creating the pull request");

    private RunStatusCard() {
    }

    /** What the card is built from; {@code review} is the pending review of a run that waits for one, or null. */
    record Input(RunFacts facts, Optional<RunContext> context, PipelineExecution execution,
                 Optional<FeedbackUi.Summary> feedback, RunUsage usage, Clock clock, HitlReview review) {

        boolean live() {
            var status = execution.getStatus();
            return status == ExecutionStatus.RUNNING || status == ExecutionStatus.PENDING;
        }
    }

    /** The active time of the run; {@code limitMinutes} is the attempt deadline, null for a run of no task. */
    record Clock(long activeSeconds, Instant now, Integer limitMinutes) {
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

    static DeveloperRunView.Header header(RunFacts facts, Optional<RunContext> context, PipelineExecution execution) {
        var task = facts.task();
        var repository = blankToNull(task.repository().substring(task.repository().indexOf('/') + 1));
        var settings = context.map(RunContext::settings).orElse(null);
        settings = settings != null ? settings : blankToNull(facts.settings());
        if (context.isPresent()) {
            var run = context.get();
            return new DeveloperRunView.Header(run.pageTitle(), run.taskId(), run.key(), run.jiraUrl(), run.crumb(),
                    run.title(), repository, settings);
        }
        var crumb = "run " + execution.getId().toString().substring(0, 4) + "…";
        var key = blankToNull(task.key());
        return new DeveloperRunView.Header(key == null ? "Run " + crumb.substring(4) : key + " · " + crumb, null,
                key, null, crumb, task.summary().isBlank() ? "Developer Flow run" : task.summary(), repository,
                settings);
    }

    static Status status(Input in) {
        var chip = in.context().map(run -> new Chip(run.chipLabel(), run.chipClass(), run.chipUrl()))
                .orElseGet(() -> chip(in.facts().productStatus(), in.facts().pullRequest()));
        var headline = headline(in);
        var usage = in.usage();
        var rounds = in.feedback().map(FeedbackUi.Summary::rounds).orElse(false);
        var amountNote = usage.amount() == null ? null : in.live() ? "so far" : rounds ? "total" : null;
        var clock = in.clock();
        var ticking = in.execution().getStatus() == ExecutionStatus.RUNNING;
        var action = action(in);
        return new Status(chip.label(), chip.cssClass(), chip.url(), headline.text(), headline.reasonLabel(),
                headline.reason(), headline.question(), headline.note(), usage.amount(), amountNote,
                usage.partial() ? RunUsage.INCOMPLETE : null, UiFormat.duration(clock.activeSeconds()),
                ticking ? clock.now().minusSeconds(clock.activeSeconds()) : null,
                ticking && clock.limitMinutes() != null ? "of " + clock.limitMinutes() : null,
                action.label(), action.url());
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
        var facts = in.facts();
        if (in.feedback().isPresent()) {
            return in.feedback().get().waiting() ? Headline.of(pullRequestHeadline(facts.pullRequest()))
                    : Headline.of(in.feedback().get().headline());
        }
        if (in.live() && facts.steps().current() != null) {
            return working(facts);
        }
        if (facts.asked()) {
            return new Headline("The agent stopped with a question", null, null, facts.reason(), null);
        }
        var failure = in.context().map(RunContext::failure);
        if (failure.isPresent()) {
            return new Headline(failure.get().headline(), "Reason", failure.get().reason(), null,
                    in.context().get().failureNote());
        }
        return finished(in);
    }

    private static Headline working(RunFacts facts) {
        var text = WORKING.getOrDefault(facts.steps().current(), facts.steps().current());
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

    /** The answer form on the task page while the task waits for it, or the review this run waits for. */
    private static Action action(Input in) {
        var answer = in.context().map(RunContext::answerUrl);
        if (answer.isPresent()) {
            return new Action("Answer on the task page", answer.get());
        }
        if (in.review() != null && in.feedback().isEmpty()) {
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
