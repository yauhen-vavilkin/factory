package org.folio.factory.devfactory.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.api.SandboxSpec;
import org.folio.factory.sandbox.exception.SandboxException;
import org.folio.factory.sandbox.harness.ChatModelAdapter;
import org.folio.factory.sandbox.harness.CodingHarness;
import org.folio.factory.sandbox.harness.HarnessConfig;
import org.folio.factory.sandbox.harness.ModelReply;
import org.folio.factory.sandbox.harness.ToolDispatcher;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class CodingWorkerTest {

  private static final SandboxHandle HANDLE = new SandboxHandle("sbx-1", "c-1");

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
        "create:T-15", "exec:cd repo && git diff", "teardown:sbx-1");
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
  void trajectoryArtifactIsThisRunTrajectoryFile() throws Exception {
    FakeSandboxService sandbox = new FakeSandboxService();
    sandbox.diffStdout = "[status]\n\n[diff]\n+ok";
    when(adapter.reply(anyString(), anyString(), anyList())).thenReturn(ModelReply.text("done"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));
    CodingWorker worker = new CodingWorker(sandbox, harness(), codec);

    AgentResult result = worker.execute(context());

    String expected = "{\"ts\":\"2026-09-04T10:00:00Z\",\"step\":1,\"tool\":\"final\",\"args_len\":0,"
        + "\"out_len\":4,\"duration_ms\":0,\"ok\":true}\n"
        + "{\"steps\":1,\"outcome\":\"COMPLETED\",\"stop_reason\":\"COMPLETED\",\"files_changed\":0,"
        + "\"diff_size_bytes\":0,\"format_errors\":0}\n";
    assertThat(result.outputs().get("trajectory.jsonl")).isEqualTo(expected);
    assertThat(Files.readString(workDir.resolve(Trajectory.FILE_NAME))).isEqualTo(expected);
  }

  @Test
  void reportCarriesFrozenFrontmatterFields() {
    FakeSandboxService sandbox = new FakeSandboxService();
    sandbox.diffStdout = "[status]\n\n[diff]\n+ok";
    when(adapter.reply(anyString(), anyString(), anyList())).thenReturn(ModelReply.text("done"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));
    CodingWorker worker = new CodingWorker(sandbox, harness(), codec);

    AgentResult result = worker.execute(context());

    JsonNode metadata = codec.parse(result.outputs().get("report.md")).metadata();
    List<String> keys = new ArrayList<>(metadata.propertyNames());
    assertThat(keys).containsExactlyInAnyOrder("task_id", "repo_url", "branch", "outcome",
        "stop_reason", "steps", "files_changed", "diff_size_bytes", "format_errors");
    assertThat(metadata.path("task_id").asString()).isEqualTo("T-15");
    assertThat(metadata.path("repo_url").asString()).isEqualTo("https://github.com/folio/o-r.git");
    assertThat(metadata.path("branch").asString()).isEqualTo("dev/T15");
    assertThat(metadata.path("outcome").asString()).isEqualTo("COMPLETED");
    assertThat(metadata.path("stop_reason").asString()).isEqualTo("COMPLETED");
    assertThat(metadata.path("steps").asInt()).isEqualTo(1);
    assertThat(metadata.path("files_changed").asInt()).isEqualTo(0);
    assertThat(metadata.path("diff_size_bytes").asLong()).isEqualTo(0L);
    assertThat(metadata.path("format_errors").asInt()).isEqualTo(0);
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

    private static final String DIFF_COMMAND = "cd repo && git diff";

    private final List<String> calls = new ArrayList<>();
    private String diffStdout = "";
    private int diffExitCode;
    private boolean failCreate;
    private boolean failExec;
    private boolean failTeardown;
    private int teardowns;

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
      if (DIFF_COMMAND.equals(command)) {
        return new CommandResult(diffExitCode, diffStdout, "", 5L);
      }
      return new CommandResult(1, "", "unexpected command", 0L);
    }

    @Override
    public void teardown(SandboxHandle handle) {
      calls.add("teardown:" + handle.sandboxId());
      teardowns++;
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
