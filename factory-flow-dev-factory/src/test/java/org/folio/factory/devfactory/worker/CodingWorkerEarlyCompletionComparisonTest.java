package org.folio.factory.devfactory.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.api.SandboxSpec;
import org.folio.factory.sandbox.harness.ChatModelAdapter;
import org.folio.factory.sandbox.harness.CodingHarness;
import org.folio.factory.sandbox.harness.HarnessConfig;
import org.folio.factory.sandbox.harness.ModelReply;
import org.folio.factory.sandbox.harness.TaskContract;
import org.folio.factory.sandbox.harness.ToolCall;
import org.folio.factory.sandbox.harness.ToolDispatcher;
import org.folio.factory.sandbox.harness.TokenUsage;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T20 R4 comparison: the same final-response preservation assertions run
 * against a failing-shaped and a successful scripted run.
 *
 * <p>The failing scenario mirrors the T17-LIVE-2 evidence (2026-09-05T16:48Z):
 * two short exploratory turns (list + exec batch, then one exec — no command
 * ran long enough to be the minutes-long {@code mvn} reproduction), then an
 * early plain-text final with zero changes, and the live run's exact token
 * totals (6962 in / 258 out). Observation from that run: the model's final
 * answer (361 chars) was lost — report.md body was empty and the trajectory
 * final record stored only its length; that persistence gap is the defect R1
 * fixes, and this test proves both shapes now keep the answer inspectable.
 * Whether the early bail itself recurs is only decidable with a live model
 * run, whose evidence will now survive.</p>
 */
@ExtendWith(MockitoExtension.class)
class CodingWorkerEarlyCompletionComparisonTest {

  private static final SandboxHandle HANDLE = new SandboxHandle("sbx-t20", "c1");

  private static final String FAILING_FINAL_TEXT =
      "I could not reproduce the Surefire shutdown warning; the build log shows no "
          + "kill-self-fork line, so no change was needed.";

  private static final String SUCCESS_FINAL_TEXT =
      "Root cause: Hibernate's delayed schema drop blocking on dead Testcontainers "
          + "connections in the shutdown hook. Changed ddl-auto from create-drop to "
          + "create in the seven integration tests; mvn -pl factory-core -am test is "
          + "green (37 tests) with no kill-self-fork line.";

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

  private FakeSandboxService sandbox;

  @BeforeEach
  void setUp() {
    sandbox = new FakeSandboxService();
    // T24 B1: finish() derives the result identity from worktreeIdentity;
    // serve one constant tree id so check receipts bind to it.
    lenient().when(gitDiffTool.worktreeIdentity(HANDLE))
        .thenReturn(ToolResult.success(CodingWorkerTest.WORKTREE_TREE_ID));
  }

  @Test
  @org.junit.jupiter.api.Tag("eval-pack")
  void failingShapedEarlyFinalPreservesAnswerStopReasonAndTokens() {
    scriptFailingShapedRun();
    sandbox.diffStdout = "";

    AgentResult result = worker().execute(context());

    assertFinalResponsePreserved(result, FAILING_FINAL_TEXT, "COMPLETED",
        "FAILED", "NO_OP_NOT_PERMITTED", 6962L, 258L, 0);
    assertThat(result.outputs().get("patch.diff")).isEqualTo("(no changes)\n");
  }

  @Test
  @org.junit.jupiter.api.Tag("eval-pack")
  void successfulRunWithVerificationPreservesSameEvidence() {
    sandbox.diffStdout = "[status]\n M a/pom.xml\n M b/pom.xml\n M c/pom.xml\n M d/pom.xml"
        + "\n M e/pom.xml\n M f/pom.xml\n M g/pom.xml\n\n[diff]\n"
        + "diff --git a/pom.xml b/pom.xml\n--- a/pom.xml\n+++ b/pom.xml\n";
    scriptSuccessfulRun();

    AgentResult result = worker()
        .execute(contextWithChecks("cd repo && mvn -pl factory-core -am test -B"));

    assertFinalResponsePreserved(result, SUCCESS_FINAL_TEXT, "COMPLETED",
        "SUCCEEDED", "CHANGES_DELIVERED", 9620L, 402L, 7);
    assertThat(result.outputs().get("patch.diff"))
        .startsWith("diff --git a/pom.xml b/pom.xml");
  }

  /**
   * The one assertion set both scenarios must survive: the model's final text
   * reaches the report.md body and the trajectory final record, the stop
   * reason and T18 task outcome travel in the frozen frontmatter keys, and
   * the actual available token usage is recorded.
   */
  private void assertFinalResponsePreserved(AgentResult result, String expectedFinalText,
      String stopReason, String taskOutcome, String taskOutcomeReason, long tokensIn,
      long tokensOut, int filesChanged) {
    Frontmatter report = codec.parse(result.outputs().get("report.md"));
    assertThat(report.body().trim()).isEqualTo(expectedFinalText);
    JsonNode metadata = report.metadata();
    assertThat(metadata.path("outcome").asString()).isEqualTo("COMPLETED");
    assertThat(metadata.path("stop_reason").asString()).isEqualTo(stopReason);
    assertThat(metadata.path("task_outcome").asString()).isEqualTo(taskOutcome);
    assertThat(metadata.path("task_outcome_reason").asString()).isEqualTo(taskOutcomeReason);
    assertThat(metadata.path("tokens_in").asLong()).isEqualTo(tokensIn);
    assertThat(metadata.path("tokens_out").asLong()).isEqualTo(tokensOut);
    assertThat(metadata.path("files_changed").asInt()).isEqualTo(filesChanged);

    List<String> turns = result.outputs().get("trajectory.jsonl").lines().toList();
    assertThat(turns.get(turns.size() - 2))
        .contains("\"tool\":\"final\"")
        .contains("\"text\":\"" + expectedFinalText + "\"");
    assertThat(turns.get(turns.size() - 1))
        .contains("\"stop_reason\":\"" + stopReason + "\"")
        .contains("\"task_outcome\":\"" + taskOutcome + "\"")
        .contains("\"tokens_in\":" + tokensIn)
        .contains("\"tokens_out\":" + tokensOut);
    assertThat(workDir.resolve("report.md")).hasContent(result.outputs().get("report.md"));
  }

  private void scriptFailingShapedRun() {
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCalls(List.of(
            new ToolCall("t1", "list", "{\"path\":\"repo\"}"),
            new ToolCall("t2", "exec", "{\"cmd\":\"cd repo && git log --oneline -3\"}")),
            new TokenUsage(2311, 41)))
        .thenReturn(ModelReply.toolCalls(List.of(
            new ToolCall("t3", "exec", "{\"cmd\":\"cd repo && rg -n ddl-auto factory-core\"}")),
            new TokenUsage(2246, 87)))
        .thenReturn(ModelReply.text(FAILING_FINAL_TEXT, new TokenUsage(2405, 130)));
    when(listTool.list(HANDLE, "repo", null))
        .thenReturn(ToolResult.success("factory-core\nfactory-sandbox\npom.xml"));
    when(execTool.run(HANDLE, "cd repo && git log --oneline -3", null))
        .thenReturn(ToolResult.success("e06c12d HEAD\n9960035\n53d6f07"));
    when(execTool.run(HANDLE, "cd repo && rg -n ddl-auto factory-core", null))
        .thenReturn(ToolResult.success("7 matches in *IntegrationTest classes"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success("[status]\n\n[diff]\n(no changes)"));
  }

  private void scriptSuccessfulRun() {
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCalls(List.of(
            new ToolCall("t1", "exec", "{\"cmd\":\"cd repo && mvn -pl factory-core -am test -B\"}")),
            new TokenUsage(2311, 41)))
        .thenReturn(ModelReply.toolCalls(List.of(
            new ToolCall("t2", "apply_patch", "{\"diff\":\"ddl-auto create-drop -> create x7\"}")),
            new TokenUsage(2246, 87)))
        .thenReturn(ModelReply.toolCalls(List.of(
            new ToolCall("t3", "exec", "{\"cmd\":\"cd repo && mvn -pl factory-core -am test -B\"}")),
            new TokenUsage(2405, 130)))
        .thenReturn(ModelReply.text(SUCCESS_FINAL_TEXT, new TokenUsage(2658, 144)));
    when(execTool.run(HANDLE, "cd repo && mvn -pl factory-core -am test -B", null))
        .thenReturn(ToolResult.success("Tests run: 37, Failures: 0, Errors: 0"));
    when(applyPatchTool.apply(HANDLE, "ddl-auto create-drop -> create x7"))
        .thenReturn(ToolResult.success("patch applied"));
    when(gitDiffTool.diff(HANDLE)).thenReturn(ToolResult.success(sandbox.diffStdout));
  }

  private CodingWorker worker() {
    return new CodingWorker(sandbox, new CodingHarness(adapter,
        new ToolDispatcher(readTool, listTool, applyPatchTool, execTool, gitDiffTool, testTool),
        gitDiffTool, Clock.fixed(Instant.parse("2026-09-06T10:00:00Z"), Clock.systemUTC().getZone()),
        HarnessConfig.defaults()), codec);
  }

  private AgentContext context() {
    return context(Map.of());
  }

  /** T24: the successful shape's contract declares its verification check. */
  private AgentContext contextWithChecks(String checkCmd) {
    return context(Map.of("checks", List.of(Map.of("id", "tests", "command", checkCmd))));
  }

  private AgentContext context(Map<String, Object> constraints) {
    JsonNode payload = JsonMapper.builder().build().valueToTree(Map.of(
        "taskId", "T-20",
        "repoUrl", "https://github.com/folio/o-r.git",
        "baseBranch", "main",
        "branch", "dev/T20",
        "goal", "Investigate and fix the Surefire shutdown warning",
        "constraints", constraints));
    return new AgentContext(UUID.randomUUID(), "coding", Map.of(), payload,
        Map.of("workDir", workDir.toString()),
        List.of("patch.diff", "report.md", "trajectory.jsonl"));
  }

  private static final class FakeSandboxService implements SandboxService {

    private final List<String> calls = new ArrayList<>();
    private String diffStdout = "";
    private int diffExitCode;

    @Override
    public SandboxHandle create(SandboxSpec spec) {
      calls.add("create:" + spec.taskId());
      return HANDLE;
    }

    @Override
    public CommandResult exec(SandboxHandle handle, String command, long timeoutSec) {
      calls.add("exec:" + command);
      if (CodingWorkerTest.REV_PARSE_COMMAND.equals(command)) {
        return new CommandResult(0, CodingWorkerTest.BASE_SHA + "\n", "", 5L);
      }
      if (CodingWorkerTest.exportCommand(CodingWorkerTest.BASE_SHA).equals(command)) {
        return new CommandResult(diffExitCode, diffStdout, "", 5L);
      }
      return new CommandResult(1, "", "unexpected command", 0L);
    }

    @Override
    public void teardown(SandboxHandle handle) {
      calls.add("teardown:" + handle.sandboxId());
    }
  }
}
