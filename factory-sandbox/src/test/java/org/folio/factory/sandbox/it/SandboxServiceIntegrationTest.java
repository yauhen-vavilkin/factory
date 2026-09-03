package org.folio.factory.sandbox.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import java.io.IOException;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxSpec;
import org.folio.factory.sandbox.core.DockerSandboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SandboxServiceIntegrationTest {

  private static final SandboxSpec SPEC =
      new SandboxSpec("it-check", "https://github.com/yauhen-vavilkin/factory.git", "main", "task/it-check");

  private DockerHttpClient httpClient;
  private DockerClient dockerClient;
  private DockerSandboxService service;

  @BeforeEach
  void setUp() {
    DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
        .withDockerHost(System.getenv().getOrDefault("DOCKER_HOST", "unix:///var/run/docker.sock"))
        .build();
    httpClient = new ZerodepDockerHttpClient.Builder()
        .dockerHost(config.getDockerHost())
        .sslConfig(config.getSSLConfig())
        .build();
    dockerClient = DockerClientImpl.getInstance(config, httpClient);
    service = new DockerSandboxService(dockerClient);
  }

  @AfterEach
  void tearDown() throws IOException {
    if (httpClient != null) {
      httpClient.close();
    }
  }

  @Test
  void createExecAndTeardownAgainstRealDocker() {
    SandboxHandle handle = null;
    try {
      handle = service.create(SPEC);

      assertNotNull(handle);
      assertFalse(handle.containerId().isBlank());

      CommandResult pwd = service.exec(handle, "pwd", 60L);
      assertTrue(pwd.ok());
      assertEquals("/workspace", pwd.stdout().trim());

      CommandResult gitLog = service.exec(handle, "cd repo && git log --oneline -1", 60L);
      assertTrue(gitLog.ok());
      assertFalse(gitLog.stdout().isBlank());
    } finally {
      if (handle != null) {
        service.teardown(handle);
      }
    }

    SandboxHandle removed = handle;
    assertNotNull(removed);
    assertThrows(NotFoundException.class,
        () -> dockerClient.inspectContainerCmd(removed.containerId()).exec());
  }
}
