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
    });
  }

  @Test
  void freezesHarnessProperties() {
    runner.run(context -> {
      assertThat(context).hasSingleBean(HarnessProperties.class);
      assertThat(context.getBean(HarnessProperties.class))
          .isEqualTo(new HarnessProperties(40, 3, 30L, "glm-5.3-flash"));
      Environment env = context.getEnvironment();
      assertThat(env.getProperty("factory.harness.max-steps")).isEqualTo("40");
      assertThat(env.getProperty("factory.harness.max-format-errors")).isEqualTo("3");
      assertThat(env.getProperty("factory.harness.job-timeout-min")).isEqualTo("30");
      assertThat(env.getProperty("factory.harness.model-id"))
          .isEqualTo(System.getenv().getOrDefault("FACTORY_HARNESS_MODEL", "glm-5.3-flash"));
    });
  }

  @Test
  void freezesSandboxProperties() {
    runner.run(context -> {
      assertThat(context).hasSingleBean(SandboxProperties.class);
      SandboxProperties sandbox = context.getBean(SandboxProperties.class);
      Environment env = context.getEnvironment();
      assertThat(sandbox.mode()).isEqualTo("docker");
      assertThat(sandbox.image()).isEqualTo("maven:3.9-eclipse-temurin-21");
      assertThat(sandbox.workspaceRetention()).isEqualTo(Duration.ZERO);
      assertThat(sandbox.workspaceRoot())
          .isEqualTo(Path.of(System.getProperty("java.io.tmpdir"), "factory-sandboxes"));
      assertThat(sandbox.dockerHost())
          .isEqualTo(System.getenv().getOrDefault("DOCKER_HOST", "unix:///var/run/docker.sock"));
      assertThat(sandbox.mavenCacheVolume())
          .isEqualTo(System.getenv().getOrDefault("FACTORY_SANDBOX_MAVEN_CACHE_VOLUME",
              "factory-m2-cache"));
      assertThat(env.getProperty("factory.sandbox.mode"))
          .isEqualTo(System.getenv().getOrDefault("FACTORY_SANDBOX_MODE", "docker"));
      assertThat(env.getProperty("factory.sandbox.image"))
          .isEqualTo("maven:3.9-eclipse-temurin-21");
      assertThat(env.getProperty("factory.sandbox.docker-host"))
          .isEqualTo(System.getenv().getOrDefault("DOCKER_HOST", "unix:///var/run/docker.sock"));
      assertThat(env.getProperty("factory.sandbox.workspace-root"))
          .isEqualTo(System.getProperty("java.io.tmpdir") + "/factory-sandboxes");
      assertThat(env.getProperty("factory.sandbox.workspace-retention")).isEqualTo("0s");
      assertThat(env.getProperty("factory.sandbox.maven-cache-volume"))
          .isEqualTo(System.getenv().getOrDefault("FACTORY_SANDBOX_MAVEN_CACHE_VOLUME",
              "factory-m2-cache"));
    });
  }

  @Test
  void freezesLlmBaseUrl() {
    runner.run(context -> {
      Environment env = context.getEnvironment();
      assertThat(env.getProperty("spring.ai.anthropic.base-url"))
          .isEqualTo(System.getenv().getOrDefault("FACTORY_LLM_BASE_URL",
              "https://api.z.ai/api/anthropic"));
    });
  }

  // DELTA 2: the fourth test, appended after freezesSandboxProperties:
  @Test
  void freezesInboxProperties() {
    runner.run(context -> {
      assertThat(context).hasSingleBean(InboxProperties.class);
      assertThat(context.getBean(InboxProperties.class))
          .isEqualTo(new InboxProperties(true, Path.of("tasks-inbox"), 5000L));
      Environment env = context.getEnvironment();
      assertThat(env.getProperty("factory.inbox.dir"))
          .isEqualTo(System.getenv().getOrDefault("FACTORY_INBOX_DIR", "tasks-inbox"));
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
