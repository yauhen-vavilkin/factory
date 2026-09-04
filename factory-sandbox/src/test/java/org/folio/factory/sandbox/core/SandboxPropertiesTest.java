package org.folio.factory.sandbox.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SandboxPropertiesTest {

  @Test
  void defaultsAppliedWhenAllNull() {
    SandboxProperties properties = new SandboxProperties(null, null, null, null, null);

    assertThat(properties.mode()).isEqualTo("docker");
    assertThat(properties.image()).isEqualTo("maven:3.9-eclipse-temurin-21");
    assertThat(properties.dockerHost()).isEqualTo(
        System.getenv().getOrDefault("DOCKER_HOST", "unix:///var/run/docker.sock"));
    assertThat(properties.workspaceRoot()).isEqualTo(
        Path.of(System.getProperty("java.io.tmpdir", "/tmp"), "factory-sandboxes"));
    assertThat(properties.workspaceRetention()).isEqualTo(Duration.ZERO);
  }

  @Test
  void explicitValuesPreserved() {
    Path root = Path.of("/tmp/sandbox-root");
    SandboxProperties properties = new SandboxProperties("local", "custom-image:1",
        "tcp://docker.internal:2375", root, Duration.ofSeconds(90));

    assertThat(properties.mode()).isEqualTo("local");
    assertThat(properties.image()).isEqualTo("custom-image:1");
    assertThat(properties.dockerHost()).isEqualTo("tcp://docker.internal:2375");
    assertThat(properties.workspaceRoot()).isEqualTo(root);
    assertThat(properties.workspaceRetention()).isEqualTo(Duration.ofSeconds(90));
  }

  @Test
  void unknownModeRejected() {
    assertThatThrownBy(() -> new SandboxProperties("xyz", null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("factory.sandbox.mode")
        .hasMessageContaining("xyz");
  }
}
