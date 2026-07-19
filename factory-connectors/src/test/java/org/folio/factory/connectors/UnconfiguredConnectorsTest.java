package org.folio.factory.connectors;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnconfiguredConnectorsTest {

    @Test
    void jiraFailsWithEnvVarNames() {
        UnconfiguredConnectors.Jira jira = new UnconfiguredConnectors.Jira();

        assertThat(jira.isConfigured()).isFalse();
        assertThat(jira.connectorName()).isEqualTo("jira");
        List<ThrowingCallable> calls = List.of(
                () -> jira.getIssue("ERM-42"),
                () -> jira.addComment("ERM-42", "body"),
                () -> jira.transitionIssue("ERM-42", "Done"));
        for (ThrowingCallable call : calls) {
            assertThatThrownBy(call)
                    .isInstanceOf(ConnectorNotConfiguredException.class)
                    .hasMessageContaining("FACTORY_CONNECTORS_JIRA_BASE_URL")
                    .hasMessageContaining("FACTORY_CONNECTORS_JIRA_EMAIL")
                    .hasMessageContaining("FACTORY_CONNECTORS_JIRA_API_TOKEN");
        }
    }

    @Test
    void gitHubFailsWithEnvVarNames() {
        UnconfiguredConnectors.GitHub gitHub = new UnconfiguredConnectors.GitHub();

        assertThat(gitHub.isConfigured()).isFalse();
        assertThat(gitHub.connectorName()).isEqualTo("github");
        List<ThrowingCallable> calls = List.of(
                () -> gitHub.createBranch("o/r", "main", "feature"),
                () -> gitHub.commitFiles("o/r", "feature", Map.of(), "msg"),
                () -> gitHub.createPullRequest("o/r", "feature", "main", "Title", "Body"));
        for (ThrowingCallable call : calls) {
            assertThatThrownBy(call)
                    .isInstanceOf(ConnectorNotConfiguredException.class)
                    .hasMessageContaining("FACTORY_CONNECTORS_GITHUB_TOKEN")
                    .hasMessageContaining("FACTORY_CONNECTORS_GITHUB_BASE_URL");
        }
    }

    @Test
    void testRailFailsWithEnvVarNames() {
        UnconfiguredConnectors.TestRail testRail = new UnconfiguredConnectors.TestRail();

        assertThat(testRail.isConfigured()).isFalse();
        assertThat(testRail.connectorName()).isEqualTo("testrail");
        List<ThrowingCallable> calls = List.of(
                () -> testRail.addCase(55, "title", "steps", "expected"),
                () -> testRail.addRun("run", List.of(991L)),
                () -> testRail.addResults(300, Map.of(991L, true), "comment"));
        for (ThrowingCallable call : calls) {
            assertThatThrownBy(call)
                    .isInstanceOf(ConnectorNotConfiguredException.class)
                    .hasMessageContaining("FACTORY_CONNECTORS_TESTRAIL_BASE_URL")
                    .hasMessageContaining("FACTORY_CONNECTORS_TESTRAIL_USERNAME")
                    .hasMessageContaining("FACTORY_CONNECTORS_TESTRAIL_API_KEY")
                    .hasMessageContaining("FACTORY_CONNECTORS_TESTRAIL_PROJECT_ID");
        }
    }
}
