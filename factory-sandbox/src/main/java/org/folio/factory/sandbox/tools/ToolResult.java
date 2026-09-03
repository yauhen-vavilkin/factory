package org.folio.factory.sandbox.tools;

public record ToolResult(boolean ok, String output, String error) {

  public static ToolResult success(String output) {
    return new ToolResult(true, output, null);
  }

  public static ToolResult failure(String error) {
    return new ToolResult(false, "", error);
  }

  public static ToolResult failure(String output, String error) {
    return new ToolResult(false, output, error);
  }
}
