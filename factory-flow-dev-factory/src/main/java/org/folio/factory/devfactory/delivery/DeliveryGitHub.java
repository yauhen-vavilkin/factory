package org.folio.factory.devfactory.delivery;

import java.util.List;
import java.util.Optional;

/** The few GitHub reads and the one pull-request write trusted delivery needs. */
public interface DeliveryGitHub {

  /** Repository facts: identity, fork lineage and the default (pull request base) branch. */
  RepositoryInfo repository(String slug);

  /** True when {@code sha} is reachable from {@code branch} (the branch contains the commit). */
  boolean branchContains(String slug, String branch, String sha);

  /** The current head commit of {@code branch}, or empty when the branch does not exist. */
  Optional<RemoteCommit> branchHead(String slug, String branch);

  /** Any pull request (open or closed) whose head is {@code owner:branch}. */
  Optional<PullRequest> findPullRequest(String slug, String owner, String branch);

  PullRequest createPullRequest(String slug, String headBranch, String baseBranch, String title,
                                String body);

  record RepositoryInfo(String fullName, boolean fork, String parentFullName,
                        String sourceFullName, String defaultBranch) {
  }

  record RemoteCommit(String sha, String tree, List<String> parents) {
  }

  record PullRequest(long number, String url, String state, String headSha) {
  }
}
