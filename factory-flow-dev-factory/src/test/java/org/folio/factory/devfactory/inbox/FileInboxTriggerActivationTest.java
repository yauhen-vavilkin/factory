package org.folio.factory.devfactory.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.devfactory.resolution.TaskResolutionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class FileInboxTriggerActivationTest {

  @Configuration
  @EnableConfigurationProperties(InboxProperties.class)
  static class InboxTriggerPropsConfig {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(InboxTriggerPropsConfig.class, FileInboxTrigger.class)
          .withBean(PipelineRouter.class, () -> org.mockito.Mockito.mock(PipelineRouter.class))
          .withBean(TaskResolutionService.class,
              () -> org.mockito.Mockito.mock(TaskResolutionService.class))
          .withBean(InboxTaskFileParser.class, InboxTaskFileParser::new);

  @Test
  void triggerOnByDefault() {
    runner.run(context -> assertThat(context).hasSingleBean(FileInboxTrigger.class));
  }

  @Test
  void killSwitchOff() {
    runner.withPropertyValues("factory.inbox.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(FileInboxTrigger.class));
  }
}
