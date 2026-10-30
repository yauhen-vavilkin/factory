package org.folio.factory.app.pilot.presentation;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.folio.factory.app.pilot.execution.PilotOutcomeService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** The words the task pages show for states, stages, failures, durations and cost. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class PilotTaskLabels {

    private static final String COST_MAY_BE_HIGHER = "cost may be higher";
    private static final String COST_UNKNOWN = "cost unknown";
    private static final String COST_MAY_BE_HIGHER_TITLE =
            "Some usage was not reported by the runtime or has no price, so the real cost may be higher.";
    private static final String COST_UNKNOWN_TITLE =
            "The runtime did not report usage, or the usage has no price, so the cost is not known.";
    private static final Pattern PULL_NUMBER = Pattern.compile("/pull/(\\d+)");
    private static final Pattern LEADING_STAGE =
            Pattern.compile("^(?:shared: )?([A-Za-z][A-Za-z_-]*): (.+)$", Pattern.DOTALL);

    static String stateLabel(PilotTaskView view) {
        if (view.state() == PilotTaskView.TaskState.WORKING) {
            return !view.attempts().isEmpty() && "RESERVED".equals(view.attempts().getLast().state())
                    ? "Queued" : "Working";
        }
        return switch (view.state()) {
            case AWAITING_SELECTION -> "Not started";
            case WITHDRAWN -> "Withdrawn";
            case UNSUPPORTED -> "Not eligible";
            case STOPPED -> "Could not complete";
            case UNCERTAIN, PROCESSING_PAUSED -> "Needs inspection";
            case PR_READY -> view.draftReasons() == null ? "PR ready" : "Draft PR";
            case WORKING -> "Working";
        };
    }

    static String attemptStateLabel(String state) {
        return switch (state) {
            case "RESERVED" -> "Queued";
            case "QUEUED", "RUNNING" -> "Working";
            case "PR_READY" -> "PR ready";
            case "STOPPED" -> "Could not complete";
            case "NEEDS_INSPECTION" -> "Needs inspection";
            case "WITHDRAWN" -> "Withdrawn";
            default -> state;
        };
    }

    static String stateClass(String label) {
        if (label == null) {
            return "state-pill";
        }
        return switch (label) {
            case "PR ready" -> "state-pill state-pill-success";
            case "Could not complete" -> "state-pill state-pill-danger";
            case "Needs inspection", "Draft PR" -> "state-pill state-pill-warning";
            default -> "state-pill";
        };
    }

    static String triggeredDetail(PilotTaskView view, String label) {
        return switch (label) {
            case "Working" -> joinNonNull(stageLabel(view.stage()), view.stageStartedAt() == null ? null
                    : minutes(Duration.between(view.stageStartedAt(), Instant.now()).getSeconds()));
            case "Queued" -> view.queuePosition() == null || view.queuePosition() == 1
                    ? "next in line" : ordinal(view.queuePosition()) + " in line";
            case "PR ready", "Draft PR" -> null;
            case "Needs inspection" -> "outcome not confirmed";
            default -> failureText(view.reason());
        };
    }

    static String attemptResult(PilotTaskView.AttemptHistoryEntry attempt) {
        return switch (attempt.state()) {
            case "PR_READY" -> pullRequestLabel(attempt.pullRequestUrl())
                    + (attempt.draftReasons() == null ? " opened" : " opened as a draft");
            case "RESERVED" -> "Queued";
            case "QUEUED", "RUNNING" -> "In progress";
            case "WITHDRAWN" -> "Withdrawn before start";
            default -> failureText(attempt.reason());
        };
    }

    static String resultSentence(PilotTaskView view, String label) {
        if (label == null) {
            return view.state() == PilotTaskView.TaskState.UNSUPPORTED ? sentence(view.reason())
                    : "Not started yet. Select it in My tasks and press Run.";
        }
        return switch (label) {
            case "PR ready" -> pullRequestLabel(view.pullRequestUrl()) + " is ready for review.";
            case "Draft PR" -> pullRequestLabel(view.pullRequestUrl()) + " is open as a draft.";
            case "Working" -> stageLabel(view.stage()) == null ? "In progress." : "In progress: "
                    + stageLabel(view.stage()) + ".";
            case "Queued" -> view.queuePosition() == null || view.queuePosition() == 1
                    ? "Queued: next in line." : "Queued: " + ordinal(view.queuePosition()) + " in line.";
            case "Needs inspection" -> "Factory could not confirm how the last run ended.";
            default -> view.reason() != null && view.reason().contains(PilotOutcomeService.PULL_REQUEST_MAY_EXIST)
                    ? sentence(failureText(view.reason())) + " Check the repository before you retry."
                    : sentence(failureText(view.reason())) + " No pull request was created.";
        };
    }

    static List<String> resultChips(PilotTaskView view) {
        List<String> chips = new ArrayList<>();
        String cost = costLabel(view.usage());
        if (cost != null) {
            chips.add(cost + " total" + (view.usage().complete() ? "" : " · incomplete"));
        }
        int count = view.attempts().size();
        if (count > 0) {
            chips.add(count + (count == 1 ? " attempt" : " attempts"));
        }
        long active = view.attempts().stream().filter(PilotTaskView.AttemptHistoryEntry::paidStart)
                .mapToLong(PilotTaskView.AttemptHistoryEntry::activeSeconds).sum();
        if (active > 0) {
            chips.add(minutes(active) + " active");
        }
        if (!view.usage().models().isEmpty()) {
            chips.add(String.join(", ", view.usage().models()));
        }
        return chips;
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
            case "verify", "verification", "VERIFIED", "VERIFICATION_FAILED" -> "Verification";
            case "publish", "initialize-pr-feedback", "pr-follow-up", "DELIVERY_BLOCKED" -> "Publication";
            default -> null;
        };
    }

    /**
     * "VERIFICATION_FAILED: 2 tests in error" and "verify: 2 tests in error" read
     * as "Verification failed: 2 tests in error"; other reasons pass through.
     */
    static String failureText(String reason) {
        if (reason == null || reason.isBlank()) {
            return "No reason was recorded";
        }
        String text = reason.strip();
        String stage = null;
        Matcher prefix = LEADING_STAGE.matcher(text);
        if (prefix.matches() && (stageLabel(prefix.group(1)) != null || prefix.group(1).matches("[A-Z_]+"))) {
            stage = "VERIFIED".equals(prefix.group(1)) ? null : stageLabel(prefix.group(1));
            text = prefix.group(2).strip();
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return stage == null ? Character.toUpperCase(text.charAt(0)) + text.substring(1)
                : stage + " failed: " + text;
    }

    static String pullRequestLabel(String url) {
        if (url == null) {
            return "Pull request";
        }
        Matcher number = PULL_NUMBER.matcher(url);
        return number.find() ? "Pull request #" + number.group(1) : "Pull request";
    }

    static String costLabel(PilotTaskUsage usage) {
        if (usage.estimatesByCurrency().isEmpty()) {
            return null;
        }
        return usage.estimatesByCurrency().entrySet().stream()
                .map(entry -> "USD".equals(entry.getKey())
                        ? "$" + entry.getValue().setScale(2, RoundingMode.HALF_UP).toPlainString()
                        : plain(entry.getValue()) + " " + entry.getKey())
                .collect(Collectors.joining(" + "));
    }

    /** Why a finished cost is missing or may be too low; null when all usage was reported and priced. */
    static String incompleteCostNote(PilotTaskUsage usage) {
        return usage.complete() ? null : costLabel(usage) == null ? COST_UNKNOWN : COST_MAY_BE_HIGHER;
    }

    static String costNoteTitle(String costNote) {
        return COST_MAY_BE_HIGHER.equals(costNote) ? COST_MAY_BE_HIGHER_TITLE
                : COST_UNKNOWN.equals(costNote) ? COST_UNKNOWN_TITLE : null;
    }

    static String usageNote(PilotTaskUsage usage) {
        var notes = new ArrayList<String>();
        if (!usage.complete()) {
            notes.add("Incomplete: some usage has no reported tokens or rate yet. Cost per attempt is in Attempts.");
        }
        if (usage.provisional()) {
            notes.add("Includes live usage that can still change.");
        }
        return notes.isEmpty() ? null : String.join(" ", notes);
    }

    static String categoryLabel(Object category) {
        return switch (category.toString()) {
            case "INPUT" -> "Input (uncached)";
            case "CACHE_READ" -> "Cache read";
            case "CACHE_WRITE" -> "Cache write";
            case "OUTPUT" -> "Output";
            default -> category.toString();
        };
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

    private static String sentence(String text) {
        if (text == null || text.isBlank()) {
            return "No reason was recorded.";
        }
        String stripped = text.strip();
        return stripped.endsWith(".") ? stripped : stripped + ".";
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String joinNonNull(String first, String second) {
        if (first == null) {
            return second;
        }
        return second == null ? first : first + " · " + second;
    }

    private static String ordinal(int position) {
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
