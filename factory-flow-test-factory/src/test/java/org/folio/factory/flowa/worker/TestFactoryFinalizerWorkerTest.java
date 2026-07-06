package org.folio.factory.flowa.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.flowa.FlowAProperties;
import org.folio.factory.flowa.artifact.ScriptBundleCodec;
import org.folio.factory.flowa.model.ScriptBundle;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TestFactoryFinalizerWorkerTest {

    private final FrontmatterCodec codec = new FrontmatterCodec();
    private final ScriptBundleCodec bundleCodec = new ScriptBundleCodec(codec);

    static class RecordingGitHub implements GitHubConnector {
        boolean branchExists;
        boolean committed;

        @Override
        public void createBranch(String repo, String baseBranch, String newBranch) {
            if (branchExists) {
                throw HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Reference already exists", HttpHeaders.EMPTY, new byte[0], null);
            }
        }

        @Override
        public void commitFiles(String repo, String branch, Map<String, String> files, String message) {
            committed = true;
        }

        @Override
        public String createPullRequest(String repo, String headBranch, String baseBranch,
                                        String title, String body) {
            return "https://github.com/o/r/pull/9";
        }
    }

    static class FailingRunTestRail implements TestRailConnector {
        @Override
        public long addCase(long sectionId, String title, String steps, String expected) {
            return 1;
        }

        @Override
        public long addRun(String name, List<Long> caseIds) {
            throw new RestClientException("TestRail returned 500");
        }

        @Override
        public void addResults(long runId, Map<Long, Boolean> results, String comment) {
        }
    }

    static class RecordingJira implements JiraConnector {
        String lastComment;

        @Override
        public JiraIssue getIssue(String issueKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addComment(String issueKey, String body) {
            lastComment = issueKey + ": " + body;
        }

        @Override
        public void transitionIssue(String issueKey, String transitionName) {
        }
    }

    private String planArtifact(Map<String, Object> metadata) {
        return codec.render(metadata, "# Test Plan");
    }

    private AgentContext contextWith(String planContent) {
        String scripts = bundleCodec.render(new ScriptBundle("karate", List.of(
                new ScriptBundle.ScriptFile("features/a.feature", List.of("TC-01"),
                        "Feature: A\n  Scenario: TC-01 works"))));
        String results = codec.render(Map.of("mode", "ADVISORY", "case_results", List.of()), "advisory");
        return new AgentContext(UUID.randomUUID(), "finalize", Map.of(
                "test_plan.md", new ArtifactContent("test_plan.md", 1, "text/markdown", planContent),
                "test_scripts.md", new ArtifactContent("test_scripts.md", 1, "text/markdown", scripts),
                "test_results.md", new ArtifactContent("test_results.md", 1, "text/markdown", results)),
                null, Map.of(), List.of("sync_report.md"));
    }

    private TestFactoryFinalizerWorker worker(GitHubConnector gitHub, TestRailConnector testRail,
                                              JiraConnector jira) {
        FlowAProperties properties = new FlowAProperties(
                new FlowAProperties.Execution(null, null), "o/r", "main", null, 55L);
        return new TestFactoryFinalizerWorker(jira, gitHub, testRail, codec, bundleCodec,
                properties, mock(AuditLog.class));
    }

    @Test
    void toleratesExistingBranchAndRecordsConnectorFailuresWithoutThrowing() {
        RecordingGitHub gitHub = new RecordingGitHub();
        gitHub.branchExists = true;
        RecordingJira jira = new RecordingJira();

        AgentResult result = worker(gitHub, new FailingRunTestRail(), jira).execute(contextWith(
                planArtifact(Map.of("issue_key", "ERM-9", "cases", List.of(
                        Map.of("id", "TC-01", "title", "works", "steps", List.of("s"), "expected", "e"))))));

        String report = result.outputs().get("sync_report.md");
        // Existing branch is reused, not fatal — the PR still gets created.
        assertThat(gitHub.committed).isTrue();
        assertThat(report).contains("https://github.com/o/r/pull/9");
        // A real TestRail failure is recorded, not retried via a thrown exception.
        assertThat(report).contains("testrail").contains("failed").contains("TestRail returned 500");
        // Later connectors still run after an earlier failure.
        assertThat(jira.lastComment).startsWith("ERM-9:");
    }

    @Test
    void failsFastOnBlankIssueKeyBeforeAnySideEffect() {
        RecordingGitHub gitHub = new RecordingGitHub();

        assertThatThrownBy(() -> worker(gitHub, new FailingRunTestRail(), new RecordingJira())
                .execute(contextWith(planArtifact(Map.of("cases", List.of())))))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("no issue key");
        assertThat(gitHub.committed).isFalse();
    }
}
