package org.folio.factory.devfactory.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.api.SandboxSpec;
import org.folio.factory.sandbox.exception.SandboxException;
import org.folio.factory.sandbox.harness.ChatModelAdapter;
import org.folio.factory.sandbox.harness.CodingHarness;
import org.folio.factory.sandbox.harness.HarnessConfig;
import org.folio.factory.sandbox.harness.HarnessReport;
import org.folio.factory.sandbox.harness.ModelReply;
import org.folio.factory.sandbox.harness.TaskContract;
import org.folio.factory.sandbox.harness.TaskOutcome;
import org.folio.factory.sandbox.harness.ToolDispatcher;
import org.folio.factory.sandbox.harness.TokenUsage;
import org.folio.factory.sandbox.harness.ToolCall;
import org.folio.factory.sandbox.harness.Trajectory;
import org.folio.factory.sandbox.tools.ApplyPatchTool;
import org.folio.factory.sandbox.tools.ExecTool;
import org.folio.factory.sandbox.tools.GitDiffTool;
import org.folio.factory.sandbox.tools.ListTool;
import org.folio.factory.sandbox.tools.ReadTool;
import org.folio.factory.sandbox.tools.TestTool;
import org.folio.factory.sandbox.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class CodingWorkerTest {

  private static final SandboxHandle HANDLE = new SandboxHandle("sbx-1", "c-1");

  static final String BASE_SHA = "0123456789abcdef0123456789abcdef01234567";
  static final String REV_PARSE_COMMAND = "cd repo && git rev-parse HEAD";

  static String exportCommand(String baseRevision) {
    return "cd repo && base=" + baseRevision
        + " && idx=$(mktemp) && { GIT_INDEX_FILE=\"$idx\" git read-tree \"$base\""
        + " && GIT_INDEX_FILE=\"$idx\" git add -A"
        + " && GIT_INDEX_FILE=\"$idx\" git diff --binary -M --cached \"$base\";"
        + " rc=$?; rm -f \"$idx\"; exit \"$rc\"; }";
  }

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

  private final FrontmatterCodec codec = new FrontmatterCodec();

  @TempDir
  Path workDir;

  @Test
  void lifecycleOrderAndExactDeclaredOutputs() {
    FakeSandboxService sandbox = new FakeSandboxService();
    sandbox.diffStdout = "[status]\n\n[diff]\n+ok";
    when(adapter.reply(anyString(), anyString(), anyList())).thenReturn(ModelReply.text("done"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));
    CodingWorker worker = new CodingWorker(sandbox, harness(), codec);

    assertThat(worker.id()).isEqualTo("coding-worker");
    AgentResult result = worker.execute(context());

    assertThat(sandbox.calls).containsExactly(
        "create:T-15", "exec:" + REV_PARSE_COMMAND, "exec:" + exportCommand(BASE_SHA),
        "teardown:sbx-1");
    assertThat(sandbox.teardowns).isEqualTo(1);
    assertThat(result.outputs()).containsOnlyKeys("patch.diff", "report.md", "trajectory.jsonl");
  }

  @Test
  void patchDiffIsDiffStdoutWithoutStatusSection() {
    FakeSandboxService sandbox = new FakeSandboxService();
    sandbox.diffStdout = "[status]\n M pom.xml\n\n[diff]\n"
        + "diff --git a/pom.xml b/pom.xml\n--- a/pom.xml\n+++ b/pom.xml\n";
    when(adapter.reply(anyString(), anyString(), anyList())).thenReturn(ModelReply.text("done"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));
    CodingWorker worker = new CodingWorker(sandbox, harness(), codec);

    AgentResult result = worker.execute(context());

    assertThat(result.outputs().get("patch.diff"))
        .isEqualTo("diff --git a/pom.xml b/pom.xml\n--- a/pom.xml\n+++ b/pom.xml\n")
        .doesNotContain("[status]");
  }

  @Test
  void noChangesMapsToExplicitEmptyPatch() {
    FakeSandboxService sandbox = new FakeSandboxService();
    sandbox.diffStdout = "";
    when(adapter.reply(anyString(), anyString(), anyList())).thenReturn(ModelReply.text("done"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));
    CodingWorker worker = new CodingWorker(sandbox, harness(), codec);

    AgentResult result = worker.execute(context());

    assertThat(result.outputs().get("patch.diff")).isEqualTo("(no changes)\n");
  }

  @Test
  void largeDiffIsNotTruncated() {
    String diffBody = ("+" + "a".repeat(1022) + "\n").repeat(60);
    FakeSandboxService sandbox = new FakeSandboxService();
    sandbox.diffStdout = "[status]\n M pom.xml\n\n[diff]\n" + diffBody;
    when(adapter.reply(anyString(), anyString(), anyList())).thenReturn(ModelReply.text("done"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));
    CodingWorker worker = new CodingWorker(sandbox, harness(), codec);

    AgentResult result = worker.execute(context());

    assertThat(result.outputs().get("patch.diff"))
        .hasSize(60 * 1024)
        .isEqualTo(diffBody)
        .doesNotContain("[output truncated");
  }

  @Test
  void binaryPatchIsPreservedByteForByteIncludingTerminatingBlankLine() {
    String binaryDiff = "diff --git a/icon.bin b/icon.bin\n"
        + "new file mode 100644\n"
        + "GIT binary patch\n"
        + "literal 9\n"
        + "QcmZ={aQg4e5MpQv01W^Fga7~l\n"
        + "\n"
        + "literal 0\n"
        + "HcmV?d00001\n"
        + "\n";
    FakeSandboxService sandbox = new FakeSandboxService();
    sandbox.diffStdout = "[status]\n\n[diff]\n" + binaryDiff;
    when(adapter.reply(anyString(), anyString(), anyList())).thenReturn(ModelReply.text("done"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));
    CodingWorker worker = new CodingWorker(sandbox, harness(), codec);

    AgentResult result = worker.execute(context());

    assertThat(result.outputs().get("patch.diff"))
        .as("git binary patch base85 blocks are terminated by an empty line; git apply "
            + "rejects (exit 128, 'corrupt binary patch') a patch whose trailing blank line "
            + "was normalized away")
        .isEqualTo(binaryDiff);
  }

  @Test
  void trajectoryArtifactIsThisRunTrajectoryFile() throws Exception {
    FakeSandboxService sandbox = new FakeSandboxService();
    sandbox.diffStdout = "[status]\n\n[diff]\n+ok";
    when(adapter.reply(anyString(), anyString(), anyList())).thenReturn(ModelReply.text("done"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));
    CodingWorker worker = new CodingWorker(sandbox, harness(), codec);

    AgentResult result = worker.execute(context());

    String expected = "{\"ts\":\"2026-09-04T10:00:00Z\",\"step\":1,\"tool\":\"final\",\"args_len\":0,"
        + "\"out_len\":4,\"duration_ms\":0,\"ok\":true}\n"
        + "{\"steps\":1,\"outcome\":\"COMPLETED\",\"stop_reason\":\"COMPLETED\","
        + "\"task_outcome\":\"FAILED\",\"task_outcome_reason\":\"NO_OP_NOT_PERMITTED\","
        + "\"files_changed\":0,\"diff_size_bytes\":0,\"format_errors\":0,"
        + "\"tokens_in\":0,\"tokens_out\":0}\n";
    assertThat(result.outputs().get("trajectory.jsonl")).isEqualTo(expected);
    assertThat(Files.readString(workDir.resolve(Trajectory.FILE_NAME))).isEqualTo(expected);
  }

  @Test
  void reportCarriesFrozenFrontmatterFields() {
    FakeSandboxService sandbox = new FakeSandboxService();
    sandbox.diffStdout = "[status]\n M pom.xml\n\n[diff]\n+ok";
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(new ToolCall("t1", "apply_patch", "{\"diff\":\"x\"}")))
        .thenReturn(ModelReply.text("done"));
    when(applyPatchTool.apply(HANDLE, "x")).thenReturn(ToolResult.success("applied"));
    when(gitDiffTool.diff(HANDLE))
        .thenReturn(ToolResult.success("[status]\n M pom.xml\n\n[diff]\n+ok"));
    CodingWorker worker = new CodingWorker(sandbox, harness(), codec);

    AgentResult result = worker.execute(context());

    JsonNode metadata = codec.parse(result.outputs().get("report.md")).metadata();
    List<String> keys = new ArrayList<>(metadata.propertyNames());
    assertThat(keys).containsExactlyInAnyOrder("task_id", "repo_url", "branch", "base_revision",
        "outcome", "stop_reason", "task_outcome", "task_outcome_reason", "steps", "files_changed",
        "diff_size_bytes", "format_errors", "tokens_in", "tokens_out");
    assertThat(metadata.path("task_id").asString()).isEqualTo("T-15");
    assertThat(metadata.path("repo_url").asString()).isEqualTo("https://github.com/folio/o-r.git");
    assertThat(metadata.path("branch").asString()).isEqualTo("dev/T15");
    assertThat(metadata.path("base_revision").asString()).isEqualTo(BASE_SHA);
    assertThat(metadata.path("outcome").asString()).isEqualTo("COMPLETED");
    assertThat(metadata.path("stop_reason").asString()).isEqualTo("COMPLETED");
    assertThat(metadata.path("task_outcome").asString()).isEqualTo("SUCCEEDED");
    assertThat(metadata.path("task_outcome_reason").asString()).isEqualTo("CHANGES_DELIVERED");
    assertThat(metadata.path("steps").asInt()).isEqualTo(2);
    assertThat(metadata.path("files_changed").asInt()).isEqualTo(1);
    assertThat(metadata.path("diff_size_bytes").asLong()).isEqualTo(3L);
    assertThat(metadata.path("format_errors").asInt()).isEqualTo(0);
    assertThat(metadata.path("tokens_in").asLong()).isEqualTo(0L);
    assertThat(metadata.path("tokens_out").asLong()).isEqualTo(0L);
  }

  @Test
  void reportCarriesTaskOutcomeForEarlyModelCompletion() {
    FakeSandboxService sandbox = new FakeSandboxService();
    sandbox.diffStdout = "";
    when(adapter.reply(anyString(), anyString(), anyList())).thenReturn(ModelReply.text("done"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));
    CodingWorker worker = new CodingWorker(sandbox, harness(), codec);

    AgentResult result = worker.execute(context());

    JsonNode metadata = codec.parse(result.outputs().get("report.md")).metadata();
    assertThat(metadata.path("outcome").asString()).isEqualTo("COMPLETED");
    assertThat(metadata.path("task_outcome").asString()).isEqualTo("FAILED");
    assertThat(metadata.path("task_outcome_reason").asString()).isEqualTo("NO_OP_NOT_PERMITTED");
  }

  @Test
  void contractFieldsFromTriggerPayloadReachHarnessUnchanged() {
    FakeSandboxService sandbox = new FakeSandboxService();
    sandbox.diffStdout = "[status]\n M pom.xml\n\n[diff]\n+ok";
    CodingHarness harness = org.mockito.Mockito.mock(CodingHarness.class);
    when(harness.run(any(), any(TaskContract.class), any())).thenReturn(new HarnessReport(2,
        HarnessReport.Outcome.COMPLETED, HarnessReport.StopReason.COMPLETED, 1, 8L, 0, 0L, 0L,
        TaskOutcome.SUCCEEDED, TaskOutcome.Reason.CHANGES_DELIVERED));
    CodingWorker worker = new CodingWorker(sandbox, harness, codec);
    JsonNode payload = JsonMapper.builder().build().readTree("""
        {"taskId":"T-18",
         "repoUrl":"https://github.com/folio/o-r.git",
         "baseBranch":"main",
         "branch":"dev/T18",
         "goal":"fix the NPE in CodingWorker",
         "acceptance":["repro command fails before the fix","test suite passes after"],
         "constraints":{"allow_paths":["factory-core"],"allow_noop":false,"language":"java-21"},
         "notes":"regression appeared after release 4.2; see ticket FOLIO-1234."}
        """);

    worker.execute(new AgentContext(UUID.randomUUID(), "coding", Map.of(), payload,
        Map.of("workDir", workDir.toString()),
        List.of("patch.diff", "report.md", "trajectory.jsonl")));

    ArgumentCaptor<TaskContract> captor = ArgumentCaptor.forClass(TaskContract.class);
    verify(harness).run(any(), captor.capture(), any());
    TaskContract contract = captor.getValue();
    assertThat(contract.goal()).isEqualTo("fix the NPE in CodingWorker");
    assertThat(contract.acceptance().size()).isEqualTo(2);
    assertThat(contract.acceptance().get(0).asString()).isEqualTo("repro command fails before the fix");
    assertThat(contract.acceptance().get(1).asString()).isEqualTo("test suite passes after");
    assertThat(contract.constraints().size()).isEqualTo(3);
    assertThat(contract.constraints().path("language").asString()).isEqualTo("java-21");
    assertThat(contract.constraints().path("allow_paths").get(0).asString())
        .isEqualTo("factory-core");
    assertThat(contract.allowsNoOp()).isFalse();
    assertThat(contract.notes())
        .isEqualTo("regression appeared after release 4.2; see ticket FOLIO-1234.");
  }

  @Test
  void missingContractFieldsStillRunWithBareGoal() {
    FakeSandboxService sandbox = new FakeSandboxService();
    sandbox.diffStdout = "[status]\n M pom.xml\n\n[diff]\n+ok";
    CodingHarness harness = org.mockito.Mockito.mock(CodingHarness.class);
    when(harness.run(any(), any(TaskContract.class), any())).thenReturn(new HarnessReport(1,
        HarnessReport.Outcome.COMPLETED, HarnessReport.StopReason.COMPLETED, 1, 8L, 0, 0L, 0L,
        TaskOutcome.SUCCEEDED, TaskOutcome.Reason.CHANGES_DELIVERED));
    CodingWorker worker = new CodingWorker(sandbox, harness, codec);

    worker.execute(context());

    ArgumentCaptor<TaskContract> captor = ArgumentCaptor.forClass(TaskContract.class);
    verify(harness).run(any(), captor.capture(), any());
    TaskContract contract = captor.getValue();
    assertThat(contract.goal()).isEqualTo("fix the NPE in CodingWorker");
    assertThat(contract.acceptance()).isNull();
    assertThat(contract.constraints()).isNull();
    assertThat(contract.notes()).isNull();
  }

  @Test
  void reportCarriesTokenTotals() {
    FakeSandboxService sandbox = new FakeSandboxService();
    sandbox.diffStdout = "[status]\n\n[diff]\n+ok";
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.text("done", new TokenUsage(250, 125)));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));
    CodingWorker worker = new CodingWorker(sandbox, harness(), codec);

    AgentResult result = worker.execute(context());

    JsonNode metadata = codec.parse(result.outputs().get("report.md")).metadata();
    assertThat(metadata.path("tokens_in").asLong()).isEqualTo(250L);
    assertThat(metadata.path("tokens_out").asLong()).isEqualTo(125L);
  }

  @Test
  void revParseFailureIsInfrastructureFailure() {
    FakeSandboxService sandbox = new FakeSandboxService();
    sandbox.revParseExitCode = 1;
    CodingWorker worker = new CodingWorker(sandbox, harness(), codec);

    assertThatThrownBy(() -> worker.execute(context()))
        .isInstanceOf(AgentExecutionException.class)
        .hasMessageContaining("base revision");
    assertThat(sandbox.calls).containsExactly(
        "create:T-15", "exec:" + REV_PARSE_COMMAND, "teardown:sbx-1");
    assertThat(sandbox.teardowns).isEqualTo(1);
  }

  @Test
  void malformedBaseRevisionFailsBeforeExportRuns() {
    FakeSandboxService sandbox = new FakeSandboxService();
    sandbox.revParseStdout = "main\n";
    CodingWorker worker = new CodingWorker(sandbox, harness(), codec);

    assertThatThrownBy(() -> worker.execute(context()))
        .isInstanceOf(AgentExecutionException.class)
        .hasMessageContaining("base revision");
    assertThat(sandbox.calls).containsExactly(
        "create:T-15", "exec:" + REV_PARSE_COMMAND, "teardown:sbx-1");
  }

  @Test
  void artifactsAreDurablyWrittenToWorkDirBeforeTeardown() throws Exception {
    FakeSandboxService sandbox = new FakeSandboxService();
    sandbox.diffStdout = "[status]\n\n[diff]\n+ok";
    when(adapter.reply(anyString(), anyString(), anyList())).thenReturn(ModelReply.text("done"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));
    sandbox.teardownProbeDir = workDir;
    CodingWorker worker = new CodingWorker(sandbox, harness(), codec);

    AgentResult result = worker.execute(context());

    assertThat(sandbox.teardownSawPatchDiff)
        .as("patch.diff must be durably written before teardown runs")
        .isTrue();
    assertThat(sandbox.teardownSawReportMd)
        .as("report.md must be durably written before teardown runs")
        .isTrue();
    assertThat(workDir.resolve("patch.diff")).hasContent(result.outputs().get("patch.diff"));
    assertThat(workDir.resolve("report.md")).hasContent(result.outputs().get("report.md"));
  }

  private CodingHarness harness() {
    return new CodingHarness(adapter, new ToolDispatcher(readTool, listTool, applyPatchTool,
        execTool, gitDiffTool, testTool), gitDiffTool, new MutableClock(
            Instant.parse("2026-09-04T10:00:00Z")), HarnessConfig.defaults());
  }

  private AgentContext context() {
    JsonNode payload = JsonMapper.builder().build().valueToTree(Map.of(
        "taskId", "T-15",
        "repoUrl", "https://github.com/folio/o-r.git",
        "baseBranch", "main",
        "branch", "dev/T15",
        "goal", "fix the NPE in CodingWorker"));
    return new AgentContext(UUID.randomUUID(), "coding", Map.of(), payload,
        Map.of("workDir", workDir.toString()),
        List.of("patch.diff", "report.md", "trajectory.jsonl"));
  }

  private static final class FakeSandboxService implements SandboxService {

    private final List<String> calls = new ArrayList<>();
    private String diffStdout = "";
    private int diffExitCode;
    private String revParseStdout = BASE_SHA + "\n";
    private int revParseExitCode;
    private boolean failCreate;
    private boolean failExec;
    private boolean failTeardown;
    private int teardowns;
    private Path teardownProbeDir;
    private boolean teardownSawPatchDiff;
    private boolean teardownSawReportMd;

    @Override
    public SandboxHandle create(SandboxSpec spec) {
      calls.add("create:" + spec.taskId());
      if (failCreate) {
        throw new SandboxException("create failed");
      }
      return HANDLE;
    }

    @Override
    public CommandResult exec(SandboxHandle handle, String command, long timeoutSec) {
      calls.add("exec:" + command);
      if (failExec) {
        throw new SandboxException("exec failed");
      }
      if (REV_PARSE_COMMAND.equals(command)) {
        return new CommandResult(revParseExitCode, revParseStdout, "", 5L);
      }
      if (exportCommand(BASE_SHA).equals(command)) {
        return new CommandResult(diffExitCode, diffStdout, "", 5L);
      }
      return new CommandResult(1, "", "unexpected command", 0L);
    }

    @Override
    public void teardown(SandboxHandle handle) {
      calls.add("teardown:" + handle.sandboxId());
      teardowns++;
      if (teardownProbeDir != null) {
        teardownSawPatchDiff = java.nio.file.Files.exists(teardownProbeDir.resolve("patch.diff"));
        teardownSawReportMd = java.nio.file.Files.exists(teardownProbeDir.resolve("report.md"));
      }
      if (failTeardown) {
        throw new SandboxException("teardown failed");
      }
    }
  }

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
