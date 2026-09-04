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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
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
    assertEquals("call-1", reply.toolCall().id());
    assertEquals("read", reply.toolCall().name());
    assertEquals("{\"path\":\"repo/pom.xml\"}", reply.toolCall().arguments());
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
    ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
    assertEquals("glm-5.3-flash", options.getModel());
    assertEquals(1, options.getToolCallbacks().size());
    assertSame(toolCallback, options.getToolCallbacks().get(0));
  }

  @Test
  void providerFailurePropagates() {
    when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("provider 500"));

    assertThrows(RuntimeException.class, () -> adapter().reply("system", "task", List.of()));
  }
}
