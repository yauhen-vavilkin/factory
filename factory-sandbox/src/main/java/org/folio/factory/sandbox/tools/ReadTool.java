package org.folio.factory.sandbox.tools;

import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.springframework.stereotype.Component;

@Component
public class ReadTool {

  static final int MAX_LINES = 2000;
  static final long READ_TIMEOUT_SEC = 30L;

  private final SandboxService sandboxService;

  public ReadTool(SandboxService sandboxService) {
    this.sandboxService = sandboxService;
  }

  public ToolResult read(SandboxHandle handle, String path, Integer fromLine, Integer toLine) {
    if (path == null || path.isBlank()) {
      return ToolResult.failure("path is required");
    }
    if (fromLine != null && fromLine < 1) {
      return ToolResult.failure("fromLine must be >= 1, got " + fromLine);
    }
    if (toLine != null && toLine < 1) {
      return ToolResult.failure("toLine must be >= 1, got " + toLine);
    }
    if (fromLine != null && toLine != null && toLine < fromLine) {
      return ToolResult.failure("toLine (" + toLine + ") must be >= fromLine (" + fromLine + ")");
    }
    int from = fromLine == null ? 1 : fromLine;
    int to = toLine == null ? from + MAX_LINES - 1 : Math.min(toLine, from + MAX_LINES - 1);
    String command = "sed -n '" + from + "," + to + "p' " + Shell.quote(path);
    CommandResult result = sandboxService.exec(handle, command, READ_TIMEOUT_SEC);
    if (!result.ok()) {
      return ToolResult.failure("read failed for " + path + ": " + stderrOrStdout(result));
    }
    String output = result.stdout();
    if (lineCount(output) >= MAX_LINES && (toLine == null || toLine - from + 1 > MAX_LINES)) {
      output = output + "\n[read limited to " + MAX_LINES + " lines]";
    }
    return ToolResult.success(OutputLimiter.truncate(output));
  }

  private static String stderrOrStdout(CommandResult result) {
    return result.stderr() != null && !result.stderr().isBlank() ? result.stderr() : result.stdout();
  }

  private static int lineCount(String output) {
    if (output == null || output.isEmpty()) {
      return 0;
    }
    int count = 0;
    for (int i = 0; i < output.length(); i++) {
      if (output.charAt(i) == '\n') {
        count++;
      }
    }
    return output.charAt(output.length() - 1) == '\n' ? count : count + 1;
  }
}
