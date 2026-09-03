package org.folio.factory.sandbox.tools;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.springframework.stereotype.Component;

@Component
public class ApplyPatchTool {

  static final long APPLY_TIMEOUT_SEC = 30L;
  static final String PATCH_FILE = "/tmp/factory-patch.diff";
  private static final String REPO_DIR = "repo";

  private final SandboxService sandboxService;

  public ApplyPatchTool(SandboxService sandboxService) {
    this.sandboxService = sandboxService;
  }

  public ToolResult apply(SandboxHandle handle, String diff) {
    if (diff == null || diff.isBlank()) {
      return ToolResult.failure("diff is required");
    }
    String encoded = Base64.getEncoder().encodeToString(diff.getBytes(StandardCharsets.UTF_8));
    CommandResult staged = sandboxService.exec(handle,
        "printf '%s' " + Shell.quote(encoded) + " | base64 -d > " + PATCH_FILE,
        APPLY_TIMEOUT_SEC);
    if (!staged.ok()) {
      return ToolResult.failure("failed to stage patch file: " + stderrOrStdout(staged));
    }
    CommandResult check = sandboxService.exec(handle,
        "cd " + REPO_DIR + " && git apply --check " + PATCH_FILE, APPLY_TIMEOUT_SEC);
    if (!check.ok()) {
      return ToolResult.failure("patch does not apply cleanly: " + stderrOrStdout(check)
          + " | the diff was NOT applied; regenerate a complete unified diff (git diff format) "
          + "against the current file content and retry");
    }
    CommandResult applied = sandboxService.exec(handle,
        "cd " + REPO_DIR + " && git apply " + PATCH_FILE, APPLY_TIMEOUT_SEC);
    if (!applied.ok()) {
      return ToolResult.failure("patch passed --check but apply failed: " + stderrOrStdout(applied)
          + " | inspect the working tree with git_diff before retrying");
    }
    return ToolResult.success("patch applied");
  }

  private static String stderrOrStdout(CommandResult result) {
    return result.stderr() != null && !result.stderr().isBlank() ? result.stderr() : result.stdout();
  }
}
