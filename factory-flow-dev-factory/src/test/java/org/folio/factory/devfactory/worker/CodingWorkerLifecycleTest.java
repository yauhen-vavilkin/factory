package org.folio.factory.devfactory.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
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
import org.folio.factory.sandbox.harness.CodingHarness;
import org.folio.factory.sandbox.harness.HarnessReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class CodingWorkerLifecycleTest {

  private static final SandboxHandle HANDLE = new SandboxHandle("sbx-1", "c-1");

  @Mock
  private CodingHarness harness;

  private final FrontmatterCodec codec = new FrontmatterCodec();

  private final FakeSandboxService sandbox = new FakeSandboxService();

  @TempDir
  Path workDir;

  @Test
  void teardownExactlyOnceOnCompleted() {
    when(harness.run(any(), anyString(), any())).thenReturn(new HarnessReport(3,
        HarnessReport.Outcome.COMPLETED, HarnessReport.StopReason.COMPLETED, 2, 512L, 0, 0L, 0L));

    AgentResult result = worker().execute(context());

    assertThat(result.outputs()).containsOnlyKeys("patch.diff", "report.md", "trajectory.jsonl");
    assertThat(sandbox.teardowns).isEqualTo(1);
  }

  @Test
  void stepsExceededFailsWithoutThrowing() {
    assertFailedRunRecordsStopReason(HarnessReport.StopReason.STEPS_EXCEEDED);
  }

  @Test
  void formatErrorsExceededFailsWithoutThrowing() {
    assertFailedRunRecordsStopReason(HarnessReport.StopReason.FORMAT_ERRORS_EXCEEDED);
  }

  @Test
  void timeoutFailsWithoutThrowing() {
    assertFailedRunRecordsStopReason(HarnessReport.StopReason.TIMEOUT);
  }

  @Test
  void modelErrorFailsWithoutThrowing() {
    assertFailedRunRecordsStopReason(HarnessReport.StopReason.MODEL_ERROR);
  }

  @Test
  void createFailureSurfacesAsAgentExecutionExceptionWithoutTeardown() {
    sandbox.failCreate = true;

    assertThatThrownBy(() -> worker().execute(context()))
        .isInstanceOf(AgentExecutionException.class);
    assertThat(sandbox.teardowns).isEqualTo(0);
    assertThat(sandbox.calls).containsExactly("create:T-15");
  }

  @Test
  void harnessRuntimeExceptionSurfacesAsAgentExecutionExceptionWithTeardownOnce() {
    when(harness.run(any(), anyString(), any())).thenThrow(new RuntimeException("harness blew up"));

    assertThatThrownBy(() -> worker().execute(context()))
        .isInstanceOf(AgentExecutionException.class);
    assertThat(sandbox.teardowns).isEqualTo(1);
  }

  @Test
  void diffCommandFailureIsInfrastructureFailure() {
    when(harness.run(any(), anyString(), any())).thenReturn(new HarnessReport(3,
        HarnessReport.Outcome.COMPLETED, HarnessReport.StopReason.COMPLETED, 2, 512L, 0, 0L, 0L));
    sandbox.diffExitCode = 1;

    assertThatThrownBy(() -> worker().execute(context()))
        .isInstanceOf(AgentExecutionException.class);
    assertThat(sandbox.teardowns).isEqualTo(1);
  }

  @Test
  void teardownFailureAfterSuccessfulRunIsAgentExecutionException() {
    when(harness.run(any(), anyString(), any())).thenReturn(new HarnessReport(3,
        HarnessReport.Outcome.COMPLETED, HarnessReport.StopReason.COMPLETED, 2, 512L, 0, 0L, 0L));
    sandbox.diffStdout = "[status]\n\n[diff]\n+ok";
    sandbox.failTeardown = true;

    assertThatThrownBy(() -> worker().execute(context()))
        .isInstanceOf(AgentExecutionException.class);
  }

  private void assertFailedRunRecordsStopReason(HarnessReport.StopReason stopReason) {
    when(harness.run(any(), anyString(), any())).thenReturn(new HarnessReport(5,
        HarnessReport.Outcome.FAILED, stopReason, 1, 256L, 0, 0L, 0L));

    AgentResult result = worker().execute(context());

    assertThat(result.outputs()).containsOnlyKeys("patch.diff", "report.md", "trajectory.jsonl");
    JsonNode metadata = codec.parse(result.outputs().get("report.md")).metadata();
    assertThat(metadata.path("outcome").asString()).isEqualTo("FAILED");
    assertThat(metadata.path("stop_reason").asString()).isEqualTo(stopReason.name());
    assertThat(sandbox.teardowns).isEqualTo(1);
  }

  private CodingWorker worker() {
    return new CodingWorker(sandbox, harness, codec);
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
}
