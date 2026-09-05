package org.folio.factory.sandbox.core;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "factory.sandbox")
public record SandboxProperties(String mode, String image, String dockerHost,
    Path workspaceRoot, Duration workspaceRetention, String mavenCacheVolume) {

  public SandboxProperties {
    mode = mode == null ? "docker" : mode;
    if (!mode.equals("docker") && !mode.equals("local")) {
      throw new IllegalArgumentException(
          "factory.sandbox.mode must be 'docker' or 'local', got: " + mode);
    }
    image = image == null ? "maven:3.9-eclipse-temurin-21" : image;
    dockerHost = dockerHost != null ? dockerHost
        : System.getenv().getOrDefault("DOCKER_HOST", "unix:///var/run/docker.sock");
    workspaceRoot = workspaceRoot != null ? workspaceRoot
        : Path.of(System.getProperty("java.io.tmpdir", "/tmp"), "factory-sandboxes");
    workspaceRetention = workspaceRetention == null ? Duration.ZERO : workspaceRetention;
    mavenCacheVolume = mavenCacheVolume == null || mavenCacheVolume.isBlank()
        ? "factory-m2-cache" : mavenCacheVolume;
  }
}
