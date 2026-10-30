package org.folio.factory.connectors.github;

import java.util.List;
import java.util.Map;

public interface GitHubConnector {

    /** Read-only lookup result; matching against stored publication identity stays with the caller. */
    record PullRequest(
            String htmlUrl, String state, boolean merged, String headRef, String headSha, String baseRef) {
    }

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

    /**
     * Like the five-argument method, but opens a draft pull request when {@code draft} is true.
     *
     * @return the created pull request's HTML URL
     */
    default String createPullRequest(String repo, String headBranch, String baseBranch, String title, String body,
                                     boolean draft) {
        throw new UnsupportedOperationException("This GitHub connector cannot choose draft pull requests");
    }

    /** Find an open PR inside the destination repository before creating another. */
    default java.util.Optional<String> findOpenPullRequest(String repo, String headBranch, String baseBranch) {
        throw new UnsupportedOperationException("GitHub pull request lookup is not configured");
    }

    /**
     * Read-only lookup across open, closed and merged pull requests for an exact
     * head/base pair inside the destination repository, so a lost create response
     * is never misread as "no pull request exists".
     */
    default List<PullRequest> findPullRequests(String repo, String headBranch, String baseBranch) {
        throw new UnsupportedOperationException("GitHub pull request lookup is not configured");
    }
}
