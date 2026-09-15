package org.folio.factory.sandbox.core;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "factory.sandbox")
public record SandboxProperties(String mode, String image, String dockerHost,
    Path workspaceRoot, Duration workspaceRetention, Path mavenRepository,
    String dockerNetwork, String dependencyDockerNetwork) {

  public SandboxProperties(String mode, String image, String dockerHost, Path workspaceRoot,
      Duration workspaceRetention, Path mavenRepository) {
    this(mode, image, dockerHost, workspaceRoot, workspaceRetention, mavenRepository, null, null);
  }

  public SandboxProperties(String mode, String image, String dockerHost, Path workspaceRoot,
      Duration workspaceRetention, Path mavenRepository, String dockerNetwork) {
    this(mode, image, dockerHost, workspaceRoot, workspaceRetention, mavenRepository,
        dockerNetwork, null);
  }

  @ConstructorBinding
  public SandboxProperties {
    mode = mode == null ? "docker" : mode;
    if (!mode.equals("docker") && !mode.equals("local")) {
      throw new IllegalArgumentException(
          "factory.sandbox.mode must be 'docker' or 'local', got: " + mode);
    }
    image = image == null ? "factory-pi:jdk21" : image;
    dockerHost = dockerHost != null ? dockerHost
        : System.getenv().getOrDefault("DOCKER_HOST", "unix:///var/run/docker.sock");
    workspaceRoot = workspaceRoot != null ? workspaceRoot
        : Path.of(System.getProperty("java.io.tmpdir", "/tmp"), "factory-sandboxes");
    workspaceRetention = workspaceRetention == null ? Duration.ZERO : workspaceRetention;
    mavenRepository = mavenRepository != null ? mavenRepository
        : Path.of(".factory/cache/sandbox-maven-repository");
    dockerNetwork = dockerNetwork == null || dockerNetwork.isBlank() ? null : dockerNetwork;
    dependencyDockerNetwork = dependencyDockerNetwork == null || dependencyDockerNetwork.isBlank()
        ? null : dependencyDockerNetwork;
  }
}
