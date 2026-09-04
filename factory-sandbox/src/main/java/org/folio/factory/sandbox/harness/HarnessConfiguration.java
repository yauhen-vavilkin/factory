package org.folio.factory.sandbox.harness;

import java.time.Clock;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(HarnessProperties.class)
public class HarnessConfiguration {

  @Bean
  public Clock harnessClock() {
    return Clock.systemUTC();
  }

  @Bean
  public HarnessConfig harnessConfig(HarnessProperties properties) {
    return new HarnessConfig(properties.maxSteps(), properties.maxFormatErrors(),
        properties.jobTimeoutMin(), properties.modelId());
  }

  @Bean
  public ChatModelAdapter chatModelAdapter(ChatModel chatModel, HarnessConfig harnessConfig,
      ToolDispatcher toolDispatcher) {
    return new SpringAiChatModelAdapter(chatModel, harnessConfig, toolDispatcher.toolCallbacks());
  }
}
