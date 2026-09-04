package org.folio.factory.sandbox.harness;

public record ChatMessage(String role, String toolCallId, String toolName, String content) {

  public static final String ROLE_ASSISTANT = "assistant";
  public static final String ROLE_TOOL = "tool";

  public static ChatMessage assistantToolCall(String toolCallId, String toolName, String argumentsJson) {
    return new ChatMessage(ROLE_ASSISTANT, toolCallId, toolName, argumentsJson);
  }

  public static ChatMessage toolResult(String toolCallId, String toolName, String content) {
    return new ChatMessage(ROLE_TOOL, toolCallId, toolName, content);
  }
}
