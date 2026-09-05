package org.folio.factory.app;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Fails fast when a live chat model is selected but the API key is blank:
 * every live call would fail with 401 (z.ai error code 1001,
 * "Authentication parameter not received in Header").
 */
@Configuration(proxyBeanMethods = false)
public class AnthropicApiKeyGuard {

  @Bean
  static BeanFactoryPostProcessor anthropicApiKeyGuardCheck() {
    return beanFactory -> {
      Environment env = beanFactory.getBean(Environment.class);
      String chatModel = env.getProperty("spring.ai.model.chat", "anthropic");
      String apiKey = env.getProperty("spring.ai.anthropic.api-key", "");
      if (!"none".equals(chatModel) && apiKey.isBlank()) {
        throw new IllegalStateException("ANTHROPIC_API_KEY is blank but spring.ai.model.chat="
            + chatModel + ": every live call would fail with 401 (z.ai error code 1001, "
            + "'Authentication parameter not received in Header'). Provide "
            + "ANTHROPIC_API_KEY in the environment or run with spring.ai.model.chat=none.");
      }
    };
  }
}
