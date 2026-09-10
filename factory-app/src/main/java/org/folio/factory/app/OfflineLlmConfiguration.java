package org.folio.factory.app;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Explicit, deterministic model used only by the offline profile. */
@Configuration(proxyBeanMethods = false)
@Profile("offline")
public class OfflineLlmConfiguration {

  @Bean
  OfflineScriptedChatModel offlineScriptedChatModel() {
    return new OfflineScriptedChatModel();
  }

  @Bean
  ChatClient.Builder chatClientBuilder(OfflineScriptedChatModel chatModel) {
    return ChatClient.builder(chatModel);
  }

  /** No HTTP client or provider delegate exists on this offline call path. */
  public static final class OfflineScriptedChatModel implements ChatModel {

    private final AtomicLong callCount = new AtomicLong();

    @Override
    public ChatResponse call(Prompt prompt) {
      callCount.incrementAndGet();
      String text = prompt.getContents();
      String response;
      if (text.contains("Triage Agent")) {
        response = """
            {"issueKey":"OFFLINE-1","summary":"Offline scripted issue",\
            "components":["factory"],"endpoints":[],"riskLevel":"low",\
            "ambiguities":[],"analysis":"## Analysis\\n\\nScripted offline response."}
            """;
      } else if (text.contains("Test Specification Agent")) {
        response = """
            {"issueKey":"OFFLINE-1","cases":[{"id":"TC-OFFLINE-1",\
            "title":"Scripted offline check","priority":"medium",\
            "type":"manual","targetEndpoint":"n/a","preconditions":[],\
            "steps":["Run the documented offline workflow"],\
            "expected":"Factory is ready","acceptanceCriteriaRef":"M0"}]}
            """;
      } else if (text.contains("Test Automation Agent")) {
        response = """
            {"framework":"karate","files":[]}
            """;
      } else {
        response = "Offline scripted model: no scripted code change is defined for this prompt.";
      }
      return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
    }

    public long callCount() {
      return callCount.get();
    }
  }
}
