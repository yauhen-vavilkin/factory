package org.folio.factory.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AnthropicApiKeyGuardTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(AnthropicApiKeyGuard.class);

  @Test
  void chatModelNoneBootsWithBlankKey() {
    runner.withPropertyValues("spring.ai.model.chat=none")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void blankKeyFailsStartupNamingTheEnvVar() {
    runner.withPropertyValues("spring.ai.anthropic.api-key=")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasMessageContaining("ANTHROPIC_API_KEY")
              .hasMessageContaining("1001");
        });
  }

  @Test
  void presentKeyBoots() {
    runner.withPropertyValues("spring.ai.anthropic.api-key=test-key")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
