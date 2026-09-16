package org.folio.factory.app;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Fails fast when a live chat model is selected but the API key is blank:
 * every live call would fail with 401 (authentication parameter not received).
 */
@Configuration(proxyBeanMethods = false)
public class AnthropicApiKeyGuard {

  @Bean
  static BeanFactoryPostProcessor anthropicApiKeyGuardCheck() {
    return beanFactory -> {
      Environment env = beanFactory.getBean(Environment.class);
      String mode = env.getProperty("factory.mode", "live");
      String chatModel = env.getProperty("spring.ai.model.chat", "anthropic");
      String apiKey = env.getProperty("spring.ai.anthropic.api-key", "");
      String sandboxMode = env.getProperty("factory.sandbox.mode", "docker");
      if ("offline".equals(mode) && !"none".equals(chatModel)) {
        throw new IllegalStateException("factory.mode=offline requires spring.ai.model.chat=none; "
            + "offline mode must use only the scripted ChatModel");
      }
      if ("live".equals(mode) && "local".equals(sandboxMode)) {
        throw new IllegalStateException("factory.mode=live cannot use factory.sandbox.mode=local");
      }
      if ("live".equals(mode) && "none".equals(chatModel)) {
        throw new IllegalStateException("factory.mode=live requires an explicit provider ChatModel; "
            + "use the offline profile for the scripted model");
      }
      if (!"none".equals(chatModel) && apiKey.isBlank()) {
        throw new IllegalStateException("ANTHROPIC_API_KEY is blank but spring.ai.model.chat="
            + chatModel + ": every live call would fail with 401 (authentication parameter not received). "
            + "Provide "
            + "ANTHROPIC_API_KEY in the environment or run with spring.ai.model.chat=none.");
      }
    };
  }
}
