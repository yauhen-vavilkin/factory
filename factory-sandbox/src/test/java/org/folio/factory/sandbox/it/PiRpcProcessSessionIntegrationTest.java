package org.folio.factory.sandbox.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.folio.factory.sandbox.api.ProcessSession;
import org.folio.factory.sandbox.api.ProcessSessionRequest;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.core.DockerProcessSessionFactory;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Tag("integration")
class PiRpcProcessSessionIntegrationTest {
  @Test
  void realPinnedPiAnswersRpcWithoutTtyThroughProductionSession() throws Exception {
    var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
    DockerHttpClient transport = new ZerodepDockerHttpClient.Builder()
        .dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig()).build();
    try (DockerClient docker = DockerClientImpl.getInstance(config, transport)) {
      String id = docker.createContainerCmd("factory-pi:jdk21").withCmd("sleep", "infinity")
          .withWorkingDir("/workspace").exec().getId();
      docker.startContainerCmd(id).exec();
      try {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ProcessSession session = new DockerProcessSessionFactory(docker).open(
            new SandboxHandle("pi-it", id),
            new ProcessSessionRequest(List.of("/opt/pi/node_modules/.bin/pi", "--mode", "rpc",
                "--no-approve", "--no-extensions", "--no-skills", "--no-context-files"),
                "/workspace", Map.of("HOME", "/home/agent", "PI_CODING_AGENT_DIR", "/state/pi",
                    "PI_OFFLINE", "1", "PI_TELEMETRY", "0"), Duration.ofSeconds(30), 1024 * 1024),
            (stream, bytes, offset, length) -> { if (stream.name().equals("STDOUT")) raw.write(bytes, offset, length); });
        try (session) {
          session.stdin().write("{\"type\":\"get_state\"}\n".getBytes(StandardCharsets.UTF_8));
          session.stdin().flush();
          session.stdin().close();
          // A normal Factory completion stops the whole coding workload after
          // collecting the response; Pi's RPC loop is intentionally long-lived.
          Thread.sleep(500);
          session.killWorkload();
          session.awaitExit().get();
        }
        assertTrue(raw.toString(StandardCharsets.UTF_8).contains("\"command\":\"get_state\""));
      } finally {
        docker.removeContainerCmd(id).withForce(true).exec();
      }
    }
  }
}
