package org.folio.factory.connectors;

import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.connectors.testrail.TestRailConnector;

import java.util.List;
import java.util.Map;

/**
 * Fallback implementations bound when credentials are absent. Every call fails
 * with a message naming the exact environment variables to set, so pipelines and
 * operators immediately know what is missing.
 */
public final class UnconfiguredConnectors {

    private UnconfiguredConnectors() {
    }

    public static final class Jira implements JiraConnector, ConnectorHealth {

        private static ConnectorNotConfiguredException notConfigured() {
            return new ConnectorNotConfiguredException("Jira connector not configured: set "
                    + "FACTORY_CONNECTORS_JIRA_BASE_URL (plus FACTORY_CONNECTORS_JIRA_EMAIL and "
                    + "FACTORY_CONNECTORS_JIRA_API_TOKEN for non-public issues)");
        }

        @Override
        public JiraIssue getIssue(String issueKey) {
            throw notConfigured();
        }

        @Override
        public tools.jackson.databind.JsonNode getComments(String issueKey, int limit) {
            throw notConfigured();
        }

        @Override
        public JiraIssue getLinkedIssue(String issueKey) {
            throw notConfigured();
        }

        @Override
        public tools.jackson.databind.JsonNode getFields() {
            throw notConfigured();
        }

        @Override
        public void addComment(String issueKey, String body) {
            throw notConfigured();
        }

        @Override
        public void transitionIssue(String issueKey, String transitionName) {
            throw notConfigured();
        }

        @Override
        public String connectorName() {
            return "jira";
        }

        @Override
        public boolean isConfigured() {
            return false;
        }
    }

    public static final class GitHub implements GitHubConnector, ConnectorHealth {

        private static ConnectorNotConfiguredException notConfigured() {
            return new ConnectorNotConfiguredException(
                    "GitHub connector not configured: set FACTORY_CONNECTORS_GITHUB_TOKEN "
                            + "(and FACTORY_CONNECTORS_GITHUB_BASE_URL for GitHub Enterprise)");
        }

        @Override
        public void createBranch(String repo, String baseBranch, String newBranch) {
            throw notConfigured();
        }

        @Override
        public void commitFiles(String repo, String branch, Map<String, String> files, String message) {
            throw notConfigured();
        }

        @Override
        public String createPullRequest(String repo, String headBranch, String baseBranch,
                                        String title, String body) {
            throw notConfigured();
        }

        @Override
        public String connectorName() {
            return "github";
        }

        @Override
        public boolean isConfigured() {
            return false;
        }
    }

    public static final class TestRail implements TestRailConnector, ConnectorHealth {

        private static ConnectorNotConfiguredException notConfigured() {
            return new ConnectorNotConfiguredException("TestRail connector not configured: set "
                    + "FACTORY_CONNECTORS_TESTRAIL_BASE_URL, FACTORY_CONNECTORS_TESTRAIL_USERNAME, "
                    + "FACTORY_CONNECTORS_TESTRAIL_API_KEY and FACTORY_CONNECTORS_TESTRAIL_PROJECT_ID");
        }

        @Override
        public long addCase(long sectionId, String title, String steps, String expected) {
            throw notConfigured();
        }

        @Override
        public long addRun(String name, List<Long> caseIds) {
            throw notConfigured();
        }

        @Override
        public void addResults(long runId, Map<Long, Boolean> results, String comment) {
            throw notConfigured();
        }

        @Override
        public String connectorName() {
            return "testrail";
        }

        @Override
        public boolean isConfigured() {
            return false;
        }
    }
}
