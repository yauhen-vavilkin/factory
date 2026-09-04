package org.folio.factory.sandbox.harness;

public record ModelReply(String text, ToolCall toolCall) {

  public static ModelReply text(String text) {
    return new ModelReply(text, null);
  }

  public static ModelReply toolCall(ToolCall toolCall) {
    return new ModelReply(null, toolCall);
  }

  public boolean isToolCall() {
    return toolCall != null;
  }
}
