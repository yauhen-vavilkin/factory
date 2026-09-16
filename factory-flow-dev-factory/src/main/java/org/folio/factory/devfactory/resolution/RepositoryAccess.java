package org.folio.factory.devfactory.resolution;

import java.util.Optional;

/** Read-only trusted repository access. Implementations must reject redirects. */
public interface RepositoryAccess {
  String resolveBranch(String canonicalSlug, String branch);

  String verifyCommit(String canonicalSlug, String fullSha);

  Optional<byte[]> readFile(String canonicalSlug, String fullSha, String path, int maxBytes);

  /** The repository's own default branch, used when a task names neither revision nor ref. */
  default String defaultBranch(String canonicalSlug) {
    throw new RepositoryAccessException("default branch lookup is not supported by this repository access");
  }
}
