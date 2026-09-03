package org.folio.factory.sandbox.tools;

import static java.util.stream.IntStream.rangeClosed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
class ReadToolTest {

  private static final SandboxHandle HANDLE = new SandboxHandle("sbx-task1", "c1");

  @Mock
  private SandboxService sandboxService;

  private ReadTool tool;

  @BeforeEach
  void setUp() {
    tool = new ReadTool(sandboxService);
  }

  @Test
  void readsFileWithoutRange() {
    when(sandboxService.exec(HANDLE, "sed -n '1,2000p' 'repo/pom.xml'", ReadTool.READ_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "<project/>", "", 3));

    ToolResult result = tool.read(HANDLE, "repo/pom.xml", null, null);

    assertTrue(result.ok());
    assertEquals("<project/>", result.output());
    assertNull(result.error());
  }

  @Test
  void readsLineRange() {
    when(sandboxService.exec(HANDLE, "sed -n '10,20p' 'repo/pom.xml'", ReadTool.READ_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "line10\nline11\n", "", 3));

    ToolResult result = tool.read(HANDLE, "repo/pom.xml", 10, 20);

    assertTrue(result.ok());
    assertEquals("line10\nline11\n", result.output());
  }

  @Test
  void capsRequestedRangeAtMaxLines() {
    String file = rangeClosed(1, 2000).mapToObj(i -> "line" + i).collect(Collectors.joining("\n")) + "\n";
    when(sandboxService.exec(HANDLE, "sed -n '1,2000p' 'repo/big.txt'", ReadTool.READ_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, file, "", 3));

    ToolResult result = tool.read(HANDLE, "repo/big.txt", 1, 5000);

    assertTrue(result.ok());
    assertTrue(result.output().endsWith("\n[read limited to 2000 lines]"));
  }

  @Test
  void openEndAddsMarkerWhenLimitReached() {
    String file = rangeClosed(1, 2000).mapToObj(i -> "x").collect(Collectors.joining("\n")) + "\n";
    when(sandboxService.exec(HANDLE, "sed -n '1,2000p' 'repo/big.txt'", ReadTool.READ_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, file, "", 3));

    ToolResult result = tool.read(HANDLE, "repo/big.txt", null, null);

    assertTrue(result.ok());
    assertTrue(result.output().endsWith("\n[read limited to 2000 lines]"));
  }

  @Test
  void fromLineBelowOneFails() {
    ToolResult result = tool.read(HANDLE, "repo/pom.xml", 0, null);

    assertFalse(result.ok());
    assertTrue(result.error().contains("fromLine"));
    verifyNoInteractions(sandboxService);
  }

  @Test
  void toLineBelowOneFails() {
    ToolResult result = tool.read(HANDLE, "repo/pom.xml", null, 0);

    assertFalse(result.ok());
    assertTrue(result.error().contains("toLine"));
    verifyNoInteractions(sandboxService);
  }

  @Test
  void toLineBelowFromLineFails() {
    ToolResult result = tool.read(HANDLE, "repo/pom.xml", 20, 10);

    assertFalse(result.ok());
    assertTrue(result.error().contains("toLine"));
    assertTrue(result.error().contains("fromLine"));
    verifyNoInteractions(sandboxService);
  }

  @Test
  void missingFileFails() {
    when(sandboxService.exec(HANDLE, "sed -n '1,2000p' 'repo/nope.txt'", ReadTool.READ_TIMEOUT_SEC))
        .thenReturn(new CommandResult(2, "",
            "sed: can't read 'repo/nope.txt': No such file or directory", 3));

    ToolResult result = tool.read(HANDLE, "repo/nope.txt", null, null);

    assertFalse(result.ok());
    assertTrue(result.error().contains("No such file or directory"));
  }

  @Test
  void blankPathFails() {
    ToolResult result = tool.read(HANDLE, " ", null, null);

    assertFalse(result.ok());
    assertTrue(result.error().contains("path"));
    verifyNoInteractions(sandboxService);
  }
}
