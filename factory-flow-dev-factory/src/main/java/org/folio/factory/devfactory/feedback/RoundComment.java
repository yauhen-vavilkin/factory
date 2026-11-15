package org.folio.factory.devfactory.feedback;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.devfactory.delivery.PullRequestRenderer;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Factory's one comment on the pull request after a published round: the commit and the "Changes"
 * part of the agent's report. The comment never fails the round; its URL is kept in the audit trail,
 * keyed by round.
 */
public final class RoundComment {

    /** The audit action of the comment; its detail keeps the round and the comment URL. */
    public static final String ACTION = "pr-round-comment";
    /** Starts every Factory comment, so reading PR comments can leave Factory's own out. */
    static final String MARKER = "<!-- factory-round ";
    private static final int MAX_SUMMARY = 6000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PrCommentWriter writer;
    private final AuditLog audit;

    RoundComment(PrCommentWriter writer, AuditLog audit) {
        this.writer = writer;
        this.audit = audit;
    }

    void post(UUID executionId, String stepId, FeedbackState state, UnaryOperator<String> redact) {
        var detail = Map.<String, Object>of("connector", "github", "action", ACTION, "round", state.round,
                "commit", state.commitSha);
        if (!writer.configured()) {
            audit.record(executionId, AuditEventType.CONNECTOR_SKIPPED, stepId, with(detail, "reason",
                    "GitHub token is not set, so Factory did not comment on the pull request"));
            return;
        }
        if (alreadyPosted(executionId, state.round)) {
            return;
        }
        try {
            var url = writer.post(state.pr, redact.apply(body(executionId, state, redact)));
            audit.record(executionId, AuditEventType.CONNECTOR_ACTION, stepId, with(detail, "commentUrl", url));
        } catch (RuntimeException e) {
            audit.record(executionId, AuditEventType.CONNECTOR_ACTION, stepId, with(detail, "error",
                    "Factory could not comment on the pull request: " + redact.apply(String.valueOf(e.getMessage()))));
        }
    }

    private boolean alreadyPosted(UUID executionId, int round) {
        return audit.forExecution(executionId).stream()
                .filter(event -> event.getEventType() == AuditEventType.CONNECTOR_ACTION)
                .map(AuditEvent::getDetail)
                .map(RoundComment::readDetail)
                .anyMatch(detail -> ACTION.equals(detail.get("action"))
                        && Integer.valueOf(round).equals(detail.get("round")) && detail.containsKey("commentUrl"));
    }

    private static String body(UUID executionId, FeedbackState state, UnaryOperator<String> redact) {
        // Redact first: the cleaning escapes characters a secret may hold, so it would no longer match.
        var changes = redact.apply(RoundReport.changes(state.summary));
        // The report is model text that a PR comment can steer, so it gets the cleaning of the PR body.
        var summary = changes.length() <= MAX_SUMMARY
                ? PullRequestRenderer.safeReport(changes)
                : PullRequestRenderer.safeReport(wholeLines(changes)) + "\n\n[cut]";
        return MARKER + executionId + " " + state.round + " -->\n"
                + "Factory round " + state.round + ": pushed " + state.commitSha + ".\n\n" + summary;
    }

    /** Cut at a line end: the cleaning redacts tokens line by line, and a token cut in two would not match. */
    private static String wholeLines(String changes) {
        var end = changes.lastIndexOf('\n', MAX_SUMMARY);
        return end > 0 ? changes.substring(0, end) : changes.lines().findFirst().orElse("");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readDetail(String detail) {
        try {
            return detail == null ? Map.of() : JSON.readValue(detail, Map.class);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static Map<String, Object> with(Map<String, Object> detail, String key, String value) {
        var copy = new java.util.LinkedHashMap<>(detail);
        copy.put(key, value);
        return copy;
    }
}
