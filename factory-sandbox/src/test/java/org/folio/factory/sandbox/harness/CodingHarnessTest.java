package org.folio.factory.sandbox.harness;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CodingHarnessTest {

  private static final SandboxHandle HANDLE = new SandboxHandle("sbx-t13", "c1");

  @Mock
  private ChatModelAdapter adapter;

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

  @Captor
  private ArgumentCaptor<List<ChatMessage>> historyCaptor;

  private MutableClock clock;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2026-09-04T10:00:00Z"));
  }

  private CodingHarness harness(HarnessConfig config) {
    return new CodingHarness(adapter, new ToolDispatcher(readTool, listTool, applyPatchTool,
        execTool, gitDiffTool, testTool), gitDiffTool, clock, config);
  }

  @Test
  void runCompletesWhenModelImmediatelyRepliesWithFinalText(@TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(HarnessConfig.defaults());
    when(adapter.reply(anyString(), eq("fix NPE"), anyList())).thenReturn(ModelReply.text("done"));
    when(gitDiffTool.diff(HANDLE))
        .thenReturn(ToolResult.success("[status]\n M pom.xml\n\n[diff]\n+fix\n"));

    HarnessReport report = harness.run(HANDLE, TaskContract.ofGoal("fix NPE"), workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(HarnessReport.StopReason.COMPLETED, report.stopReason());
    assertEquals(1, report.steps());
    assertEquals(0, report.formatErrors());
    assertEquals(1, report.filesChanged());
    List<String> lines = Files.readAllLines(workDir.resolve(Trajectory.FILE_NAME));
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).contains("\"tool\":\"final\""));
    assertTrue(lines.get(1).contains("\"outcome\":\"COMPLETED\""));
  }

  @Test
  void threeStepRunCompletesWithTrajectory(@TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(new HarnessConfig(40, 3, 30L, "glm-5.3-flash"));
    String diffBody = "diff --git a/pom.xml b/pom.xml\n--- a/pom.xml\n";
    when(adapter.reply(anyString(), eq("fix NPE"), anyList()))
        .thenReturn(ModelReply.toolCall(new ToolCall("t1", "read", "{\"path\":\"repo/pom.xml\"}")))
        .thenReturn(ModelReply.toolCall(new ToolCall("t2", "apply_patch", "{\"diff\":\"update pom\"}")))
        .thenReturn(ModelReply.text("fixed the NPE"));
    when(readTool.read(HANDLE, "repo/pom.xml", null, null)).thenReturn(ToolResult.success("<project/>"));
    when(applyPatchTool.apply(HANDLE, "update pom")).thenReturn(ToolResult.success("patch applied"));
    when(gitDiffTool.diff(HANDLE))
        .thenReturn(ToolResult.success("[status]\n M pom.xml\n\n[diff]\n" + diffBody));

    HarnessReport report = harness.run(HANDLE, TaskContract.ofGoal("fix NPE"), workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(HarnessReport.StopReason.COMPLETED, report.stopReason());
    assertEquals(3, report.steps());
    assertEquals(0, report.formatErrors());
    assertEquals(1, report.filesChanged());
    assertEquals(diffBody.getBytes(UTF_8).length, report.diffSizeBytes());
    List<String> lines = Files.readAllLines(workDir.resolve(Trajectory.FILE_NAME));
    assertEquals(4, lines.size());
    assertTrue(lines.get(0).contains("\"tool\":\"read\""));
    assertTrue(lines.get(1).contains("\"tool\":\"apply_patch\""));
    assertTrue(lines.get(2).contains("\"tool\":\"final\""));
    assertTrue(lines.get(3).contains("\"outcome\":\"COMPLETED\""));
    assertTrue(lines.get(3).contains("\"stop_reason\":\"COMPLETED\""));
  }

  @Test
  void executesEveryToolCallInABatchInOrderAndAnswersEach(@TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(HarnessConfig.defaults());
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCalls(List.of(
            new ToolCall("t1", "read", "{\"path\":\"repo/pom.xml\"}"),
            new ToolCall("t2", "read", "{\"path\":\"repo/README.md\"}")), TokenUsage.ZERO))
        .thenReturn(ModelReply.text("done"));
    when(readTool.read(HANDLE, "repo/pom.xml", null, null))
        .thenReturn(ToolResult.success("<project/>"));
    when(readTool.read(HANDLE, "repo/README.md", null, null))
        .thenReturn(ToolResult.success("readme"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));

    HarnessReport report = harness.run(HANDLE, TaskContract.ofGoal("fix NPE"), workDir);

    InOrder inOrder = inOrder(readTool);
    inOrder.verify(readTool).read(HANDLE, "repo/pom.xml", null, null);
    inOrder.verify(readTool).read(HANDLE, "repo/README.md", null, null);
    verify(adapter, times(2)).reply(anyString(), anyString(), historyCaptor.capture());
    List<ChatMessage> secondHistory = historyCaptor.getAllValues().get(1);
    assertEquals(4, secondHistory.size());
    assertEquals(ChatMessage.ROLE_ASSISTANT, secondHistory.get(0).role());
    assertEquals("t1", secondHistory.get(0).toolCallId());
    assertEquals("read", secondHistory.get(0).toolName());
    assertEquals(ChatMessage.ROLE_TOOL, secondHistory.get(1).role());
    assertEquals("t1", secondHistory.get(1).toolCallId());
    assertEquals("read", secondHistory.get(1).toolName());
    assertEquals(ChatMessage.ROLE_ASSISTANT, secondHistory.get(2).role());
    assertEquals("t2", secondHistory.get(2).toolCallId());
    assertEquals("read", secondHistory.get(2).toolName());
    assertEquals(ChatMessage.ROLE_TOOL, secondHistory.get(3).role());
    assertEquals("t2", secondHistory.get(3).toolCallId());
    assertEquals("read", secondHistory.get(3).toolName());
    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(HarnessReport.StopReason.COMPLETED, report.stopReason());
    assertEquals(2, report.steps());
    assertEquals(0, report.formatErrors());
    List<String> lines = Files.readAllLines(workDir.resolve(Trajectory.FILE_NAME));
    assertEquals(4, lines.size());
    assertTrue(lines.get(0).contains("\"tool\":\"read\""));
    assertTrue(lines.get(0).contains("\"step\":1"));
    assertTrue(lines.get(0).contains("\"out_len\":10"));
    assertTrue(lines.get(1).contains("\"tool\":\"read\""));
    assertTrue(lines.get(1).contains("\"step\":1"));
    assertTrue(lines.get(1).contains("\"out_len\":6"));
    assertTrue(lines.get(2).contains("\"tool\":\"final\""));
    assertTrue(lines.get(2).contains("\"step\":2"));
    assertTrue(lines.get(3).contains("\"outcome\":\"COMPLETED\""));
  }

  @Test
  void formatErrorsWithinBatchCountPerCall(@TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(HarnessConfig.defaults());
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCalls(List.of(
            new ToolCall("g1", "grep", "{}"),
            new ToolCall("r1", "read", "{\"path\":\"repo/pom.xml\"}"),
            new ToolCall("g2", "grep", "{}")), TokenUsage.ZERO))
        .thenReturn(ModelReply.text("done"));
    when(readTool.read(HANDLE, "repo/pom.xml", null, null))
        .thenReturn(ToolResult.success("<project/>"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));

    HarnessReport report = harness.run(HANDLE, TaskContract.ofGoal("fix NPE"), workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(HarnessReport.StopReason.COMPLETED, report.stopReason());
    assertEquals(2, report.formatErrors());
    assertEquals(2, report.steps());
  }

  @Test
  void maxStepsExceededFails(@TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(new HarnessConfig(3, 3, 30L, "glm-5.3-flash"));
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(new ToolCall("t1", "read", "{\"path\":\"repo/pom.xml\"}")));
    when(readTool.read(HANDLE, "repo/pom.xml", null, null)).thenReturn(ToolResult.success("<project/>"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));

    HarnessReport report = harness.run(HANDLE, TaskContract.ofGoal("fix NPE"), workDir);

    assertEquals(HarnessReport.Outcome.FAILED, report.outcome());
    assertEquals(HarnessReport.StopReason.STEPS_EXCEEDED, report.stopReason());
    assertEquals(3, report.steps());
    verify(adapter, times(3)).reply(anyString(), anyString(), anyList());
    List<String> lines = Files.readAllLines(workDir.resolve(Trajectory.FILE_NAME));
    assertEquals(4, lines.size());
  }

  @Test
  void consecutiveInvalidToolCallsExceedLimitAndModelSeesHints(@TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(new HarnessConfig(40, 3, 30L, "glm-5.3-flash"));
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(new ToolCall("f1", "grep", "{}")))
        .thenReturn(ModelReply.toolCall(new ToolCall("f2", "grep", "{}")))
        .thenReturn(ModelReply.toolCall(new ToolCall("f3", "grep", "{}")));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));

    HarnessReport report = harness.run(HANDLE, TaskContract.ofGoal("fix NPE"), workDir);

    assertEquals(HarnessReport.Outcome.FAILED, report.outcome());
    assertEquals(HarnessReport.StopReason.FORMAT_ERRORS_EXCEEDED, report.stopReason());
    assertEquals(3, report.formatErrors());
    verify(adapter, times(3)).reply(anyString(), anyString(), historyCaptor.capture());
    List<List<ChatMessage>> histories = historyCaptor.getAllValues();
    assertEquals(List.of(), histories.get(0));
    for (int i = 1; i < histories.size(); i++) {
      List<ChatMessage> history = histories.get(i);
      ChatMessage last = history.get(history.size() - 1);
      assertEquals("tool", last.role());
      assertTrue(last.content().contains("unknown tool 'grep'"));
    }
  }

  @Test
  void toolFailureIsReturnedToModelAndDoesNotCountAsFormatError(@TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(new HarnessConfig(40, 3, 30L, "glm-5.3-flash"));
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(new ToolCall("t1", "read", "{\"path\":\"repo/nope\"}")))
        .thenReturn(ModelReply.toolCall(new ToolCall("t2", "read", "{\"path\":\"repo/pom.xml\"}")))
        .thenReturn(ModelReply.text("fixed"));
    when(readTool.read(HANDLE, "repo/nope", null, null))
        .thenReturn(ToolResult.failure("read failed: no such file"));
    when(readTool.read(HANDLE, "repo/pom.xml", null, null)).thenReturn(ToolResult.success("<project/>"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));

    HarnessReport report = harness.run(HANDLE, TaskContract.ofGoal("fix NPE"), workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(0, report.formatErrors());
    verify(adapter, times(3)).reply(anyString(), anyString(), historyCaptor.capture());
    List<ChatMessage> secondHistory = historyCaptor.getAllValues().get(1);
    ChatMessage toolResult = secondHistory.get(secondHistory.size() - 1);
    assertTrue(toolResult.content().contains("ERROR: read failed: no such file"));
    List<String> lines = Files.readAllLines(workDir.resolve(Trajectory.FILE_NAME));
    assertTrue(lines.get(0).contains("\"ok\":false"));
  }

  @Test
  void wallClockTimeoutFails(@TempDir Path workDir) {
    CodingHarness harness = harness(new HarnessConfig(40, 3, 1L, "glm-5.3-flash"));
    when(adapter.reply(anyString(), anyString(), anyList())).thenAnswer(invocation -> {
      clock.advance(Duration.ofMinutes(5));
      return ModelReply.toolCall(new ToolCall("t1", "read", "{\"path\":\"repo/pom.xml\"}"));
    });
    when(readTool.read(HANDLE, "repo/pom.xml", null, null)).thenReturn(ToolResult.success("<project/>"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));

    HarnessReport report = harness.run(HANDLE, TaskContract.ofGoal("fix NPE"), workDir);

    assertEquals(HarnessReport.Outcome.FAILED, report.outcome());
    assertEquals(HarnessReport.StopReason.TIMEOUT, report.stopReason());
    assertEquals(1, report.steps());
    verify(adapter, times(1)).reply(anyString(), anyString(), anyList());
  }

  @Test
  void adapterExceptionFailsWithModelError(@TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(new HarnessConfig(40, 3, 30L, "glm-5.3-flash"));
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenThrow(new RuntimeException("provider 500"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));

    HarnessReport report = harness.run(HANDLE, TaskContract.ofGoal("fix NPE"), workDir);

    assertEquals(HarnessReport.Outcome.FAILED, report.outcome());
    assertEquals(HarnessReport.StopReason.MODEL_ERROR, report.stopReason());
    assertEquals(1, report.steps());
    List<String> lines = Files.readAllLines(workDir.resolve(Trajectory.FILE_NAME));
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).contains("\"tool\":\"model_error\""));
    assertTrue(lines.get(1).contains("\"stop_reason\":\"MODEL_ERROR\""));
  }

  @Test
  void tokenUsageAccumulatesAcrossTurns(@TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(HarnessConfig.defaults());
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCalls(List.of(
            new ToolCall("t1", "read", "{\"path\":\"repo/pom.xml\"}")), new TokenUsage(100, 50)))
        .thenReturn(ModelReply.text("done", new TokenUsage(37, 13)));
    when(readTool.read(HANDLE, "repo/pom.xml", null, null)).thenReturn(ToolResult.success("<project/>"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));

    HarnessReport report = harness.run(HANDLE, TaskContract.ofGoal("fix NPE"), workDir);

    assertEquals(137L, report.tokensIn());
    assertEquals(63L, report.tokensOut());
    List<String> lines = Files.readAllLines(workDir.resolve(Trajectory.FILE_NAME));
    String lastLine = lines.get(lines.size() - 1);
    assertTrue(lastLine.contains("\"tokens_in\":137"));
    assertTrue(lastLine.contains("\"tokens_out\":63"));
  }

  @Test
  void contractDirectiveIsTheFirstTurnUserMessage(@TempDir Path workDir) {
    CodingHarness harness = harness(HarnessConfig.defaults());
    when(adapter.reply(anyString(), anyString(), anyList())).thenReturn(ModelReply.text("done"));
    when(gitDiffTool.diff(HANDLE))
        .thenReturn(ToolResult.success("[status]\n M pom.xml\n\n[diff]\n+fix\n"));
    TaskContract contract = new TaskContract("fix the NPE in CodingWorker",
        jsonArray("[\"repro command fails before the fix\", \"test suite passes after\"]"),
        jsonObject("{\"allow_paths\":[\"factory-core\"],\"language\":\"java-21\"}"),
        "regression appeared after 4.2");
    harness.run(HANDLE, contract, workDir);

    ArgumentCaptor<String> userMessage = ArgumentCaptor.forClass(String.class);
    verify(adapter).reply(anyString(), userMessage.capture(), anyList());
    assertEquals(contract.directive(), userMessage.getValue());
    assertTrue(userMessage.getValue().contains("repro command fails before the fix"));
    assertTrue(userMessage.getValue().contains("allow_paths"));
    assertTrue(userMessage.getValue().contains("regression appeared after 4.2"));
  }

  @Test
  void earlyCompletionWithoutChangeFailsTaskOutcomeWhenNoopNotPermitted(
      @TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(HarnessConfig.defaults());
    when(adapter.reply(anyString(), anyString(), anyList())).thenReturn(ModelReply.text("done"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));
    TaskContract contract = new TaskContract("reproduce the NPE and fix it",
        jsonArray("[\"repro command fails before the fix\"]"), null, null);

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertEquals(TaskOutcome.Reason.NO_OP_NOT_PERMITTED, report.taskOutcomeReason());
  }

  @Test
  void earlyCompletionWithNoopPermittedButNoVerificationFailsTaskOutcome(
      @TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(HarnessConfig.defaults());
    when(adapter.reply(anyString(), anyString(), anyList())).thenReturn(ModelReply.text("done"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));
    TaskContract contract = new TaskContract("verify the reported NPE is real",
        null, jsonObject("{\"allow_noop\":true}"), null);

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertEquals(TaskOutcome.Reason.NO_VERIFICATION_EVIDENCE, report.taskOutcomeReason());
  }

  @Test
  void noopWithOnlyFailingToolCallsStillLacksVerificationEvidence(
      @TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(HarnessConfig.defaults());
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(new ToolCall("t1", "read", "{\"path\":\"repo/nope\"}")))
        .thenReturn(ModelReply.text("verified"));
    when(readTool.read(HANDLE, "repo/nope", null, null))
        .thenReturn(ToolResult.failure("read failed: no such file"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));
    TaskContract contract = new TaskContract("verify the reported NPE is real",
        null, jsonObject("{\"allow_noop\":true}"), null);

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertEquals(TaskOutcome.Reason.NO_VERIFICATION_EVIDENCE, report.taskOutcomeReason());
  }

  @Test
  void verifiedNoOpWithPermittedContractSucceeds(@TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(HarnessConfig.defaults());
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(
            new ToolCall("t1", "exec", "{\"cmd\":\"cd repo && mvn -pl core test -B\"}")))
        .thenReturn(ModelReply.text("Reproduction passes on main: the reported NPE "
            + "no longer occurs, test run is green, no change needed."));
    when(execTool.run(HANDLE, "cd repo && mvn -pl core test -B", null))
        .thenReturn(ToolResult.success("Tests run: 12, Failures: 0"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));
    TaskContract contract = new TaskContract("verify the reported NPE is real",
        jsonArray("[\"reproduction command result recorded\"]"),
        jsonObject("{\"allow_noop\":true}"), null);

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertEquals(TaskOutcome.Reason.NO_OP_VERIFIED, report.taskOutcomeReason());
  }

  @Test
  void changedResultWithFinalReportSucceeds(@TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(HarnessConfig.defaults());
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(new ToolCall("t1", "apply_patch", "{\"diff\":\"update pom\"}")))
        .thenReturn(ModelReply.text("fixed the NPE, tests pass"));
    when(applyPatchTool.apply(HANDLE, "update pom")).thenReturn(ToolResult.success("patch applied"));
    when(gitDiffTool.diff(HANDLE))
        .thenReturn(ToolResult.success("[status]\n M pom.xml\n\n[diff]\ndiff --git a/pom.xml b/pom.xml\n"));
    TaskContract contract = new TaskContract("fix the NPE",
        jsonArray("[\"tests pass\"]"), null, null);

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertEquals(TaskOutcome.Reason.CHANGES_DELIVERED, report.taskOutcomeReason());
  }

  @Test
  void blankFinalReplyFailsTaskOutcomeEvenWithChanges(@TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(HarnessConfig.defaults());
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(new ToolCall("t1", "apply_patch", "{\"diff\":\"update pom\"}")))
        .thenReturn(ModelReply.text("  "));
    when(applyPatchTool.apply(HANDLE, "update pom")).thenReturn(ToolResult.success("patch applied"));
    when(gitDiffTool.diff(HANDLE))
        .thenReturn(ToolResult.success("[status]\n M pom.xml\n\n[diff]\ndiff --git a/pom.xml b/pom.xml\n"));
    TaskContract contract = new TaskContract("fix the NPE", null, null, null);

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertEquals(TaskOutcome.Reason.MISSING_FINAL_REPORT, report.taskOutcomeReason());
  }

  @Test
  void failedModelRunFailsTaskOutcomeRegardlessOfDiff(@TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(new HarnessConfig(3, 3, 30L, "glm-5.3-flash"));
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(new ToolCall("t1", "apply_patch", "{\"diff\":\"update pom\"}")));
    when(applyPatchTool.apply(HANDLE, "update pom")).thenReturn(ToolResult.success("patch applied"));
    when(gitDiffTool.diff(HANDLE))
        .thenReturn(ToolResult.success("[status]\n M pom.xml\n\n[diff]\ndiff --git a/pom.xml b/pom.xml\n"));
    TaskContract contract = new TaskContract("fix the NPE", null, null, null);

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.FAILED, report.outcome());
    assertEquals(HarnessReport.StopReason.STEPS_EXCEEDED, report.stopReason());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertEquals(TaskOutcome.Reason.MODEL_RUN_FAILED, report.taskOutcomeReason());
  }

  @Test
  void finalResponseCarriedOnReportAndTrajectoryFinalRecord(@TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(HarnessConfig.defaults());
    String finalText = "changed ddl-auto to create in seven integration tests; mvn green";
    when(adapter.reply(anyString(), anyString(), anyList())).thenReturn(ModelReply.text(finalText));
    when(gitDiffTool.diff(HANDLE))
        .thenReturn(ToolResult.success("[status]\n M pom.xml\n\n[diff]\n+fix\n"));

    HarnessReport report = harness.run(HANDLE, TaskContract.ofGoal("fix the warning"), workDir);

    assertEquals(finalText, report.finalText());
    List<String> lines = Files.readAllLines(workDir.resolve(Trajectory.FILE_NAME));
    assertTrue(lines.get(0).contains("\"tool\":\"final\""));
    assertTrue(lines.get(0).contains("\"text\":\"" + finalText + "\""));
    assertTrue(lines.get(0).contains("\"out_len\":" + finalText.length()));
  }

  @Test
  void overlongFinalResponseIsBoundedAndFullLengthStaysRecorded(@TempDir Path workDir)
      throws Exception {
    CodingHarness harness = harness(HarnessConfig.defaults());
    String finalText = "y".repeat(CodingHarness.FINAL_TEXT_MAX_CHARS + 100);
    when(adapter.reply(anyString(), anyString(), anyList())).thenReturn(ModelReply.text(finalText));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));

    HarnessReport report = harness.run(HANDLE, TaskContract.ofGoal("fix"), workDir);

    String marker = "[final response truncated: 100 chars omitted]";
    assertEquals(CodingHarness.FINAL_TEXT_MAX_CHARS + 1 + marker.length(),
        report.finalText().length());
    assertTrue(report.finalText().endsWith(marker));
    List<String> lines = Files.readAllLines(workDir.resolve(Trajectory.FILE_NAME));
    assertTrue(lines.get(0).contains("\"out_len\":" + finalText.length()));
    assertTrue(lines.get(0).contains(marker));
  }

  @Test
  void failedRunWithoutFinalReplyCarriesEmptyFinalText(@TempDir Path workDir) throws Exception {
    CodingHarness harness = harness(new HarnessConfig(3, 3, 30L, "glm-5.3-flash"));
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(new ToolCall("t1", "read", "{\"path\":\"repo/pom.xml\"}")));
    when(readTool.read(HANDLE, "repo/pom.xml", null, null)).thenReturn(ToolResult.success("<project/>"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));

    HarnessReport report = harness.run(HANDLE, TaskContract.ofGoal("fix NPE"), workDir);

    assertEquals(HarnessReport.Outcome.FAILED, report.outcome());
    assertEquals("", report.finalText());
  }

  private static tools.jackson.databind.JsonNode jsonArray(String json) {
    return MAPPER.readTree(json);
  }

  private static tools.jackson.databind.JsonNode jsonObject(String json) {
    return MAPPER.readTree(json);
  }

  private static final tools.jackson.databind.json.JsonMapper MAPPER =
      tools.jackson.databind.json.JsonMapper.builder().build();

  private static final class MutableClock extends Clock {

    private Instant instant;

    MutableClock(Instant start) {
      this.instant = start;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
