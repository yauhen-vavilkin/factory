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
  private static final String REPO_DIR = "repo";

  private final SandboxService sandboxService;

  public ApplyPatchTool(SandboxService sandboxService) {
    this.sandboxService = sandboxService;
  }

  /**
   * T23 R5: the staged patch file is keyed to the handle's sandbox id (unique
   * per execution), so two concurrent executions — which in local mode share
   * the host /tmp — cannot overwrite each other's staged diff between
   * --check and apply. This removes the shared-path collision only; local
   * mode still runs every workspace on one host and is not an isolation
   * boundary (docker mode stages inside the container's own /tmp).
   */
  private static String patchFile(SandboxHandle handle) {
    return "/tmp/factory-patch-"
        + handle.sandboxId().replaceAll("[^A-Za-z0-9._-]", "_") + ".diff";
  }

  public ToolResult apply(SandboxHandle handle, String diff) {
    if (diff == null || diff.isBlank()) {
      return ToolResult.failure("diff is required");
    }
    String patchFile = patchFile(handle);
    String encoded = Base64.getEncoder().encodeToString(diff.getBytes(StandardCharsets.UTF_8));
    CommandResult staged = sandboxService.exec(handle,
        "printf '%s' " + Shell.quote(encoded) + " | base64 -d > " + patchFile,
        APPLY_TIMEOUT_SEC);
    if (!staged.ok()) {
      return ToolResult.failure("failed to stage patch file: " + stderrOrStdout(staged));
    }
    CommandResult check = sandboxService.exec(handle,
        "cd " + REPO_DIR + " && git apply --check " + patchFile, APPLY_TIMEOUT_SEC);
    if (!check.ok()) {
      return ToolResult.failure("patch does not apply cleanly: " + stderrOrStdout(check)
          + " | the diff was NOT applied; regenerate a complete unified diff (git diff format) "
          + "against the current file content and retry");
    }
    CommandResult applied = sandboxService.exec(handle,
        "cd " + REPO_DIR + " && git apply " + patchFile, APPLY_TIMEOUT_SEC);
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
