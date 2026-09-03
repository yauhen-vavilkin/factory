package org.folio.factory.sandbox.tools;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecToolTest {

  private static final SandboxHandle HANDLE = new SandboxHandle("sbx-task1", "c1");

  @Mock
  private SandboxService sandboxService;

  private ExecTool tool;

  @BeforeEach
  void setUp() {
    tool = new ExecTool(sandboxService);
  }

  @Test
  void runsCommandWithDefaultTimeout() {
    when(sandboxService.exec(HANDLE, "ls -la", ExecTool.DEFAULT_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "pom.xml", "", 12));

    ToolResult result = tool.run(HANDLE, "ls -la", null);

    assertTrue(result.ok());
    assertEquals("pom.xml", result.output());
  }

  @Test
  void usesProvidedTimeout() {
    when(sandboxService.exec(HANDLE, "sleep 1", 45L))
        .thenReturn(new CommandResult(0, "", "", 1000));

    ToolResult result = tool.run(HANDLE, "sleep 1", 45L);

    assertTrue(result.ok());
  }

  @Test
  void nonZeroExitCarriesOutputAndError() {
    when(sandboxService.exec(HANDLE, "mvn -v", ExecTool.DEFAULT_TIMEOUT_SEC))
        .thenReturn(new CommandResult(1, "building", "failure reason", 10));

    ToolResult result = tool.run(HANDLE, "mvn -v", null);

    assertFalse(result.ok());
    assertTrue(result.output().contains("building"));
    assertTrue(result.output().contains("[stderr]"));
    assertTrue(result.output().contains("failure reason"));
    assertEquals("command exited with code 1", result.error());
  }

  @Test
  void longOutputTruncated() {
    when(sandboxService.exec(HANDLE, "cat big.log", ExecTool.DEFAULT_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "x".repeat(60_000), "", 10));

    ToolResult result = tool.run(HANDLE, "cat big.log", null);

    assertTrue(result.ok());
    assertTrue(result.output().getBytes(UTF_8).length <= OutputLimiter.MAX_OUTPUT_BYTES);
    assertTrue(result.output().contains("[output truncated:"));
  }

  @Test
  void blankCommandFails() {
    ToolResult result = tool.run(HANDLE, " ", null);

    assertFalse(result.ok());
    assertTrue(result.error().contains("cmd is required"));
    verifyNoInteractions(sandboxService);
  }

  @Test
  void zeroTimeoutFails() {
    ToolResult result = tool.run(HANDLE, "ls", 0L);

    assertFalse(result.ok());
    assertTrue(result.error().contains("timeoutSec"));
    verifyNoInteractions(sandboxService);
  }
}
