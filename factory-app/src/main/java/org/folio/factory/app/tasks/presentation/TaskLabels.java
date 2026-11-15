package org.folio.factory.app.tasks.presentation;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.execution.StopReasons;
import org.folio.factory.app.tasks.execution.TaskOutcomeService;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.web.UiFormat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The words the task pages show for states, stages, failures, durations and cost. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class TaskLabels {

    static final String NEEDS_ANSWER = "Needs your answer";
    /** The stage of an attempt in flight whose run ended before the dispatcher closed the attempt. */
    static final String RUN_ENDED = "run-ended";
    private static final String COST_MAY_BE_HIGHER = "cost may be higher";
    private static final String COST_MAY_BE_HIGHER_TITLE =
            "Some usage was not reported by the runtime or has no price, so the real cost may be higher.";
    private static final String COST_UNKNOWN_TITLE =
            "The runtime did not report usage, or the usage has no price, so the cost is not known.";
    private static final Pattern PULL_NUMBER = Pattern.compile("/pull/(\\d+)");

    /** Work in flight whose latest attempt waits for the dispatcher. */
    static boolean queued(TaskView view) {
        return view.state() == TaskView.TaskState.WORKING && !view.attempts().isEmpty()
                && view.attempts().getLast().state() == TaskAttempt.State.RESERVED;
    }

    /** Work in flight that the dispatcher has started. */
    static boolean working(TaskView view) {
        return view.state() == TaskView.TaskState.WORKING && !queued(view);
    }

    static String triggeredDetail(TaskView view) {
        return switch (view.state()) {
            // A running task shows its progress elsewhere; only a queued one reaches here.
            case WORKING -> linePlace(view);
            // The chip says it: a pull request, or Needs inspection.
            case PR_READY, UNCERTAIN, PROCESSING_PAUSED -> null;
            case STOPPED -> view.needsAnswer() ? null : AttemptFailure.reason(view.reason());
            default -> AttemptFailure.reason(view.reason());
        };
    }

    static String attemptResult(TaskView.AttemptHistoryEntry attempt, AttemptStatus status) {
        if (attempt.pullRequestUrl() != null) {
            return pullRequestLabel(attempt.pullRequestUrl())
                    + (attempt.draftReasons() == null ? " opened" : " opened as a draft");
        }
        // An attempt that needs inspection has no reason of its own: its headline says what is not known.
        return switch (attempt.state()) {
            case STOPPED, PR_READY -> AttemptFailure.reason(attempt.reason());
            default -> status.headline();
        };
    }

    /** The headline of a task that has no attempt yet. */
    static String notStartedSentence(TaskView view) {
        return view.state() == TaskView.TaskState.UNSUPPORTED ? AttemptFailure.reason(view.reason())
                : "Not started yet. Select it in My tasks and press Run.";
    }

    /** What an attempt in flight does now, in the words of the run page's step cards. */
    static String workingHeadline(String stage) {
        if (RUN_ENDED.equals(stage)) {
            return "Finishing the attempt";
        }
        var label = stageLabel(stage);
        if (label == null) {
            return AttemptStatus.STARTING;
        }
        return switch (label) {
            case "Preparation" -> "Preparing the task";
            case "Implementation" -> "Implementing changes";
            case "Verification" -> "Verifying changes";
            default -> "Creating the pull request";
        };
    }

    /** What a failed attempt left behind: no pull request, or perhaps one whose response was lost. */
    static String pullRequestNote(String reason) {
        return reason != null && reason.contains(TaskOutcomeService.PULL_REQUEST_MAY_EXIST)
                ? "Check the repository before you start a new attempt." : "No pull request was created.";
    }

    /** "next in line" or "3rd in line" for a queued task. */
    private static String linePlace(TaskView view) {
        return view.queuePosition() == null || view.queuePosition() == 1
                ? "next in line" : ordinal(view.queuePosition()) + " in line";
    }

    /**
     * Maps the two raw stage vocabularies — dev-factory step ids and the terminal
     * result-state prefixes the outcome service composes — to the four stage
     * labels. Stop states and unknown values show no stage.
     */
    static String stageLabel(String stage) {
        if (stage == null) {
            return null;
        }
        return switch (stage) {
            case "read-task", "select-repository", "prepare-task" -> "Preparation";
            case "implement", "clarify-implementation", "continue-implementation", "coding",
                    "DEVELOPMENT_FAILED" -> "Implementation";
            case "verify", "verification", "VERIFIED", StopReasons.CHECKS_FAILED, StopReasons.CHECKS_NOT_RUN ->
                    "Verification";
            case "publish", "initialize-pr-feedback", "pr-follow-up", "DELIVERY_BLOCKED" -> "Publication";
            default -> null;
        };
    }

    static String pullRequestLabel(String url) {
        if (url == null) {
            return "Pull request";
        }
        Matcher number = PULL_NUMBER.matcher(url);
        return number.find() ? "Pull request #" + number.group(1) : "Pull request";
    }

    /** "Draft PR #6" or "PR #6": the pull request chip of a list row. */
    static String shortPullRequestLabel(String url, boolean draft) {
        var number = url == null ? null : PULL_NUMBER.matcher(url);
        var label = number != null && number.find() ? "PR #" + number.group(1) : "PR";
        return draft ? "Draft " + label : label;
    }

    static String costLabel(TaskUsage usage) {
        return UiFormat.money(usage.estimatesByCurrency());
    }

    /**
     * The cost of finished work: "$0.00" when its attempts started but none reached the agent and all their usage
     * was counted, so none spent.
     */
    static String finishedCost(TaskView view) {
        var started = view.attempts().stream().anyMatch(attempt -> attempt.startedAt() != null);
        var paid = view.attempts().stream().anyMatch(TaskView.AttemptHistoryEntry::paidStart);
        return costOrNothingSpent(view.usage(), started && !paid);
    }

    /** The same for one attempt. */
    static String finishedCost(TaskView.AttemptHistoryEntry attempt) {
        return costOrNothingSpent(attempt.usage(), attempt.startedAt() != null && !attempt.paidStart());
    }

    private static String costOrNothingSpent(TaskUsage usage, boolean noPaidStart) {
        var cost = costLabel(usage);
        var nothingSpent = noPaidStart && usage.complete() && usage.tokensByCategory().isEmpty();
        return cost == null && nothingSpent ? UiFormat.ZERO_COST : cost;
    }

    /** The cost of work that runs now: "$0.00" until it reports tokens, null when its tokens have no price. */
    static String costSoFar(TaskUsage usage) {
        var cost = costLabel(usage);
        return cost == null && usage.tokensByCategory().isEmpty() ? UiFormat.ZERO_COST : cost;
    }

    /** Why a finished cost is missing or may be too low; null when all usage was reported and priced. */
    static String incompleteCostNote(TaskUsage usage) {
        return usage.complete() ? null : costLabel(usage) == null ? UiFormat.COST_UNKNOWN : COST_MAY_BE_HIGHER;
    }

    static String costNoteTitle(String costNote) {
        return COST_MAY_BE_HIGHER.equals(costNote) ? COST_MAY_BE_HIGHER_TITLE
                : UiFormat.COST_UNKNOWN.equals(costNote) ? COST_UNKNOWN_TITLE : null;
    }

    static String usageNote(TaskUsage usage) {
        var notes = new ArrayList<String>();
        if (!usage.complete()) {
            notes.add("Incomplete: some usage has no reported tokens or rate yet. Cost per attempt is in Attempts.");
        }
        if (usage.provisional()) {
            notes.add("Includes live usage that can still change.");
        }
        return notes.isEmpty() ? null : String.join(" ", notes);
    }

    static String minutes(long seconds) {
        long minutes = Math.max(0, seconds) / 60;
        if (minutes < 1) {
            return "<1 min";
        }
        if (minutes < 60) {
            return minutes + " min";
        }
        return minutes / 60 + " h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " min");
    }

    static String ago(Instant instant) {
        if (instant == null) {
            return null;
        }
        long seconds = Math.max(0, Duration.between(instant, Instant.now()).getSeconds());
        if (seconds < 60) {
            return "just now";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " min ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " h ago";
        }
        long days = hours / 24;
        return days + (days == 1 ? " day ago" : " days ago");
    }

    static String ordinal(int position) {
        int lastTwo = position % 100;
        String suffix = lastTwo >= 11 && lastTwo <= 13 ? "th" : switch (position % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return position + suffix;
    }
}
