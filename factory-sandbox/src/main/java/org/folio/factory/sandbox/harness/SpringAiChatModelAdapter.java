package org.folio.factory.sandbox.harness;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

public class SpringAiChatModelAdapter implements ChatModelAdapter {

  private static final int MAX_TOKENS = 8192;

  private final ChatModel chatModel;
  private final HarnessConfig config;
  private final List<ToolCallback> toolCallbacks;

  public SpringAiChatModelAdapter(ChatModel chatModel, HarnessConfig config,
      List<ToolCallback> toolCallbacks) {
    this.chatModel = chatModel;
    this.config = config;
    this.toolCallbacks = List.copyOf(toolCallbacks);
  }

  @Override
  public ModelReply reply(String systemPrompt, String taskGoal, List<ChatMessage> history) {
    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(systemPrompt));
    messages.add(new UserMessage(taskGoal));
    for (ChatMessage message : history) {
      if (ChatMessage.ROLE_ASSISTANT.equals(message.role())) {
        messages.add(AssistantMessage.builder()
            .toolCalls(List.of(new AssistantMessage.ToolCall(
                message.toolCallId(), "function", message.toolName(), message.content())))
            .build());
      } else {
        messages.add(ToolResponseMessage.builder()
            .responses(List.of(new ToolResponseMessage.ToolResponse(
                message.toolCallId(), message.toolName(), message.content())))
            .build());
      }
    }
    AnthropicChatOptions options = AnthropicChatOptions.builder()
        .model(config.modelId())
        .maxTokens(MAX_TOKENS)
        .toolCallbacks(toolCallbacks)
        .build();
    ChatResponse response = chatModel.call(new Prompt(messages, options));
    ChatResponseMetadata metadata = response.getMetadata();
    Usage usage = metadata == null ? null : metadata.getUsage();
    TokenUsage tokenUsage = new TokenUsage(
        orZero(usage == null ? null : usage.getPromptTokens()),
        orZero(usage == null ? null : usage.getCompletionTokens()));
    AssistantMessage assistant = response.getResult().getOutput();
    if (assistant.hasToolCalls()) {
      List<ToolCall> toolCalls = assistant.getToolCalls().stream()
          .map(toolCall -> new ToolCall(toolCall.id(), toolCall.name(), toolCall.arguments()))
          .toList();
      return ModelReply.toolCalls(toolCalls, tokenUsage);
    }
    return ModelReply.text(assistant.getText(), tokenUsage);
  }

  private static long orZero(Integer tokens) {
    return tokens == null ? 0L : tokens;
  }
}
