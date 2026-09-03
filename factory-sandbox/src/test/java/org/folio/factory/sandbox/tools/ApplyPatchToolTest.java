package org.folio.factory.sandbox.tools;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Base64;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApplyPatchToolTest {

  private static final SandboxHandle HANDLE = new SandboxHandle("sbx-task1", "c1");
  private static final String DIFF = """
      diff --git a/pom.xml b/pom.xml
      --- a/pom.xml
      +++ b/pom.xml
      @@ -1,2 +1,3 @@
       <project>
      +<!-- new -->
       </project>
      """;
  private static final String STAGE_COMMAND =
      "printf '%s' " + Shell.quote(Base64.getEncoder().encodeToString(DIFF.getBytes(UTF_8)))
          + " | base64 -d > /tmp/factory-patch.diff";
  private static final String CHECK_COMMAND = "cd repo && git apply --check /tmp/factory-patch.diff";
  private static final String APPLY_COMMAND = "cd repo && git apply /tmp/factory-patch.diff";

  @Mock
  private SandboxService sandboxService;

  private ApplyPatchTool tool;

  @BeforeEach
  void setUp() {
    tool = new ApplyPatchTool(sandboxService);
  }

  @Test
  void appliesCleanDiff() {
    when(sandboxService.exec(HANDLE, STAGE_COMMAND, ApplyPatchTool.APPLY_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "", "", 5));
    when(sandboxService.exec(HANDLE, CHECK_COMMAND, ApplyPatchTool.APPLY_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "", "", 5));
    when(sandboxService.exec(HANDLE, APPLY_COMMAND, ApplyPatchTool.APPLY_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "", "", 5));

    ToolResult result = tool.apply(HANDLE, DIFF);

    assertTrue(result.ok());
    assertEquals("patch applied", result.output());
  }

  @Test
  void rejectsDiffThatFailsCheckWithoutApplying() {
    when(sandboxService.exec(HANDLE, STAGE_COMMAND, ApplyPatchTool.APPLY_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "", "", 5));
    when(sandboxService.exec(HANDLE, CHECK_COMMAND, ApplyPatchTool.APPLY_TIMEOUT_SEC))
        .thenReturn(new CommandResult(1, "",
            "error: patch failed: pom.xml:1\nerror: pom.xml: patch does not apply", 5));

    ToolResult result = tool.apply(HANDLE, DIFF);

    assertFalse(result.ok());
    assertTrue(result.error().contains("patch does not apply"));
    assertTrue(result.error().contains("NOT applied"));
    verify(sandboxService, never()).exec(HANDLE, APPLY_COMMAND, ApplyPatchTool.APPLY_TIMEOUT_SEC);
  }

  @Test
  void applyFailureAfterSuccessfulCheckIsReported() {
    when(sandboxService.exec(HANDLE, STAGE_COMMAND, ApplyPatchTool.APPLY_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "", "", 5));
    when(sandboxService.exec(HANDLE, CHECK_COMMAND, ApplyPatchTool.APPLY_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "", "", 5));
    when(sandboxService.exec(HANDLE, APPLY_COMMAND, ApplyPatchTool.APPLY_TIMEOUT_SEC))
        .thenReturn(new CommandResult(1, "", "error: unrecognized input", 5));

    ToolResult result = tool.apply(HANDLE, DIFF);

    assertFalse(result.ok());
    assertTrue(result.error().contains("apply failed"));
    assertTrue(result.error().contains("git_diff"));
  }

  @Test
  void emptyDiffFailsWithoutExec() {
    ToolResult result = tool.apply(HANDLE, "  ");

    assertFalse(result.ok());
    assertTrue(result.error().contains("diff is required"));
    verifyNoInteractions(sandboxService);
  }

  @Test
  void stageFailureIsReported() {
    when(sandboxService.exec(HANDLE, STAGE_COMMAND, ApplyPatchTool.APPLY_TIMEOUT_SEC))
        .thenReturn(new CommandResult(1, "", "base64: invalid input", 5));

    ToolResult result = tool.apply(HANDLE, DIFF);

    assertFalse(result.ok());
    assertTrue(result.error().contains("stage"));
  }
}
