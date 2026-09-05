package org.folio.factory.sandbox.harness;

import java.util.List;

public record ModelReply(String text, List<ToolCall> toolCalls, TokenUsage usage) {

  public static ModelReply text(String text) {
    return new ModelReply(text, List.of(), null);
  }

  public static ModelReply text(String text, TokenUsage usage) {
    return new ModelReply(text, List.of(), usage);
  }

  public static ModelReply toolCall(ToolCall toolCall) {
    return new ModelReply(null, List.of(toolCall), null);
  }

  public static ModelReply toolCalls(List<ToolCall> toolCalls, TokenUsage usage) {
    return new ModelReply(null, toolCalls, usage);
  }

  public boolean isToolCall() {
    return toolCalls != null && !toolCalls.isEmpty();
  }

  public ToolCall toolCall() {
    return toolCalls.get(0);
  }
}
