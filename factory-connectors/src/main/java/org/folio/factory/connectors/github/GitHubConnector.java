package org.folio.factory.connectors.github;

import java.util.Map;

public interface GitHubConnector {

    /**
     * Creates {@code newBranch} pointing at the head of {@code baseBranch}.
     *
     * @param repo "owner/name"
     */
    void createBranch(String repo, String baseBranch, String newBranch);

    /**
     * Commits the given files (path → content) to the branch, one commit per call.
     */
    void commitFiles(String repo, String branch, Map<String, String> files, String message);

    /**
     * @return the created pull request's HTML URL
     */
    String createPullRequest(String repo, String headBranch, String baseBranch, String title, String body);
}
