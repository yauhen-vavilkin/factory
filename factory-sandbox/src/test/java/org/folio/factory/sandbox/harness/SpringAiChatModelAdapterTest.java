package org.folio.factory.sandbox.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

@ExtendWith(MockitoExtension.class)
class SpringAiChatModelAdapterTest {

  @Mock
  private ChatModel chatModel;

  @Mock
  private ToolCallback toolCallback;

  private SpringAiChatModelAdapter adapter() {
    return new SpringAiChatModelAdapter(chatModel, HarnessConfig.defaults(), List.of(toolCallback));
  }

  private static ChatResponse responseFor(AssistantMessage assistant) {
    return new ChatResponse(List.of(new Generation(assistant)));
  }

  @Test
  void plainTextResponseBecomesTextReply() {
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(responseFor(AssistantMessage.builder().content("fixed the NPE").build()));

    ModelReply reply = adapter().reply("system", "fix the bug", List.of());

    assertEquals("fixed the NPE", reply.text());
    assertFalse(reply.isToolCall());
  }

  @Test
  void toolCallResponseBecomesToolReply() {
    AssistantMessage assistant = AssistantMessage.builder()
        .toolCalls(List.of(new AssistantMessage.ToolCall(
            "call-1", "function", "read", "{\"path\":\"repo/pom.xml\"}")))
        .build();
    when(chatModel.call(any(Prompt.class))).thenReturn(responseFor(assistant));

    ModelReply reply = adapter().reply("system", "fix the bug", List.of());

    assertTrue(reply.isToolCall());
    assertEquals("call-1", reply.toolCalls().get(0).id());
    assertEquals("read", reply.toolCalls().get(0).name());
    assertEquals("{\"path\":\"repo/pom.xml\"}", reply.toolCalls().get(0).arguments());
  }

  @Test
  void batchOfToolCallsIsReturnedWhole() {
    AssistantMessage assistant = AssistantMessage.builder()
        .toolCalls(List.of(
            new AssistantMessage.ToolCall(
                "call-1", "function", "read", "{\"path\":\"repo/pom.xml\"}"),
            new AssistantMessage.ToolCall(
                "call-2", "function", "list", "{\"path\":\"repo\"}")))
        .build();
    when(chatModel.call(any(Prompt.class))).thenReturn(responseFor(assistant));

    ModelReply reply = adapter().reply("system", "fix the bug", List.of());

    assertTrue(reply.isToolCall());
    assertEquals(2, reply.toolCalls().size());
    assertEquals("call-1", reply.toolCalls().get(0).id());
    assertEquals("read", reply.toolCalls().get(0).name());
    assertEquals("{\"path\":\"repo/pom.xml\"}", reply.toolCalls().get(0).arguments());
    assertEquals("call-2", reply.toolCalls().get(1).id());
    assertEquals("list", reply.toolCalls().get(1).name());
    assertEquals("{\"path\":\"repo\"}", reply.toolCalls().get(1).arguments());
  }

  @Test
  void buildsPromptWithHistoryMessagesOptionsAndCallbacks() {
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(responseFor(AssistantMessage.builder().content("ok").build()));
    List<ChatMessage> history = List.of(
        ChatMessage.assistantToolCall("call-1", "read", "{\"path\":\"repo/pom.xml\"}"),
        ChatMessage.toolResult("call-1", "read", "<project/>"));

    adapter().reply("system prompt", "fix the bug", history);

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(captor.capture());
    Prompt prompt = captor.getValue();
    List<Message> messages = prompt.getInstructions();
    assertEquals(4, messages.size());
    assertEquals("system prompt", ((SystemMessage) messages.get(0)).getText());
    assertEquals("fix the bug", ((UserMessage) messages.get(1)).getText());
    AssistantMessage replayedCall = (AssistantMessage) messages.get(2);
    assertEquals("call-1", replayedCall.getToolCalls().get(0).id());
    assertEquals("read", replayedCall.getToolCalls().get(0).name());
    assertEquals("{\"path\":\"repo/pom.xml\"}", replayedCall.getToolCalls().get(0).arguments());
    ToolResponseMessage replayedResult = (ToolResponseMessage) messages.get(3);
    assertEquals("call-1", replayedResult.getResponses().get(0).id());
    assertEquals("read", replayedResult.getResponses().get(0).name());
    assertEquals("<project/>", replayedResult.getResponses().get(0).responseData());
    assertTrue(prompt.getOptions() instanceof AnthropicChatOptions);
    AnthropicChatOptions options = (AnthropicChatOptions) prompt.getOptions();
    assertEquals("claude-sonnet-4-5", options.getModel());
    assertEquals(8192, options.getMaxTokens().intValue());
    assertEquals(1, options.getToolCallbacks().size());
    assertSame(toolCallback, options.getToolCallbacks().get(0));
  }

  @Test
  void providerFailurePropagates() {
    when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("provider 500"));

    assertThrows(RuntimeException.class, () -> adapter().reply("system", "task", List.of()));
  }

  @Test
  void usageMetadataMapsIntoReply() {
    ChatResponseMetadata metadata = ChatResponseMetadata.builder()
        .usage(new DefaultUsage(11, 7))
        .build();
    when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
        new Generation(AssistantMessage.builder().content("done").build())), metadata));

    ModelReply reply = adapter().reply("system", "fix the bug", List.of());

    assertEquals(new TokenUsage(11, 7), reply.usage());
  }

  @Test
  void usageIsNullSafeWithoutMetadata() {
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(responseFor(AssistantMessage.builder().content("done").build()));

    ModelReply reply = adapter().reply("system", "fix the bug", List.of());

    assertEquals(TokenUsage.ZERO, reply.usage());
  }

  /**
   * T23 R1 empirical pin: Spring AI's {@code ChatResponse.getResult()} on a
   * zero-generation response (observed on the pre-fix code: it returns null,
   * so the unguarded {@code getResult().getOutput()} crashed with a
   * NullPointerException). The adapter must instead surface a dedicated
   * diagnostic so the harness can distinguish an empty model response from a
   * provider failure.
   */
  @Test
  void zeroGenerationResponseSurfacesAsDedicatedDiagnostic() {
    when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));

    EmptyModelResponseException thrown = assertThrows(EmptyModelResponseException.class,
        () -> adapter().reply("system", "fix the bug", List.of()));
    assertTrue(thrown.getMessage().contains("zero generations"));
  }

  @Test
  void nullAssistantOutputSurfacesAsDedicatedDiagnostic() {
    // A generation carrying no assistant output is the other empty shape.
    when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(null))));

    EmptyModelResponseException thrown = assertThrows(EmptyModelResponseException.class,
        () -> adapter().reply("system", "fix the bug", List.of()));
    assertTrue(thrown.getMessage().contains("no assistant output"));
  }
}
