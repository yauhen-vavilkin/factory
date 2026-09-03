package org.folio.factory.sandbox.tools;

import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.springframework.stereotype.Component;

@Component
public class ExecTool {

  static final long DEFAULT_TIMEOUT_SEC = 120L;

  private final SandboxService sandboxService;

  public ExecTool(SandboxService sandboxService) {
    this.sandboxService = sandboxService;
  }

  public ToolResult run(SandboxHandle handle, String cmd, Long timeoutSec) {
    if (cmd == null || cmd.isBlank()) {
      return ToolResult.failure("cmd is required");
    }
    if (timeoutSec != null && timeoutSec < 1) {
      return ToolResult.failure("timeoutSec must be >= 1, got " + timeoutSec);
    }
    long timeout = timeoutSec == null ? DEFAULT_TIMEOUT_SEC : timeoutSec;
    CommandResult result = sandboxService.exec(handle, cmd, timeout);
    String output = result.stdout();
    if (result.stderr() != null && !result.stderr().isBlank()) {
      output = output + "\n[stderr]\n" + result.stderr();
    }
    output = OutputLimiter.truncate(output);
    if (result.ok()) {
      return ToolResult.success(output);
    }
    return ToolResult.failure(output, "command exited with code " + result.exitCode());
  }
}
