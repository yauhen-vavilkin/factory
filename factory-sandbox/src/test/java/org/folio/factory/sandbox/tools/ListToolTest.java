package org.folio.factory.sandbox.tools;

import static java.util.stream.IntStream.rangeClosed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.stream.Collectors;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ListToolTest {

  private static final SandboxHandle HANDLE = new SandboxHandle("sbx-task1", "c1");

  @Mock
  private SandboxService sandboxService;

  private ListTool tool;

  @BeforeEach
  void setUp() {
    tool = new ListTool(sandboxService);
  }

  @Test
  void listsFilesSorted() {
    when(sandboxService.exec(HANDLE, "find 'repo/src' -type f", ListTool.LIST_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "repo/src/B.java\nrepo/src/A.java\n", "", 5));

    ToolResult result = tool.list(HANDLE, "repo/src", null);

    assertTrue(result.ok());
    assertEquals("repo/src/A.java\nrepo/src/B.java", result.output());
  }

  @Test
  void appliesGlob() {
    when(sandboxService.exec(HANDLE, "find 'repo/src' -type f -name '*.java'", ListTool.LIST_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "repo/src/A.java\n", "", 5));

    ToolResult result = tool.list(HANDLE, "repo/src", "*.java");

    assertTrue(result.ok());
    assertEquals("repo/src/A.java", result.output());
  }

  @Test
  void capsAtFiveHundredEntries() {
    String files = rangeClosed(1, 501).mapToObj(i -> "repo/src/f" + i + ".java")
        .collect(Collectors.joining("\n"));
    when(sandboxService.exec(HANDLE, "find 'repo/src' -type f", ListTool.LIST_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, files, "", 5));

    ToolResult result = tool.list(HANDLE, "repo/src", null);

    assertTrue(result.ok());
    assertTrue(result.output().contains("repo/src/f500.java"));
    assertFalse(result.output().contains("repo/src/f99.java"));
    assertTrue(result.output().endsWith("[500 entries shown, list truncated]"));
  }

  @Test
  void missingDirFails() {
    when(sandboxService.exec(HANDLE, "find 'repo/nope' -type f", ListTool.LIST_TIMEOUT_SEC))
        .thenReturn(new CommandResult(1, "", "find: 'repo/nope': No such file or directory", 5));

    ToolResult result = tool.list(HANDLE, "repo/nope", null);

    assertFalse(result.ok());
    assertTrue(result.error().contains("No such file or directory"));
  }

  @Test
  void blankPathFails() {
    ToolResult result = tool.list(HANDLE, "", null);

    assertFalse(result.ok());
    assertTrue(result.error().contains("path"));
    verifyNoInteractions(sandboxService);
  }
}
