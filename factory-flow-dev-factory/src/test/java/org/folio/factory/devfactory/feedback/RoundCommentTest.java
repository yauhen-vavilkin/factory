package org.folio.factory.devfactory.feedback;

import org.folio.factory.core.service.AuditLog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoundCommentTest {

    private static final String MODEL_KEY = "sk-model_secret-value-1234";

    /** A PR comment can steer the agent's report, and Factory posts the report under its own token. */
    @Test
    void theRoundCommentCleansTheAgentsReportLikeThePullRequestBody() {
        var posted = new StringBuilder();
        var audit = mock(AuditLog.class);
        when(audit.forExecution(any())).thenReturn(List.of());
        var comment = new RoundComment(writer(posted), audit);
        var state = new FeedbackState();
        state.round = 1;
        state.commitSha = "abc123";
        state.pr = new PrFeedbackGateway.PullRequest("fork/repo", 7, "factory/task", "main", "abc123",
                "https://github.com/fork/repo/pull/7", true);
        state.summary = """
                ## Changes
                - Fixed the null check, as @org/team asked.
                ![x](https://evil.example/?d=1) <a href="https://evil.example">here</a>
                Token ghp_abcdefghijklmnopqrstuvwxyz0123
                Model key sk-model_secret-value-1234
                """;

        comment.post(UUID.randomUUID(), "pr-follow-up", state, text -> text.replace(MODEL_KEY, "[R]"));

        assertThat(posted.toString())
                .contains("Factory round 1: pushed abc123.", "- Fixed the null check", "Model key &#91;R&#93;")
                .doesNotContain("@org", "https://evil.example", "<a ", "ghp_abcdefghijklmnopqrstuvwxyz0123",
                        "model&#95;secret");
    }

    /** A long report is cut at a line end, so a token on the last line kept is still redacted whole. */
    @Test
    void aLongReportIsCutAtALineEndSoATokenIsNeverCutInTwo() {
        var posted = new StringBuilder();
        var audit = mock(AuditLog.class);
        when(audit.forExecution(any())).thenReturn(List.of());
        var state = new FeedbackState();
        state.round = 2;
        state.commitSha = "def456";
        state.pr = new PrFeedbackGateway.PullRequest("fork/repo", 7, "factory/task", "main", "def456",
                "https://github.com/fork/repo/pull/7", true);
        // The token starts at about character 5990 of the section, so a cut at 6000 would split it.
        state.summary = "## Changes\n- see www.evil.example\n- " + "x".repeat(5956)
                + "\n- token ghp_abcdefghijklmnopqrstuvwxyz0123\n";

        new RoundComment(writer(posted), audit).post(UUID.randomUUID(), "pr-follow-up", state, text -> text);

        assertThat(posted.toString()).endsWith("[cut]").doesNotContain("ghp", "www.evil");
    }

    private static PrCommentWriter writer(StringBuilder posted) {
        return new PrCommentWriter() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public String post(PrFeedbackGateway.PullRequest pr, String body) {
                posted.append(body);
                return "https://github.com/fork/repo/pull/7#issuecomment-1";
            }
        };
    }
}
