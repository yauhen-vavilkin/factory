package org.folio.factory.devfactory.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class InboxPropertiesTest {

  @Configuration
  @EnableConfigurationProperties(InboxProperties.class)
  static class PropsConfig {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropsConfig.class);

  @Test
  void defaultsApplyWhenUnset() {
    runner.run(ctx -> {
      assertThat(ctx).hasSingleBean(InboxProperties.class);
      assertThat(ctx.getBean(InboxProperties.class))
          .isEqualTo(new InboxProperties(true, Path.of("tasks-inbox"), 5000L));
    });
  }

  @Test
  void overridesBindFromProperties() {
    runner.withPropertyValues(
            "factory.inbox.enabled=false",
            "factory.inbox.dir=/tmp/x",
            "factory.inbox.poll-interval-ms=250")
        .run(ctx -> assertThat(ctx.getBean(InboxProperties.class))
            .isEqualTo(new InboxProperties(false, Path.of("/tmp/x"), 250L)));
  }
}
