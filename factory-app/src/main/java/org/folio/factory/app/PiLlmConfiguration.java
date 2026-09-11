package org.folio.factory.app;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Supplies the common Spring AI infrastructure without enabling a provider in
 * Pi mode. Coding is performed by the isolated Pi process through the fixed
 * gateway; an accidental Flow A call must fail instead of making an implicit
 * provider request.
 */
@Configuration(proxyBeanMethods = false)
@Profile("pi")
public class PiLlmConfiguration {

  @Bean
  PiNoopChatModel piNoopChatModel() {
    return new PiNoopChatModel();
  }

  @Bean
  ChatClient.Builder chatClientBuilder(PiNoopChatModel chatModel) {
    return ChatClient.builder(chatModel);
  }

  static final class PiNoopChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
      throw new IllegalStateException(
          "Pi mode does not use Spring AI ChatClient; coding must run through the Pi gateway");
    }
  }
}
