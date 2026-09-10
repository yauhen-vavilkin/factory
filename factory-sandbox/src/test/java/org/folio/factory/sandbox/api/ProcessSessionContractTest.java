package org.folio.factory.sandbox.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessSessionContractTest {

  @Test
  void sessionRequestKeepsArgvAndLimitsExplicit() {
    ProcessSessionRequest request = new ProcessSessionRequest(
        List.of("/opt/pi/bin/pi", "--mode", "rpc"), "/workspace/repo",
        java.util.Map.of("PI_OFFLINE", "1"), Duration.ofSeconds(30), 1024);

    assertEquals("/workspace/repo", request.cwd());
    assertEquals("--mode", request.argv().get(1));
    assertEquals(1024, request.maxOutputBytes());
    assertTrue(request.timeout().toSeconds() > 0);
  }
}
