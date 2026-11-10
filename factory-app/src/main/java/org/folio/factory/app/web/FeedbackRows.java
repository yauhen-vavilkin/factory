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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Page rows of the PR feedback part of the run page: the new PR comments and the rounds. */
final class FeedbackRows {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US).withZone(ZoneOffset.UTC);
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
     * One round: what it fixed, its commit, checks, a short summary of the agent's report, the full report
     * and Factory's comment on the PR.
     */
    public record Round(String title, String comments, String commit, String commitSha, String commitUrl, String meta,
                        String summary, String report, String reason, String commentUrl, String cost, Chip chip,
                        boolean manual,
                        List<Selected> selected, Map<String, Object> usage, String runtime,
                        VerificationReceipt verification, List<Map<String, String>> activity) {
    }

    public record Chip(String label, String cssClass) {
    }

    /** The round's usage, activity and the URL of Factory's comment, read from the audit trail. */
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
        var usage = facts.projection().summary(round, facts.live());
        var sha = state.commitSha().isBlank() ? null : state.commitSha();
        var shortSha = sha == null ? null : sha.substring(0, Math.min(SHORT_SHA, sha.length()));
        var selected = state.selected().stream()
                .map(comment -> new Selected(comment.author(), comment.body()))
                .toList();
        return new Round(title, commentCount(state), shortSha, sha, commitUrl(state.pr(), sha), meta(state),
                RoundReport.brief(state.summary()), state.summary(), state.reason(), facts.commentUrls().get(round),
                String.valueOf(usage.get("cost")),
                chip(state), state.manual(), selected, usage, state.runtimeName(), state.verification(),
                facts.projection().activity(round));
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

    /** "Nov 10, 18:22 · 6 min · 300 tests passed". */
    private static String meta(FeedbackState state) {
        var started = instant(state.roundStartedAt());
        var finished = instant(state.finishedAt());
        return Stream.of(started == null ? null : WHEN.format(started),
                        started == null || finished == null ? null : minutes(Duration.between(started, finished)),
                        tests(state.verification()))
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" · "));
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

    private static Instant instant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
