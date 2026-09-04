package org.folio.factory.sandbox.harness;

import org.folio.factory.sandbox.tools.ToolResult;

public record ToolExecution(boolean formatError, ToolResult result) {

  public static ToolExecution invalidToolCall(String hint) {
    return new ToolExecution(true, ToolResult.failure(hint));
  }

  public static ToolExecution executed(ToolResult result) {
    return new ToolExecution(false, result);
  }
}
