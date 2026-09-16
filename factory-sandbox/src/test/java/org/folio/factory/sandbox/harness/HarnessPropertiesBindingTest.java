package org.folio.factory.sandbox.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class HarnessPropertiesBindingTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropsConfig.class);

  @Test
  void bindsDefaultsWhenNoPropertiesSet() {
    runner.run(context -> {
      assertThat(context).hasSingleBean(HarnessProperties.class);
      assertThat(context.getBean(HarnessProperties.class))
          .isEqualTo(new HarnessProperties(40, 3, 30L, "claude-sonnet-4-5"));
    });
  }

  @Test
  void bindsConfiguredValues() {
    runner.withPropertyValues(
            "factory.harness.max-steps=7",
            "factory.harness.max-format-errors=1",
            "factory.harness.job-timeout-min=11",
            "factory.harness.model-id=test-model")
        .run(context -> {
          assertThat(context.getBean(HarnessProperties.class))
              .isEqualTo(new HarnessProperties(7, 1, 11L, "test-model"));
        });
  }

  @Test
  void harnessConfigMapsNullPropertiesOntoDefaults() {
    assertThat(new HarnessConfiguration().harnessConfig(new HarnessProperties(null, null, null, null)))
        .isEqualTo(HarnessConfig.defaults());
  }

  @Test
  void harnessConfigMapsConfiguredValuesUnchanged() {
    HarnessConfig config = new HarnessConfiguration().harnessConfig(
        new HarnessProperties(9, 2, 45L, "other-model"));

    assertThat(config).isEqualTo(new HarnessConfig(9, 2, 45L, "other-model"));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(HarnessProperties.class)
  static class PropsConfig {
  }
}
