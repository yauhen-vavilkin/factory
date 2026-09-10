package org.folio.factory.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AnthropicApiKeyGuardTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(AnthropicApiKeyGuard.class);

  @Test
  void chatModelNoneBootsWithBlankKey() {
    runner.withPropertyValues("factory.mode=offline", "spring.ai.model.chat=none")
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

  @Test
  void offlineModeRejectsProviderModel() {
    runner.withPropertyValues("factory.mode=offline", "spring.ai.model.chat=anthropic",
            "spring.ai.anthropic.api-key=test-key")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasMessageContaining("scripted ChatModel");
        });
  }

  @Test
  void liveModeRejectsLocalSandboxBeforeNetworkUse() {
    runner.withPropertyValues("factory.mode=live", "factory.sandbox.mode=local",
            "spring.ai.anthropic.api-key=test-key")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasMessageContaining("cannot use")
              .hasMessageContaining("factory.sandbox.mode=local");
        });
  }

  @Test
  void liveModeRejectsDisabledProviderModel() {
    runner.withPropertyValues("factory.mode=live", "spring.ai.model.chat=none")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasMessageContaining("explicit provider ChatModel");
        });
  }
}
