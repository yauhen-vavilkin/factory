package org.folio.factory.sandbox.tools;

import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.springframework.stereotype.Component;

@Component
public class GitDiffTool {

  static final long DIFF_TIMEOUT_SEC = 30L;
  private static final String REPO_DIR = "repo";

  /**
   * T24 B1: builds a complete CONTENT identity of the delivered result. One
   * shell invocation so the scratch index lives and dies inside it: seed an
   * isolated index ({@code GIT_INDEX_FILE}) from HEAD, stage the entire
   * worktree onto it ({@code git add -A} — untracked, modified, deleted and
   * staged content alike; worktree state wins over a stale staging area),
   * then print the resulting tree id. The tree id is blob-content-addressed,
   * so equality proves content equality of the delivered scope — unlike the
   * display diff, it cannot collide through truncation, binary contents,
   * untracked/staged files, or commits made during the run. The real index
   * is never touched and the scratch index is removed before the shell
   * exits.
   */
  static final String WORKTREE_IDENTITY_COMMAND = "cd " + REPO_DIR + " && idx=$(mktemp) && { "
      + "GIT_INDEX_FILE=\"$idx\" git read-tree HEAD"
      + " && GIT_INDEX_FILE=\"$idx\" git add -A"
      + " && GIT_INDEX_FILE=\"$idx\" git write-tree;"
      + " rc=$?; rm -f \"$idx\"; exit \"$rc\"; }";

  private static final String TREE_ID_PATTERN = "[0-9a-f]{40,64}";

  private final SandboxService sandboxService;

  public GitDiffTool(SandboxService sandboxService) {
    this.sandboxService = sandboxService;
  }

  public ToolResult diff(SandboxHandle handle) {
    CommandResult status = sandboxService.exec(handle,
        "cd " + REPO_DIR + " && git status --porcelain", DIFF_TIMEOUT_SEC);
    if (!status.ok()) {
      return ToolResult.failure("git status failed: " + stderrOrStdout(status));
    }
    CommandResult diff = sandboxService.exec(handle,
        "cd " + REPO_DIR + " && git diff", DIFF_TIMEOUT_SEC);
    if (!diff.ok()) {
      return ToolResult.failure("git diff failed: " + stderrOrStdout(diff));
    }
    String diffText = diff.stdout().isBlank() ? "(no changes)" : diff.stdout().stripTrailing();
    String output = "[status]\n" + status.stdout().stripTrailing() + "\n\n[diff]\n" + diffText;
    return ToolResult.success(OutputLimiter.truncate(output));
  }

  /**
   * The delivered-content identity of the working tree, or a failure when it
   * could not be captured (a missing identity can never count as fresh
   * evidence).
   */
  public ToolResult worktreeIdentity(SandboxHandle handle) {
    CommandResult tree = sandboxService.exec(handle, WORKTREE_IDENTITY_COMMAND, DIFF_TIMEOUT_SEC);
    if (!tree.ok()) {
      return ToolResult.failure("git write-tree failed: " + stderrOrStdout(tree));
    }
    String identity = tree.stdout() == null ? "" : tree.stdout().strip();
    if (!identity.matches(TREE_ID_PATTERN)) {
      return ToolResult.failure(
          "git write-tree did not print a tree id: '" + identity + "'");
    }
    return ToolResult.success(identity);
  }

  private static String stderrOrStdout(CommandResult result) {
    return result.stderr() != null && !result.stderr().isBlank() ? result.stderr() : result.stdout();
  }
}
