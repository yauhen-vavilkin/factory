package org.folio.factory.sandbox.tools;

import java.util.List;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.springframework.stereotype.Component;

@Component
public class ListTool {

  static final int MAX_ENTRIES = 500;
  static final long LIST_TIMEOUT_SEC = 30L;

  private final SandboxService sandboxService;

  public ListTool(SandboxService sandboxService) {
    this.sandboxService = sandboxService;
  }

  public ToolResult list(SandboxHandle handle, String path, String glob) {
    if (path == null || path.isBlank()) {
      return ToolResult.failure("path is required");
    }
    String command = "find " + Shell.quote(path) + " -type f"
        + (glob == null || glob.isBlank() ? "" : " -name " + Shell.quote(glob));
    CommandResult result = sandboxService.exec(handle, command, LIST_TIMEOUT_SEC);
    if (!result.ok()) {
      return ToolResult.failure("list failed for " + path + ": " + stderrOrStdout(result));
    }
    List<String> entries = result.stdout().lines()
        .filter(line -> !line.isBlank())
        .sorted()
        .toList();
    String output;
    if (entries.size() > MAX_ENTRIES) {
      output = String.join("\n", entries.subList(0, MAX_ENTRIES))
          + "\n[" + MAX_ENTRIES + " entries shown, list truncated]";
    } else {
      output = String.join("\n", entries);
    }
    return ToolResult.success(OutputLimiter.truncate(output));
  }

  private static String stderrOrStdout(CommandResult result) {
    return result.stderr() != null && !result.stderr().isBlank() ? result.stderr() : result.stdout();
  }
}
