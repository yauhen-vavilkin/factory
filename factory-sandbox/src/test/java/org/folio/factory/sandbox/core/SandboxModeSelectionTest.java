package org.folio.factory.sandbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import java.time.Clock;
import org.folio.factory.sandbox.api.SandboxService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SandboxModeSelectionTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(SandboxRuntimeConfiguration.class, DockerSandboxService.class);

  @Test
  void defaultModeSelectsDockerImplementation() {
    runner.run(context -> {
      assertThat(context).hasSingleBean(SandboxService.class);
      assertThat(context).hasSingleBean(DockerSandboxService.class);
      assertThat(context.getBean(SandboxService.class)).isInstanceOf(DockerSandboxService.class);
      assertThat(context).hasSingleBean(DockerClient.class);
    });
  }

  @Test
  void dockerModeSelectsDockerImplementation() {
    runner.withPropertyValues("factory.sandbox.mode=docker").run(context -> {
      assertThat(context).hasSingleBean(SandboxService.class);
      assertThat(context).hasSingleBean(DockerSandboxService.class);
      assertThat(context).hasSingleBean(DockerClient.class);
    });
  }

  @Test
  void localModeSelectsLocalImplementation() {
    runner.withBean(Clock.class, Clock::systemUTC)
        .withUserConfiguration(LocalSandboxService.class)
        .withPropertyValues("factory.sandbox.mode=local")
        .run(context -> {
          assertThat(context).hasSingleBean(SandboxService.class);
          assertThat(context.getBean(SandboxService.class)).isInstanceOf(LocalSandboxService.class);
          assertThat(context).doesNotHaveBean(DockerClient.class);
          assertThat(context).doesNotHaveBean(DockerSandboxService.class);
        });
  }
}
