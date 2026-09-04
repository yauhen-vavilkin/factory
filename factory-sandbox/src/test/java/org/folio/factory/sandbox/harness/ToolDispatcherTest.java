package org.folio.factory.sandbox.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.tools.ApplyPatchTool;
import org.folio.factory.sandbox.tools.ExecTool;
import org.folio.factory.sandbox.tools.GitDiffTool;
import org.folio.factory.sandbox.tools.ListTool;
import org.folio.factory.sandbox.tools.ReadTool;
import org.folio.factory.sandbox.tools.TestTool;
import org.folio.factory.sandbox.tools.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolDispatcherTest {

  private static final SandboxHandle HANDLE = new SandboxHandle("sbx-t13", "c1");

  @Mock
  private ReadTool readTool;

  @Mock
  private ListTool listTool;

  @Mock
  private ApplyPatchTool applyPatchTool;

  @Mock
  private ExecTool execTool;

  @Mock
  private GitDiffTool gitDiffTool;

  @Mock
  private TestTool testTool;

  private ToolDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    dispatcher = new ToolDispatcher(readTool, listTool, applyPatchTool, execTool, gitDiffTool, testTool);
  }

  @Test
  void dispatchesReadWithParsedArguments() {
    when(readTool.read(HANDLE, "repo/pom.xml", 10, 20))
        .thenReturn(ToolResult.success("<project/>"));

    ToolExecution execution = dispatcher.execute(HANDLE, "read",
        "{\"path\":\"repo/pom.xml\",\"fromLine\":10,\"toLine\":20}");

    assertFalse(execution.formatError());
    assertEquals("<project/>", execution.result().output());
  }

  @Test
  void dispatchesTestWithNullModule() {
    when(testTool.test(HANDLE, null)).thenReturn(ToolResult.success("[summary] ok"));

    ToolExecution execution = dispatcher.execute(HANDLE, "test", "{}");

    assertFalse(execution.formatError());
  }

  @Test
  void dispatchesGitDiffWithBlankArguments() {
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));

    ToolExecution execution = dispatcher.execute(HANDLE, "git_diff", " ");

    assertFalse(execution.formatError());
  }

  @Test
  void unknownToolIsFormatErrorWithHint() {
    ToolExecution execution = dispatcher.execute(HANDLE, "grep", "{}");

    assertTrue(execution.formatError());
    assertTrue(execution.result().error().contains("unknown tool 'grep'"));
    assertTrue(execution.result().error().contains("read"));
  }

  @Test
  void brokenJsonArgumentsAreFormatError() {
    ToolExecution execution = dispatcher.execute(HANDLE, "read", "{\"path\": ");

    assertTrue(execution.formatError());
    assertTrue(execution.result().error().contains("not valid JSON"));
  }

  @Test
  void missingRequiredFieldIsFormatError() {
    ToolExecution execution = dispatcher.execute(HANDLE, "read", "{}");

    assertTrue(execution.formatError());
    assertTrue(execution.result().error().contains("path"));
  }

  @Test
  void wrongFieldTypeIsFormatError() {
    ToolExecution execution = dispatcher.execute(HANDLE, "exec", "{\"cmd\": 42}");

    assertTrue(execution.formatError());
    assertTrue(execution.result().error().contains("cmd"));
  }

  @Test
  void toolFailureIsNotFormatError() {
    when(readTool.read(HANDLE, "repo/nope.txt", null, null))
        .thenReturn(ToolResult.failure("read failed: no such file"));

    ToolExecution execution = dispatcher.execute(HANDLE, "read", "{\"path\":\"repo/nope.txt\"}");

    assertFalse(execution.formatError());
    assertFalse(execution.result().ok());
  }

  @Test
  void toolCallbacksExposeAllSixToolsWithSchemas() {
    List<ToolCallback> callbacks = dispatcher.toolCallbacks();

    assertEquals(6, callbacks.size());
    assertEquals(List.of("read", "list", "apply_patch", "exec", "git_diff", "test"),
        callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList());
    for (ToolCallback callback : callbacks) {
      assertTrue(callback.getToolDefinition().inputSchema().startsWith("{\"type\":\"object\""));
    }
  }
}
