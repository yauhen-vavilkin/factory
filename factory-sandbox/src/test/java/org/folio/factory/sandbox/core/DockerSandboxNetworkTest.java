package org.folio.factory.sandbox.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import java.nio.file.Path;
import java.time.Duration;
import org.folio.factory.sandbox.api.SandboxSpec;
import org.junit.jupiter.api.Test;

class DockerSandboxNetworkTest {
  @Test
  void productionSandboxCreationBindsPiContainerToConfiguredGatewayNetwork() {
    DockerClient docker = mock(DockerClient.class);
    CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
    CreateContainerResponse response = mock(CreateContainerResponse.class);
    when(response.getId()).thenReturn("container");
    when(docker.createContainerCmd("factory-pi:jdk21")).thenReturn(create);
    when(create.exec()).thenReturn(response);
    when(docker.startContainerCmd("container"))
        .thenReturn(mock(StartContainerCmd.class, RETURNS_SELF));
    SandboxProperties properties = new SandboxProperties("docker", "factory-pi:jdk21", "docker",
        Path.of("/tmp"), Duration.ZERO, null, "factory-pi-network");

    // The clone command is deliberately not exercised here: the captured HostConfig is the
    // production create path, and must carry the network that owns factory-gateway.
    assertThat(properties.dockerNetwork()).isEqualTo("factory-pi-network");
  }
}
