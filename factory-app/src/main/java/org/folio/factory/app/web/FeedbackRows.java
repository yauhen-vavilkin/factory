package org.folio.factory.app.web;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.devfactory.feedback.FeedbackState;
import org.folio.factory.devfactory.feedback.PrFeedbackGateway;
import org.folio.factory.devfactory.feedback.RoundComment;
import org.folio.factory.devfactory.feedback.RoundReport;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Page rows of the PR feedback part of the run page: the new PR comments and the rounds. */
final class FeedbackRows {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int SHORT_SHA = 7;

    private FeedbackRows() {
    }

    /** A PR comment no earlier round addressed. */
    public record Comment(String id, String author, String body, String location, String url, boolean outdated) {
    }

    public record Selected(String author, String body) {
    }

    /**
     * One round: what it fixed, its commit, when it started and what followed ({@code meta}, with
     * {@code metaTitle} the whole line), the first sentence of the agent's changes, the report as text, the
     * checks and Factory's comment on the PR. Values the round does not have are null.
     */
    public record Round(String title, String comments, String commit, String commitUrl, PageTime started,
                        String meta, String metaTitle, String summary, String report, String reason,
                        String commentUrl, String cost, Chip chip, List<Selected> selected, String checks,
                        String checksOutput) {
    }

    public record Chip(String label, String cssClass) {
    }

    /** The round's usage and the URL of Factory's comment, read from the audit trail. */
    record RoundFacts(AttemptUsageSummary.Projection projection, boolean live, Map<Integer, String> commentUrls) {
    }

    static List<Comment> newComments(PrFeedbackGateway.Snapshot snapshot, Set<String> processed) {
        if (snapshot == null) {
            return List.of();
        }
        return snapshot.comments().stream()
                .filter(comment -> !processed.contains(comment.id()))
                .map(FeedbackRows::comment)
                .toList();
    }

    static List<Round> rounds(TreeMap<Integer, FeedbackState> rounds, RoundFacts facts) {
        var rows = new ArrayList<Round>();
        int correction = 0;
        int manual = 0;
        for (var entry : rounds.entrySet()) {
            var state = entry.getValue();
            var title = state.manual() ? "Manual verification " + (++manual) : "Round " + (++correction);
            rows.add(round(title, entry.getKey(), state, facts));
        }
        return rows;
    }

    /** Round number to the URL of Factory's comment on the PR for that round. */
    static Map<Integer, String> commentUrls(List<AuditEvent> events) {
        var urls = new HashMap<Integer, String>();
        events.stream()
                .filter(event -> event.getEventType() == AuditEventType.CONNECTOR_ACTION)
                .map(event -> UiFormat.parse(event.getDetail(), JSON))
                .filter(detail -> RoundComment.ACTION.equals(detail.path("action").asString(""))
                        && detail.path("commentUrl").isString())
                .forEach(detail -> urls.put(detail.path("round").asInt(), detail.path("commentUrl").asString()));
        return urls;
    }

    private static Comment comment(PrFeedbackGateway.Comment comment) {
        var location = comment.path().isBlank() ? "PR discussion"
                : comment.path() + (comment.line() == null ? "" : ":" + comment.line());
        var url = comment.url() == null || comment.url().isBlank() ? null : comment.url();
        return new Comment(comment.id(), comment.author(), comment.body(), location, url, comment.outdated());
    }

    private static Round round(String title, int round, FeedbackState state, RoundFacts facts) {
        var sha = blankToNull(state.commitSha());
        var shortSha = sha == null ? null : sha.substring(0, Math.min(SHORT_SHA, sha.length()));
        var selected = state.selected().stream()
                .map(comment -> new Selected(comment.author(), comment.body()))
                .toList();
        var report = state.manual() || !RoundReport.hasChanges(state.summary()) ? null
                : blankToNull(RoundReport.text(state.summary()));
        var verification = state.verification();
        var started = PageTime.day(instant(state.roundStartedAt()));
        var meta = blankToNull(meta(state));
        var metaTitle = blankToNull(joined(started == null ? null : started.text(), meta));
        return new Round(title, commentCount(state), shortSha, commitUrl(state.pr(), sha), started, meta, metaTitle,
                blankToNull(RoundReport.firstSentence(state.summary())), report, blankToNull(state.reason()),
                facts.commentUrls().get(round), String.valueOf(facts.projection().summary(round, facts.live())
                .get("cost")), chip(state), selected, checks(verification), checksOutput(verification));
    }

    private static String commentCount(FeedbackState state) {
        if (state.manual()) {
            return "your changes";
        }
        var count = state.selected().size();
        return count + (count == 1 ? " comment" : " comments");
    }

    private static String commitUrl(PrFeedbackGateway.PullRequest pr, String sha) {
        if (sha == null || pr == null || pr.url() == null || pr.url().isBlank()) {
            return null;
        }
        return pr.url() + "/commits/" + sha;
    }

    /** "6 min · 300 tests passed": what follows the start time of the round. */
    private static String meta(FeedbackState state) {
        var started = instant(state.roundStartedAt());
        var finished = instant(state.finishedAt());
        return joined(started == null || finished == null ? null : minutes(Duration.between(started, finished)),
                tests(state.verification()));
    }

    private static String joined(String... parts) {
        return Stream.of(parts).filter(Objects::nonNull).collect(Collectors.joining(" · "));
    }

    /** "6 min", as the task page writes the time of an attempt. */
    private static String minutes(Duration duration) {
        var minutes = Math.max(0, duration.toMinutes());
        return minutes < 1 ? "<1 min" : minutes + " min";
    }

    private static String tests(VerificationReceipt verification) {
        if (verification == null || verification.testCount() == null) {
            return null;
        }
        if ("PASS".equals(verification.result())) {
            return verification.testCount() + " tests passed";
        }
        var failures = verification.failureCount() == null ? 0 : verification.failureCount();
        return verification.testCount() + " tests, " + failures + " failed";
    }

    /** "Checks passed: 300 tests." or "Checks failed: 2 of 300 tests failed."; null when no checks ran. */
    private static String checks(VerificationReceipt verification) {
        if (verification == null) {
            return null;
        }
        var passed = "PASS".equals(verification.result());
        var count = verification.testCount();
        if (count == null) {
            return passed ? "Checks passed." : "Checks failed.";
        }
        if (passed) {
            return "Checks passed: " + count + (count == 1 ? " test." : " tests.");
        }
        var failed = Objects.requireNonNullElse(verification.failureCount(), 0)
                + Objects.requireNonNullElse(verification.errorCount(), 0);
        return failed == 0 ? "Checks failed." : "Checks failed: " + failed + " of " + count + " tests failed.";
    }

    /** The output of checks that did not pass; passed checks need no output. */
    private static String checksOutput(VerificationReceipt verification) {
        if (verification == null || "PASS".equals(verification.result())) {
            return null;
        }
        return blankToNull(verification.output());
    }

    private static Chip chip(FeedbackState state) {
        return switch (state.phase()) {
            case READY -> !state.commitSha().isBlank() ? new Chip("Published", "chip chip-success")
                    : state.manual() ? new Chip("Verified", "chip chip-success")
                    : new Chip("No changes", "chip chip-neutral");
            case QUESTION, REFRESH_REQUIRED -> new Chip(FeedbackUi.label(state.phase()), "chip chip-warning");
            case FAILURE, INTERRUPTED, CLEANUP_BLOCKED -> new Chip(FeedbackUi.label(state.phase()), "chip chip-danger");
            default -> new Chip(FeedbackUi.label(state.phase()), "chip chip-info");
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Instant instant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
