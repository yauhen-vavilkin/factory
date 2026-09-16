package org.folio.factory.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;

import org.folio.factory.core.engine.EngineProperties;
import org.folio.factory.devfactory.inbox.InboxProperties;
import org.folio.factory.sandbox.core.SandboxProperties;
import org.folio.factory.sandbox.harness.HarnessProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Frozen-config contract, asserted against the REAL classpath
 * application.yaml (loaded via ConfigDataApplicationContextInitializer, the
 * same config-data path a booting app uses). Environment-level key assertions
 * make a missing or misspelled yaml key fail even when the frozen value equals
 * the code default; bean-level assertions prove the records bind those values.
 */
class ApplicationYamlDefaultsTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .withUserConfiguration(PropsConfig.class);

  @Test
  void pinsEngineLeaseTimeoutAboveHarnessJobTimeout() {
    runner.run(context -> {
      assertThat(context).hasSingleBean(EngineProperties.class);
      assertThat(context.getBean(EngineProperties.class).leaseTimeoutSeconds())
          .isEqualTo(2700L);
      assertThat(context.getEnvironment().getProperty("factory.engine.lease-timeout-seconds"))
          .isEqualTo("2700");
      assertThat(context.getBean(EngineProperties.class).maxConcurrentExecutions()).isEqualTo(1);
      assertThat(context.getBean(EngineProperties.class).batchSize()).isEqualTo(1);
      assertThat(context.getBean(EngineProperties.class).workerThreads()).isEqualTo(1);
    });
  }

  @Test
  void freezesHarnessProperties() {
    runner.run(context -> {
      assertThat(context).hasSingleBean(HarnessProperties.class);
      assertThat(context.getBean(HarnessProperties.class))
          .isEqualTo(new HarnessProperties(40, 3, 30L,
              System.getenv().getOrDefault("FACTORY_HARNESS_MODEL", "claude-sonnet-4-5")));
      Environment env = context.getEnvironment();
      assertThat(env.getProperty("factory.harness.max-steps")).isEqualTo("40");
      assertThat(env.getProperty("factory.harness.max-format-errors")).isEqualTo("3");
      assertThat(env.getProperty("factory.harness.job-timeout-min")).isEqualTo("30");
      assertThat(env.getProperty("factory.harness.model-id"))
          .isEqualTo(System.getenv().getOrDefault("FACTORY_HARNESS_MODEL", "claude-sonnet-4-5"));
    });
  }

  @Test
  void freezesSandboxProperties() {
    runner.run(context -> {
      assertThat(context).hasSingleBean(SandboxProperties.class);
      SandboxProperties sandbox = context.getBean(SandboxProperties.class);
      Environment env = context.getEnvironment();
      assertThat(sandbox.mode()).isEqualTo("docker");
      assertThat(sandbox.image()).isEqualTo("factory-pi:jdk21");
      assertThat(sandbox.workspaceRetention()).isEqualTo(Duration.ZERO);
      assertThat(sandbox.workspaceRoot())
          .isEqualTo(Path.of(".factory/data/sandboxes"));
      assertThat(sandbox.dockerHost())
          .isEqualTo(System.getenv().getOrDefault("DOCKER_HOST", "unix:///var/run/docker.sock"));
      assertThat(sandbox.mavenRepository())
          .isEqualTo(Path.of(System.getenv().getOrDefault("FACTORY_SANDBOX_MAVEN_REPOSITORY",
              ".factory/cache/sandbox-maven-repository")));
      assertThat(env.getProperty("factory.sandbox.mode"))
          .isEqualTo(System.getenv().getOrDefault("FACTORY_SANDBOX_MODE", "docker"));
      assertThat(env.getProperty("factory.sandbox.image"))
          .isEqualTo(System.getenv().getOrDefault("FACTORY_SANDBOX_IMAGE", "factory-pi:jdk21"));
      assertThat(env.getProperty("factory.sandbox.docker-host"))
          .isEqualTo(System.getenv().getOrDefault("DOCKER_HOST", "unix:///var/run/docker.sock"));
      assertThat(env.getProperty("factory.sandbox.workspace-root"))
          .isEqualTo(".factory/data/sandboxes");
      assertThat(env.getProperty("factory.sandbox.workspace-retention")).isEqualTo("0s");
      assertThat(env.getProperty("factory.sandbox.maven-repository"))
          .isEqualTo(System.getenv().getOrDefault("FACTORY_SANDBOX_MAVEN_REPOSITORY",
              ".factory/cache/sandbox-maven-repository"));
    });
  }

  /**
   * Factory's host build must never trust a Maven repository that sandboxes can
   * populate: the host wrapper's local repository and the sandbox repository
   * default are different directories.
   */
  @Test
  void hostMavenRepositoryIsSeparateFromTheSandboxRepository() throws Exception {
    String hostConfig = java.nio.file.Files.readString(Path.of("../.mvn/maven.config"));
    assertThat(hostConfig).contains("-Dmaven.repo.local=.factory/cache/host-maven-repository");
    runner.run(context -> {
      Path sandbox = context.getBean(SandboxProperties.class).mavenRepository().normalize();
      assertThat(sandbox).isNotEqualTo(Path.of(".factory/cache/host-maven-repository"));
      assertThat(hostConfig).doesNotContain(sandbox.toString());
    });
  }

  @Test
  void freezesLlmBaseUrl() {
    runner.run(context -> {
      Environment env = context.getEnvironment();
      assertThat(env.getProperty("spring.ai.anthropic.base-url"))
          .isEqualTo(System.getenv().getOrDefault("FACTORY_LLM_BASE_URL",
              "https://api.anthropic.com"));
    });
  }

  /**
   * A direct start (no Spring profile, no FACTORY_EXTERNAL_WRITES_ENABLED)
   * must never enable external connector writes.
   */
  @Test
  void externalWritesAreOffWithoutAnExplicitOptIn() {
    runner.run(context -> assertThat(context.getEnvironment()
        .getProperty("factory.connectors.external-writes-enabled"))
        .isEqualTo(System.getenv().getOrDefault("FACTORY_EXTERNAL_WRITES_ENABLED", "false")));
  }

  // DELTA 2: the fourth test, appended after freezesSandboxProperties:
  @Test
  void freezesInboxProperties() {
    runner.run(context -> {
      assertThat(context).hasSingleBean(InboxProperties.class);
      assertThat(context.getBean(InboxProperties.class))
          .isEqualTo(new InboxProperties(true, Path.of(".factory/data/inbox"), 5000L));
      Environment env = context.getEnvironment();
      assertThat(env.getProperty("factory.inbox.dir"))
          .isEqualTo(System.getenv().getOrDefault("FACTORY_INBOX_DIR", ".factory/data/inbox"));
      assertThat(env.getProperty("factory.inbox.enabled"))
          .isEqualTo(System.getenv().getOrDefault("FACTORY_INBOX_ENABLED", "true"));
      assertThat(env.getProperty("factory.inbox.poll-interval-ms"))
          .isEqualTo(System.getenv().getOrDefault("FACTORY_INBOX_POLL_INTERVAL_MS", "5000"));
    });
  }

  @Configuration(proxyBeanMethods = false)
  // DELTA 1: InboxProperties.class appended to this list
  @EnableConfigurationProperties({EngineProperties.class, HarnessProperties.class,
      SandboxProperties.class, InboxProperties.class})
  static class PropsConfig {
  }
}
