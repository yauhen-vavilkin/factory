package org.folio.factory.sandbox.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
class GitDiffToolTest {

  private static final SandboxHandle HANDLE = new SandboxHandle("sbx-task1", "c1");
  private static final String STATUS_COMMAND = "cd repo && git status --porcelain";
  private static final String DIFF_COMMAND = "cd repo && git diff";
  private static final String IDENTITY_COMMAND = "cd repo && idx=$(mktemp) && { "
      + "GIT_INDEX_FILE=\"$idx\" git read-tree HEAD"
      + " && GIT_INDEX_FILE=\"$idx\" git add -A"
      + " && GIT_INDEX_FILE=\"$idx\" git write-tree;"
      + " rc=$?; rm -f \"$idx\"; exit \"$rc\"; }";

  @Mock
  private SandboxService sandboxService;

  private GitDiffTool tool;

  @BeforeEach
  void setUp() {
    tool = new GitDiffTool(sandboxService);
  }

  @Test
  void returnsStatusAndDiff() {
    when(sandboxService.exec(HANDLE, STATUS_COMMAND, GitDiffTool.DIFF_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, " M pom.xml\n", "", 5));
    when(sandboxService.exec(HANDLE, DIFF_COMMAND, GitDiffTool.DIFF_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "diff --git a/pom.xml b/pom.xml\n--- a/pom.xml\n", "", 5));

    ToolResult result = tool.diff(HANDLE);

    assertTrue(result.ok());
    assertEquals("[status]\n M pom.xml\n\n[diff]\ndiff --git a/pom.xml b/pom.xml\n--- a/pom.xml",
        result.output());
  }

  @Test
  void cleanTreeReportsNoChanges() {
    when(sandboxService.exec(HANDLE, STATUS_COMMAND, GitDiffTool.DIFF_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "", "", 5));
    when(sandboxService.exec(HANDLE, DIFF_COMMAND, GitDiffTool.DIFF_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "", "", 5));

    ToolResult result = tool.diff(HANDLE);

    assertTrue(result.ok());
    assertTrue(result.output().contains("(no changes)"));
  }

  @Test
  void statusFailureFails() {
    when(sandboxService.exec(HANDLE, STATUS_COMMAND, GitDiffTool.DIFF_TIMEOUT_SEC))
        .thenReturn(new CommandResult(128, "", "fatal: not a git repository", 5));

    ToolResult result = tool.diff(HANDLE);

    assertFalse(result.ok());
    assertTrue(result.error().contains("git status failed"));
    assertTrue(result.error().contains("not a git repository"));
  }

  @Test
  void diffFailureFails() {
    when(sandboxService.exec(HANDLE, STATUS_COMMAND, GitDiffTool.DIFF_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "", "", 5));
    when(sandboxService.exec(HANDLE, DIFF_COMMAND, GitDiffTool.DIFF_TIMEOUT_SEC))
        .thenReturn(new CommandResult(128, "", "fatal: bad object", 5));

    ToolResult result = tool.diff(HANDLE);

    assertFalse(result.ok());
    assertTrue(result.error().contains("git diff failed"));
    assertTrue(result.error().contains("bad object"));
  }

  /**
   * T24 B1: the content identity command builds an isolated scratch index
   * (GIT_INDEX_FILE), stages the entire worktree onto it and prints the
   * resulting tree id — blob-content-addressed, never truncated.
   */
  @Test
  void worktreeIdentityRunsIsolatedScratchIndexWriteTree() {
    when(sandboxService.exec(HANDLE, IDENTITY_COMMAND, GitDiffTool.DIFF_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "0123456789abcdef0123456789abcdef01234567\n", "", 5));

    ToolResult result = tool.worktreeIdentity(HANDLE);

    assertTrue(result.ok());
    assertEquals("0123456789abcdef0123456789abcdef01234567", result.output());
  }

  @Test
  void worktreeIdentityFailureFails() {
    when(sandboxService.exec(HANDLE, IDENTITY_COMMAND, GitDiffTool.DIFF_TIMEOUT_SEC))
        .thenReturn(new CommandResult(128, "", "fatal: not a git repository", 5));

    ToolResult result = tool.worktreeIdentity(HANDLE);

    assertFalse(result.ok());
    assertTrue(result.error().contains("git write-tree failed"));
    assertTrue(result.error().contains("not a git repository"));
  }

  @Test
  void worktreeIdentityRejectsNonTreeOutput() {
    when(sandboxService.exec(HANDLE, IDENTITY_COMMAND, GitDiffTool.DIFF_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, "not a tree id\n", "", 5));

    ToolResult result = tool.worktreeIdentity(HANDLE);

    assertFalse(result.ok());
    assertTrue(result.error().contains("did not print a tree id"));
  }
}
