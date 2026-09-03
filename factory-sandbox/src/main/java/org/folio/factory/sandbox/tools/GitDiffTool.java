package org.folio.factory.sandbox.tools;

import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.springframework.stereotype.Component;

@Component
public class GitDiffTool {

  static final long DIFF_TIMEOUT_SEC = 30L;
  private static final String REPO_DIR = "repo";

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

  private static String stderrOrStdout(CommandResult result) {
    return result.stderr() != null && !result.stderr().isBlank() ? result.stderr() : result.stdout();
  }
}
