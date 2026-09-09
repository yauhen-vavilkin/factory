package org.folio.factory.devfactory.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.agent.AgentWorkerRegistry;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.engine.ExecutionEngine;
import org.folio.factory.core.engine.HitlGateOpener;
import org.folio.factory.core.engine.SubFlowInvoker;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.RetryPolicy;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.worker.recovery.RecoveryBundleStore;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.core.LocalSandboxService;
import org.folio.factory.sandbox.core.SandboxProperties;
import org.folio.factory.sandbox.harness.CodingHarness;
import org.folio.factory.sandbox.harness.HarnessConfig;
import org.folio.factory.sandbox.harness.HarnessReport;
import org.folio.factory.sandbox.harness.ModelReply;
import org.folio.factory.sandbox.harness.TaskContract;
import org.folio.factory.sandbox.harness.TaskOutcome;
import org.folio.factory.sandbox.harness.TokenUsage;
import org.folio.factory.sandbox.harness.ToolCall;
import org.folio.factory.sandbox.harness.ToolDispatcher;
import org.folio.factory.sandbox.harness.Trajectory;
import org.folio.factory.sandbox.tools.ApplyPatchTool;
import org.folio.factory.sandbox.tools.ExecTool;
import org.folio.factory.sandbox.tools.GitDiffTool;
import org.folio.factory.sandbox.tools.ListTool;
import org.folio.factory.sandbox.tools.OutputLimiter;
import org.folio.factory.sandbox.tools.ReadTool;
import org.folio.factory.sandbox.tools.TestTool;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T21 R4 honest pack summary, hermetic: renders the summary from the runs'
 * actually persisted report.md data — accepted outcome, tokens and measured
 * wall time — with {@code unknown} wherever the scripted provider reported no
 * usage. The assertions pin the honesty rules: outcomes come from the
 * persisted task_outcome (never from how many tests passed), token numbers
 * are only shown when the provider supplied them, and every duration is a
 * real measurement. Nothing here contacts a live provider.
 */
@Tag("eval-pack")
class EvalPackHermeticSummaryTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final FrontmatterCodec codec = new FrontmatterCodec();

  @TempDir
  Path root;

  @Test
  void summaryReportsPersistedOutcomesActualTokensOrUnknownAndMeasuredDurations() throws Exception {
    EvalPackScenarios pack = new EvalPackScenarios(root);

    EvalPackScenarios.PackRun regression = regressionRun(pack);
    EvalPackScenarios.PackRun earlyBail = earlyBailRun(pack);
    EvalPackScenarios.PackRun greenNoChange = greenNoChangeRun(pack);

    String summary = render(regression, earlyBail, greenNoChange);
    System.out.println(summary);
    Path summaryFile = Path.of("target", "eval-pack-summary.md");
    Files.createDirectories(summaryFile.getParent());
    Files.writeString(summaryFile, summary);

    assertThat(summary)
        .contains("provider: scripted ChatModel (hermetic)")
        .contains("live-provider runs in this pack: none")
        .contains("outcome source: persisted report.md task_outcome (never test counts)")
        .contains("tokens: numbers are the provider's reported usage; "
            + "`unknown` means the provider reported no usage")
        .contains("| REGRESSION-SUM | SUCCEEDED (CHANGES_DELIVERED) | COMPLETED | 5500 | 383 |")
        .contains("| EARLY-NO-REPRO-SUM | FAILED (NO_OP_NOT_PERMITTED) | COMPLETED "
            + "| 1150 | 100 |")
        .contains("| GREEN-NO-CHANGE-SUM | FAILED (NO_OP_NOT_PERMITTED) | COMPLETED "
            + "| unknown | unknown |");
    assertThat(summary)
        .as("a run whose scripted provider reported no usage must never show a fabricated "
            + "token number")
        .doesNotContain("| unknown | 0 |")
        .doesNotContain("| 0 | unknown |");
    List<String> rows = summary.lines()
        .filter(line -> line.startsWith("| ") && !line.startsWith("| scenario")
            && !line.startsWith("|---"))
        .toList();
    assertThat(rows).as("exactly the three pack scenarios").hasSize(3);
    for (String row : rows) {
      assertThat(row).as("every scenario row must carry a measured duration: %s", row)
          .matches("\\| [A-Z-]+ \\| (SUCCEEDED|FAILED) \\([A-Z_]+\\) \\| [A-Z_]+ \\| "
              + "(unknown|\\d+) \\| (unknown|\\d+) \\| \\d+ \\|");
    }
    assertThat(summary)
        .as("summary rows reflect the persisted outcomes, not in-memory callbacks")
        .contains(persistedRow(regression))
        .contains(persistedRow(earlyBail))
        .contains(persistedRow(greenNoChange));
    assertThat(Files.readString(summaryFile)).isEqualTo(summary);
  }

  private String persistedRow(EvalPackScenarios.PackRun run) {
    JsonNode metadata = codec.parse(run.persistedReport()).metadata();
    return "| " + run.id() + " | " + metadata.path("task_outcome").asString()
        + " (" + metadata.path("task_outcome_reason").asString() + ") | "
        + metadata.path("stop_reason").asString() + " | ";
  }

  private String render(EvalPackScenarios.PackRun... runs) {
    StringBuilder summary = new StringBuilder("""
        # Dev Factory eval pack — run summary (hermetic)

        provider: scripted ChatModel (hermetic); live-provider runs in this pack: none
        outcome source: persisted report.md task_outcome (never test counts)
        tokens: numbers are the provider's reported usage; `unknown` means the provider reported no usage
        durations: measured wall time of the full coding-worker execution

        | scenario | acceptance | stop_reason | tokens_in | tokens_out | wall_ms |
        |---|---|---|---|---|---|
        """);
    for (EvalPackScenarios.PackRun run : runs) {
      JsonNode metadata = codec.parse(run.persistedReport()).metadata();
      String acceptance = metadata.path("task_outcome").asString() + " ("
          + metadata.path("task_outcome_reason").asString() + ")";
      String tokensIn = tokenCell(metadata.path("tokens_in").asLong(),
          metadata.path("tokens_out").asLong(), metadata.path("tokens_in").asLong());
      String tokensOut = tokenCell(metadata.path("tokens_in").asLong(),
          metadata.path("tokens_out").asLong(), metadata.path("tokens_out").asLong());
      summary.append("| ").append(run.id())
          .append(" | ").append(acceptance)
          .append(" | ").append(metadata.path("stop_reason").asString())
          .append(" | ").append(tokensIn)
          .append(" | ").append(tokensOut)
          .append(" | ").append(run.durationMs())
          .append(" |\n");
    }
    return summary.toString();
  }

  /**
   * A run whose scripted provider reported no usage (both totals zero) is
   * rendered as {@code unknown} — a real model turn always consumes tokens,
   * so printing 0 would fabricate a measurement that was never made.
   */
  private static String tokenCell(long tokensIn, long tokensOut, long cell) {
    return tokensIn == 0 && tokensOut == 0 ? "unknown" : Long.toString(cell);
  }

  private EvalPackScenarios.PackRun regressionRun(EvalPackScenarios pack) throws Exception {
    Path fixture = pack.createGreetingFixture("REGRESSION-SUM", "Goodbye, $1.");
    ObjectNode contract = JSON.createObjectNode()
        .put("goal", EvalPackScenarios.REGRESSION_GOAL);
    ArrayNode acceptance = contract.putArray("acceptance");
    EvalPackScenarios.REGRESSION_ACCEPTANCE.lines()
        .filter(line -> !line.isBlank()).forEach(acceptance::add);
    // T24: the discriminating check is a declared contract obligation; the
    // scripted verify turn (t3, after the patch) discharges it fresh.
    contract.putObject("constraints").putArray("checks").addObject()
        .put("id", "check").put("command", "cd repo && sh check.sh");
    contract.put("notes", EvalPackScenarios.REGRESSION_NOTES);
    return pack.run("REGRESSION-SUM", fixture, contract, List.of(
        ModelReply.toolCalls(List.of(new ToolCall("t1", "exec",
            execArgs("cd repo && sh check.sh"))), new TokenUsage(1200, 48)),
        ModelReply.toolCalls(List.of(new ToolCall("t2", "apply_patch",
            patchArgs(EvalPackScenarios.GREETING_FIX))), new TokenUsage(2100, 96)),
        ModelReply.toolCalls(List.of(new ToolCall("t3", "exec",
            execArgs("cd repo && sh check.sh"))), new TokenUsage(1900, 64)),
        ModelReply.text("Fixed greet.sh and verified sh check.sh passes.",
            new TokenUsage(300, 175))));
  }

  private EvalPackScenarios.PackRun earlyBailRun(EvalPackScenarios pack) throws Exception {
    Path fixture = pack.createGreetingFixture("EARLY-NO-REPRO-SUM", "Goodbye, $1.");
    ObjectNode contract = JSON.createObjectNode()
        .put("goal", EvalPackScenarios.REGRESSION_GOAL);
    contract.putArray("acceptance").add("sh check.sh exits 0 after the change");
    return pack.run("EARLY-NO-REPRO-SUM", fixture, contract, List.of(
        ModelReply.toolCalls(List.of(new ToolCall("t1", "exec",
            execArgs("cd repo && git log --oneline -3"))), new TokenUsage(900, 40)),
        ModelReply.text("Could not reproduce; no change needed.",
            new TokenUsage(250, 60))));
  }

  /**
   * The tests-green-but-no-change counterexample, replayed with a script
   * that reports no token usage so the summary must mark it unknown.
   */
  private EvalPackScenarios.PackRun greenNoChangeRun(EvalPackScenarios pack) throws Exception {
    Path fixture = pack.createGreetingFixture("GREEN-NO-CHANGE-SUM", "Hello, $1.");
    ObjectNode contract = JSON.createObjectNode().put("goal",
        "Add farewell.sh printing 'Goodbye, <name>.' and extend check.sh to verify it.");
    contract.putArray("acceptance").add("sh check.sh verifies both greeting and farewell");
    return pack.run("GREEN-NO-CHANGE-SUM", fixture, contract, List.of(
        new ModelReply(null, List.of(new ToolCall("t1", "exec",
            execArgs("cd repo && sh check.sh"))), null),
        ModelReply.text("All tests pass; no change needed.")));
  }

  private static String execArgs(String cmd) {
    ObjectNode args = JSON.createObjectNode();
    args.put("cmd", cmd);
    return args.toString();
  }

  private static String patchArgs(String diff) {
    ObjectNode args = JSON.createObjectNode();
    args.put("diff", diff);
    return args.toString();
  }

  // ---------------------------------------------------------------------
  // T26 S03 additive extension (appended; everything above is
  // byte-unchanged, including the T21 test method).
  // ---------------------------------------------------------------------

  /** Output file for the T24/T25 counterexample rows (never the T21 file). */
  private static final Path EXTENDED_SUMMARY =
      Path.of("target", "eval-pack-summary-t24-t25.md");

  private static final String MVNW_CHECK = "cd repo && sh mvnw -B test";
  private static final String CHECK_SH = "cd repo && sh check.sh";
  private static final String S03_FLOW_ID = "t24-t25-summary-requeue-flow";
  private static final List<String> DECLARED_OUTPUTS =
      List.of("patch.diff", "report.md", "trajectory.jsonl");

  /** Cosmetic README-only change: keeps filesChanged=1 without fixing greet.sh. */
  private static final String README_COSMETIC_COMMENT = String.join("\n",
      "diff --git a/README.md b/README.md",
      "--- a/README.md",
      "+++ b/README.md",
      "@@ -1,4 +1,4 @@",
      " # Greeting service",
      " ",
      " Spec: `sh greet.sh <name>` prints `Hello, <name>.`",
      "-`sh check.sh` verifies the spec and is the task's observable criterion.",
      "+`sh check.sh` verifies the spec and is the task's observable criterion. (cosmetic note)",
      "");

  /** The post-check tracked re-edit: the verified Hello fix becomes Howdy. */
  private static final String GREETING_HOWDY_REEDIT = String.join("\n",
      "diff --git a/greet.sh b/greet.sh",
      "--- a/greet.sh",
      "+++ b/greet.sh",
      "@@ -1,2 +1,2 @@",
      " #!/bin/sh",
      "-echo \"Hello, $1.\"",
      "+echo \"Howdy, $1.\"",
      "");

  /** One rendered row: the id plus cells already resolved from persisted sources. */
  private record SummaryRow(String id, String acceptance, String stopReason,
      String tokensIn, String tokensOut, long wallMs) { }

  /**
   * T26 S03: the honest summary extended to the new membership. Re-runs
   * compact -TSUM/-DSUM variants of the six T24/T25 counterexamples (same
   * scripts/obstructions as the S01/S02 members, distinct ids under this
   * test's {@code @TempDir}) through the real worker/engine machinery and
   * renders their rows exactly like the T21 render: T24 rows from the
   * persisted report.md metadata, T25 rows from the persisted recovery-bundle
   * manifest (tokens {@code unknown} — the stubbed-harness/provider-less
   * compact runs report no usage — never a fabricated number), durations
   * measured with {@code System.nanoTime} around the worker/engine
   * execution. Writes {@code target/eval-pack-summary-t24-t25.md}; the T21
   * method's {@code target/eval-pack-summary.md} is untouched.
   */
  @Test
  void extendedSummaryCoversT24T25CounterexampleRowsWithHonestyRules() throws Exception {
    EvalPackScenarios pack = new EvalPackScenarios(root);

    EvalPackScenarios.PackRun absent = t24AbsentEvidenceRunWithUsage(pack);
    EvalPackScenarios.PackRun truncated = t24TruncatedUsageLessRun(pack);
    EvalPackScenarios.PackRun drift = t24PostCheckDriftRun(pack);
    assertPersistedRejection(absent, "REQUIRED_CHECK_FAILED");
    assertPersistedRejection(truncated, "REQUIRED_CHECK_FAILED");
    assertPersistedRejection(drift, "REQUIRED_CHECK_STALE");

    SummaryRow export = t25ExportFailureRow();
    SummaryRow terminal = t25TerminalExceptionRow();
    SummaryRow requeue = t25RequeueOrdinalRow();

    String summary = renderExtended(List.of(absent, truncated, drift),
        export, terminal, requeue);
    System.out.println(summary);
    Files.createDirectories(EXTENDED_SUMMARY.getParent());
    Files.writeString(EXTENDED_SUMMARY, summary);

    assertThat(summary)
        .contains("| T24SUM-ABSENT | FAILED (REQUIRED_CHECK_FAILED) | COMPLETED "
            + "| 4000 | 280 |")
        .contains("| T24SUM-TRUNC | FAILED (REQUIRED_CHECK_FAILED) | COMPLETED "
            + "| unknown | unknown |")
        .contains("| T24SUM-DRIFT | FAILED (REQUIRED_CHECK_STALE) | COMPLETED "
            + "| 5980 | 398 |")
        .contains("| T25DSUM-EXPORT | FAILED (EXPORT_FAILED) | COMPLETED "
            + "| unknown | unknown |")
        .contains("| T25DSUM-TERMINAL | FAILED (TERMINAL_EXCEPTION) | unknown "
            + "| unknown | unknown |")
        .contains("| T25DSUM-REQUEUE | FAILED (PERSIST_FAILED) | unknown "
            + "| unknown | unknown |");
    assertThat(summary)
        .as("the specific new rejection reasons must appear in the extended summary")
        .contains("REQUIRED_CHECK_FAILED", "REQUIRED_CHECK_STALE", "EXPORT_FAILED",
            "TERMINAL_EXCEPTION", "PERSIST_FAILED");
    assertThat(summary)
        .as("no fabricated token number may sit next to an unknown measurement")
        .doesNotContain("| unknown | 0 |")
        .doesNotContain("| 0 | unknown |");
    List<String> rows = summary.lines()
        .filter(line -> line.startsWith("| ") && !line.startsWith("| scenario")
            && !line.startsWith("|---"))
        .toList();
    assertThat(rows).as("exactly the six new members' rows").hasSize(6);
    for (String row : rows) {
      assertThat(row).as("every extended row keeps the honest pack-row shape: %s", row)
          .matches("\\| [A-Z0-9-]+ \\| FAILED \\([A-Z_]+\\) \\| ([A-Z_]+|unknown) \\| "
              + "(unknown|\\d+) \\| (unknown|\\d+) \\| \\d+ \\|");
    }
    assertThat(summary)
        .as("extended rows reflect the persisted outcomes, not in-memory callbacks")
        .contains(persistedRow(absent))
        .contains(persistedRow(truncated))
        .contains(persistedRow(drift));
    assertThat(Files.readString(EXTENDED_SUMMARY)).isEqualTo(summary);
  }

  private void assertPersistedRejection(EvalPackScenarios.PackRun run, String reason) {
    JsonNode metadata = codec.parse(run.persistedReport()).metadata();
    assertThat(metadata.path("task_outcome").asString())
        .as("%s: the persisted outcome behind the summary row", run.id())
        .isEqualTo("FAILED");
    assertThat(metadata.path("task_outcome_reason").asString())
        .as("%s: the persisted rejection reason behind the summary row", run.id())
        .isEqualTo(reason);
  }

  /**
   * The extended render: T24 rows exactly like the T21 render (persisted
   * report.md metadata and the same {@code tokenCell} honesty rule), T25
   * rows from the persisted recovery-bundle manifest and bundled trajectory.
   */
  private String renderExtended(List<EvalPackScenarios.PackRun> t24Runs,
      SummaryRow... t25Rows) {
    StringBuilder summary = new StringBuilder("""
        # Dev Factory eval pack — T24/T25 counterexample summary (hermetic)

        provider: scripted ChatModel / scripted harness seam (hermetic); live-provider runs in this pack: none
        outcome source: persisted report.md task_outcome / recovery-bundle manifest failure_reason (never test counts)
        tokens: numbers are the provider's reported usage; `unknown` means the provider reported no usage
        durations: measured wall time of the full coding-worker/engine execution
        T25 rows: acceptance from the persisted recovery-bundle manifest; stop_reason from the run shape; tokens unknown (no usage record)

        | scenario | acceptance | stop_reason | tokens_in | tokens_out | wall_ms |
        |---|---|---|---|---|---|
        """);
    for (EvalPackScenarios.PackRun run : t24Runs) {
      JsonNode metadata = codec.parse(run.persistedReport()).metadata();
      String acceptance = metadata.path("task_outcome").asString() + " ("
          + metadata.path("task_outcome_reason").asString() + ")";
      String tokensIn = tokenCell(metadata.path("tokens_in").asLong(),
          metadata.path("tokens_out").asLong(), metadata.path("tokens_in").asLong());
      String tokensOut = tokenCell(metadata.path("tokens_in").asLong(),
          metadata.path("tokens_out").asLong(), metadata.path("tokens_out").asLong());
      summary.append("| ").append(run.id())
          .append(" | ").append(acceptance)
          .append(" | ").append(metadata.path("stop_reason").asString())
          .append(" | ").append(tokensIn)
          .append(" | ").append(tokensOut)
          .append(" | ").append(run.durationMs())
          .append(" |\n");
    }
    for (SummaryRow row : t25Rows) {
      summary.append("| ").append(row.id())
          .append(" | ").append(row.acceptance())
          .append(" | ").append(row.stopReason())
          .append(" | ").append(row.tokensIn())
          .append(" | ").append(row.tokensOut())
          .append(" | ").append(row.wallMs())
          .append(" |\n");
    }
    return summary.toString();
  }

  /** A T25 row resolved from the persisted bundle manifest and bundled trajectory. */
  private SummaryRow t25Row(String id, RecoveryBundleStore.RecoveryBundle bundle, long wallMs)
      throws IOException {
    JsonNode reportRecord = bundledReportRecord(bundle.directory());
    String stopReason = reportRecord == null
        ? "unknown" : reportRecord.path("stop_reason").asString("unknown");
    long tokensIn = reportRecord == null ? 0L : reportRecord.path("tokens_in").asLong();
    long tokensOut = reportRecord == null ? 0L : reportRecord.path("tokens_out").asLong();
    return new SummaryRow(id, "FAILED (" + bundle.failureReason() + ")", stopReason,
        tokenCell(tokensIn, tokensOut, tokensIn), tokenCell(tokensIn, tokensOut, tokensOut),
        wallMs);
  }

  /**
   * The bundled trajectory's final report record (the harness's own terminal
   * accounting), or {@code null} when the run died before any report record
   * was written — the honesty boundary for stop_reason/tokens.
   */
  private static JsonNode bundledReportRecord(Path bundleDir) throws IOException {
    JsonNode last = null;
    for (String line : Files.readAllLines(bundleDir.resolve(Trajectory.FILE_NAME))) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode node = JSON.readTree(line);
      if (node.has("stop_reason")) {
        last = node;
      }
    }
    return last;
  }

  /** T24 compact re-run, changed path, absent evidence, WITH scripted usage. */
  private EvalPackScenarios.PackRun t24AbsentEvidenceRunWithUsage(EvalPackScenarios pack)
      throws Exception {
    Path fixture = pack.createMavenCheckFixture("T24SUM-ABSENT",
        "[INFO] No tests to run.\n[INFO] BUILD SUCCESS");
    return pack.run("T24SUM-ABSENT", fixture, t24Contract(false, MVNW_CHECK), List.of(
        ModelReply.toolCalls(List.of(new ToolCall("t1", "apply_patch",
            EvalPackScenarios.patchArgs(README_COSMETIC_COMMENT))), new TokenUsage(1500, 70)),
        ModelReply.toolCalls(List.of(new ToolCall("t2", "exec",
            EvalPackScenarios.execArgs(MVNW_CHECK))), new TokenUsage(2200, 90)),
        ModelReply.text("mvnw -B test reported BUILD SUCCESS — the required check is green.",
            new TokenUsage(300, 120))));
  }

  /** T24 compact re-run, changed path, real truncation, USAGE-LESS script. */
  private EvalPackScenarios.PackRun t24TruncatedUsageLessRun(EvalPackScenarios pack)
      throws Exception {
    String body = "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\n"
        + "[INFO] progress\n".repeat(3400)
        + "Tests run: 1, Failures: 0, Errors: 0, Skipped: 1\n"
        + "[INFO] BUILD SUCCESS";
    assertThat(body.getBytes(StandardCharsets.UTF_8).length)
        .as("the fixture body must overflow the real OutputLimiter bound so the "
            + "truncation marker comes from the real ExecTool")
        .isGreaterThan(OutputLimiter.MAX_OUTPUT_BYTES);
    Path fixture = pack.createMavenCheckFixture("T24SUM-TRUNC", body);
    return pack.run("T24SUM-TRUNC", fixture, t24Contract(false, MVNW_CHECK), List.of(
        new ModelReply(null, List.of(new ToolCall("t1", "apply_patch",
            EvalPackScenarios.patchArgs(README_COSMETIC_COMMENT))), null),
        new ModelReply(null, List.of(new ToolCall("t2", "exec",
            EvalPackScenarios.execArgs(MVNW_CHECK))), null),
        ModelReply.text("The build log shows a clean summary and BUILD SUCCESS.")));
  }

  /** T24 compact re-run: post-check tracked re-edit (identity drift), changed path. */
  private EvalPackScenarios.PackRun t24PostCheckDriftRun(EvalPackScenarios pack)
      throws Exception {
    Path fixture = pack.createGreetingFixture("T24SUM-DRIFT", "Goodbye, $1.");
    return pack.run("T24SUM-DRIFT", fixture, t24Contract(false, CHECK_SH), List.of(
        ModelReply.toolCalls(List.of(new ToolCall("t1", "apply_patch",
            EvalPackScenarios.patchArgs(EvalPackScenarios.GREETING_FIX))),
            new TokenUsage(2100, 96)),
        ModelReply.toolCalls(List.of(new ToolCall("t2", "exec",
            EvalPackScenarios.execArgs(CHECK_SH))), new TokenUsage(1900, 64)),
        ModelReply.toolCalls(List.of(new ToolCall("t3", "apply_patch",
            EvalPackScenarios.patchArgs(GREETING_HOWDY_REEDIT))), new TokenUsage(1700, 88)),
        ModelReply.text("Fixed and verified; the final wording tweak is cosmetic.",
            new TokenUsage(280, 150))));
  }

  private ObjectNode t24Contract(boolean allowNoop, String checkCommand) {
    ObjectNode contract = JSON.createObjectNode()
        .put("goal", EvalPackScenarios.REGRESSION_GOAL);
    ArrayNode acceptance = contract.putArray("acceptance");
    EvalPackScenarios.REGRESSION_ACCEPTANCE.lines()
        .filter(line -> !line.isBlank()).forEach(acceptance::add);
    ObjectNode constraints = contract.putObject("constraints");
    if (allowNoop) {
      constraints.put("allow_noop", true);
    }
    constraints.putArray("checks").addObject()
        .put("id", "tests").put("command", checkCommand);
    contract.put("notes", EvalPackScenarios.REGRESSION_NOTES);
    return contract;
  }

  /** T25 compact re-run: export failure as DATA (rm -rf .git exec turn). */
  private SummaryRow t25ExportFailureRow() throws Exception {
    String taskId = "T25DSUM-EXPORT";
    EvalPackScenarios pack = new EvalPackScenarios(root);
    Path fixture = pack.createGreetingFixture(taskId, "Goodbye, $1.");
    SandboxService sandbox = new LocalSandboxService(new SandboxProperties("local", null, null,
        root.resolve("sbx-root-" + taskId), Duration.ZERO, null), Clock.systemUTC());
    RecoveryBundleStore recovery = new RecoveryBundleStore(root.resolve("recovery"));
    GitDiffTool gitDiffTool = new GitDiffTool(sandbox);
    CodingHarness harness = new CodingHarness(
        new EvalPackScenarios.ScriptedModel(taskId, List.of(
            new ModelReply(null, List.of(new ToolCall("t1", "apply_patch",
                EvalPackScenarios.patchArgs(EvalPackScenarios.GREETING_FIX))), null),
            new ModelReply(null, List.of(new ToolCall("t2", "exec",
                EvalPackScenarios.execArgs(
                    "cd repo && printf 'dsum-export-unique-bytes\\n' > unique.txt"))), null),
            new ModelReply(null, List.of(new ToolCall("t3", "exec",
                EvalPackScenarios.execArgs("cd repo && rm -rf .git"))), null),
            ModelReply.text("Fixed the greeting and recorded the change."))),
        new ToolDispatcher(new ReadTool(sandbox), new ListTool(sandbox),
            new ApplyPatchTool(sandbox), new ExecTool(sandbox), gitDiffTool,
            new TestTool(sandbox)),
        gitDiffTool, Clock.systemUTC(), HarnessConfig.defaults());
    UUID executionId = UUID.randomUUID();
    CodingWorker worker = new CodingWorker(sandbox, harness, codec, recovery);
    AgentContext context =
        new AgentContext(executionId, "coding", Map.of(),
            t25Payload(taskId, fixture), Map.of(), DECLARED_OUTPUTS, 1);

    long started = System.nanoTime();
    AgentExecutionException failure = null;
    try {
      worker.execute(context);
    } catch (AgentExecutionException e) {
      failure = e;
    }
    long wallMs = (System.nanoTime() - started) / 1_000_000L;

    RecoveryBundleStore.RecoveryBundle bundle =
        recovery.find(executionId, "coding", 1).orElseThrow();
    assertThat(failure)
        .as("the destroyed .git export failure must surface as the terminal exception")
        .isNotNull();
    assertThat(bundle.completeness()).isEqualTo("INCOMPLETE");
    assertThat(bundle.failureReason()).isEqualTo("EXPORT_FAILED");
    return t25Row(taskId, bundle, wallMs);
  }

  /**
   * T25 compact re-run: terminal exception after coding work (scripted-harness
   * seam — a real model turn cannot reach the failure point).
   */
  private SummaryRow t25TerminalExceptionRow() throws Exception {
    String taskId = "T25DSUM-TERMINAL";
    new EvalPackScenarios(root).createGreetingFixture(taskId, "Goodbye, $1.");
    SandboxService sandbox = new LocalSandboxService(new SandboxProperties("local", null, null,
        root.resolve("sbx-root-" + taskId), Duration.ZERO, null), Clock.systemUTC());
    RecoveryBundleStore recovery = new RecoveryBundleStore(root.resolve("recovery"));
    UUID executionId = UUID.randomUUID();
    CodingHarness harness = mock(CodingHarness.class);
    when(harness.run(any(), any(TaskContract.class), any())).thenAnswer(invocation -> {
      SandboxHandle handle = invocation.getArgument(0);
      Path workDir = invocation.getArgument(2);
      Files.createDirectories(workDir);
      Files.writeString(workDir.resolve(Trajectory.FILE_NAME),
          "{\"ts\":\"2026-09-09T10:00:00Z\",\"step\":1,\"tool\":\"exec\",\"ok\":true}\n");
      exec(sandbox, handle, "cd repo && printf 'dsum-terminal-unique-bytes\\n' > unique.txt");
      throw new RuntimeException("terminal exception after coding work exists");
    });
    CodingWorker worker = new CodingWorker(sandbox, harness, codec, recovery);
    AgentContext context =
        new AgentContext(executionId, "coding", Map.of(),
            t25Payload(taskId, root.resolve(taskId)), Map.of(), DECLARED_OUTPUTS, 1);

    long started = System.nanoTime();
    AgentExecutionException failure = null;
    try {
      worker.execute(context);
    } catch (AgentExecutionException e) {
      failure = e;
    }
    long wallMs = (System.nanoTime() - started) / 1_000_000L;

    assertThat(failure)
        .as("the execution-free worker surfaces the infrastructure failure")
        .isNotNull();
    assertThat(failure.getMessage()).contains("infrastructure failure");
    RecoveryBundleStore.RecoveryBundle bundle =
        recovery.find(executionId, "coding", 1).orElseThrow();
    assertThat(bundle.completeness()).isEqualTo("INCOMPLETE");
    assertThat(bundle.failureReason()).isEqualTo("TERMINAL_EXCEPTION");
    return t25Row(taskId, bundle, wallMs);
  }

  /**
   * T25 compact re-run: the attempt-ordinal requeue shape through the real
   * engine and the real HitlDecisionService approval route, with the same
   * host-side persist obstruction (a directory where report.md should land).
   */
  private SummaryRow t25RequeueOrdinalRow() throws Exception {
    RequeueRig rig = new RequeueRig("T25DSUM-REQUEUE");
    rig.stubAttempt((handle, workDir) -> {
      exec(rig.sandbox, handle,
          "cd repo && printf 'dsum-requeue-unique-bytes\\n' > unique-b3.txt");
      try {
        Files.createDirectory(workDir.resolve("report.md"));
      } catch (IOException e) {
        throw new IllegalStateException("could not create the persist obstruction", e);
      }
    });

    long started = System.nanoTime();
    rig.engine().advance(rig.executionId());
    assertThat(rig.execution().getStatus())
        .as("a maxAttempts-1 policy escalates the single PERSIST_FAILED attempt")
        .isEqualTo(ExecutionStatus.FAILED_ESCALATED);
    RecoveryBundleStore.RecoveryBundle first = rig.bundle(1).orElseThrow();
    rig.approveEscalation();
    assertThat(rig.execution().getStatus()).isEqualTo(ExecutionStatus.PENDING);
    rig.execution().setStatus(ExecutionStatus.RUNNING);
    rig.stubAttempt((handle, workDir) ->
        exec(rig.sandbox, handle, "cd repo && printf 'public class A2 {}\\n' > A2.java"));
    rig.engine().advance(rig.executionId());
    long wallMs = (System.nanoTime() - started) / 1_000_000L;

    assertThat(rig.worker.attempts)
        .as("the escalation-approved requeue runs as attempt 2, never reuse of attempt 1")
        .containsExactly(1, 2);
    assertThat(first.completeness()).isEqualTo("INCOMPLETE");
    assertThat(first.failureReason()).isEqualTo("PERSIST_FAILED");
    return t25Row("T25DSUM-REQUEUE", first, wallMs);
  }

  private static ObjectNode t25Payload(String taskId, Path fixture) {
    ObjectNode payload = JSON.createObjectNode();
    payload.put("taskId", taskId)
        .put("repoUrl", fixture.toAbsolutePath().toString())
        .put("baseBranch", "main")
        .put("branch", "dev/" + taskId)
        .put("goal", EvalPackScenarios.REGRESSION_GOAL);
    return payload;
  }

  private static void exec(SandboxService sandbox, SandboxHandle handle, String command) {
    var result = sandbox.exec(handle, command, 60L);
    assertThat(result.ok())
        .as("scenario arrange command failed: %s%nstdout=%s%nstderr=%s",
            command, result.stdout(), result.stderr())
        .isTrue();
  }

  /** Delegates to the real worker while recording the attempts it saw. */
  private static final class RecordingWorker implements AgentWorker {

    private final AgentWorker delegate;
    private final List<Integer> attempts = new ArrayList<>();

    RecordingWorker(AgentWorker delegate) {
      this.delegate = delegate;
    }

    @Override
    public String id() {
      return delegate.id();
    }

    @Override
    public AgentResult execute(
        AgentContext context) {
      attempts.add(context.attempt());
      return delegate.execute(context);
    }
  }

  /**
   * Compact port of the S02 durability engine rig: a real ExecutionEngine
   * over a real AgentWorkerRegistry holding a real CodingWorker with a real
   * LocalSandboxService and a real file RecoveryBundleStore, a fake in-memory
   * StateManager whose attempt allocator never resets, mocked audit and
   * artifact store, and the real HitlDecisionService approval route over a
   * mocked review repository.
   */
  private final class RequeueRig {

    final String taskId;
    final RecoveryBundleStore recovery = new RecoveryBundleStore(root.resolve("recovery"));
    final SandboxService sandbox;
    final FakeStateManager state;
    final AuditLog audit = mock(AuditLog.class);
    final ArtifactStore artifactStore = mock(ArtifactStore.class);
    final CodingHarness harness = mock(CodingHarness.class);
    final RecordingWorker worker;

    RequeueRig(String taskId) throws Exception {
      this.taskId = taskId;
      new EvalPackScenarios(root).createGreetingFixture(taskId, "Goodbye, $1.");
      this.sandbox = new LocalSandboxService(new SandboxProperties("local", null, null,
          root.resolve("sbx-root-" + taskId), Duration.ZERO, null), Clock.systemUTC());
      this.state = new FakeStateManager(t25Payload(taskId, root.resolve(taskId)).toString());
      this.worker = new RecordingWorker(
          new CodingWorker(sandbox, harness, codec, recovery));
    }

    void stubAttempt(BiConsumer<SandboxHandle, Path> turns) {
      when(harness.run(any(), any(TaskContract.class), any())).thenAnswer(invocation -> {
        SandboxHandle handle = invocation.getArgument(0);
        Path workDir = invocation.getArgument(2);
        Files.createDirectories(workDir);
        Files.writeString(workDir.resolve(Trajectory.FILE_NAME),
            "{\"ts\":\"2026-09-09T10:00:00Z\",\"step\":1,\"tool\":\"exec\",\"ok\":true}\n");
        turns.accept(handle, workDir);
        return new HarnessReport(3, HarnessReport.Outcome.COMPLETED,
            HarnessReport.StopReason.COMPLETED, 2, 128L, 0, 0L, 0L,
            TaskOutcome.SUCCEEDED, TaskOutcome.Reason.CHANGES_DELIVERED, "");
      });
    }

    ExecutionEngine engine() {
      StepDescriptor step = new StepDescriptor("coding", StepType.AGENT, worker.id(),
          null, null, List.of(StepDescriptor.TRIGGER_INPUT), DECLARED_OUTPUTS, Map.of());
      FlowDescriptor flow = new FlowDescriptor(S03_FLOW_ID, "T24/T25 Summary Requeue Flow",
          "1.0.0", List.of(), null, null, List.of(step), new RetryPolicy(1, List.of(1L)));
      FlowRegistry flowRegistry = mock(FlowRegistry.class);
      when(flowRegistry.require(S03_FLOW_ID)).thenReturn(flow);
      return new ExecutionEngine(flowRegistry,
          new AgentWorkerRegistry(List.of(worker), flowRegistry),
          state, artifactStore, audit, mock(HitlGateOpener.class), mock(SubFlowInvoker.class),
          List.of(), JSON, List.of(recovery));
    }

    /** The real escalation-approval route, exactly as the review inbox would decide. */
    void approveEscalation() {
      StepDescriptor step = new StepDescriptor("coding", StepType.AGENT, worker.id(),
          null, null, List.of(StepDescriptor.TRIGGER_INPUT), DECLARED_OUTPUTS, Map.of());
      FlowDescriptor flow = new FlowDescriptor(S03_FLOW_ID, "T24/T25 Summary Requeue Flow",
          "1.0.0", List.of(), null, null, List.of(step), RetryPolicy.DEFAULT);
      FlowRegistry flowRegistry = mock(FlowRegistry.class);
      when(flowRegistry.require(S03_FLOW_ID)).thenReturn(flow);
      HitlReviewRepository reviews = mock(HitlReviewRepository.class);
      HitlReview review = new HitlReview(executionId(),
          HitlGateOpener.ESCALATION_GATE_ID, 0, "{}");
      when(reviews.findById(review.getId())).thenReturn(Optional.of(review));
      new HitlDecisionService(reviews, state, artifactStore, audit, flowRegistry,
          List.of(), JSON).decide(review.getId(), HitlDecision.APPROVE,
          "t26-s03-reviewer", "approved: requeue the failed step", null);
    }

    UUID executionId() {
      return state.execution.getId();
    }

    PipelineExecution execution() {
      return state.execution;
    }

    Optional<RecoveryBundleStore.RecoveryBundle> bundle(int attempt) {
      return recovery.find(executionId(), "coding", attempt);
    }
  }

  /** In-memory StateManager: one shared execution plus retry bookkeeping. */
  private static final class FakeStateManager extends StateManager {

    private final PipelineExecution execution;
    private final Map<String, Integer> retries = new LinkedHashMap<>();
    private final Map<String, Integer> attemptOrdinals = new LinkedHashMap<>();

    FakeStateManager(String triggerPayloadJson) {
      super(null, null, null);
      this.execution = new PipelineExecution(S03_FLOW_ID, "1.0.0", triggerPayloadJson);
      this.execution.setStatus(ExecutionStatus.RUNNING);
    }

    @Override
    public PipelineExecution get(UUID executionId) {
      return execution;
    }

    @Override
    public void heartbeat(UUID executionId) {
    }

    @Override
    public int retryCount(UUID executionId, String stepId) {
      return retries.getOrDefault(stepId, 0);
    }

    @Override
    public int incrementRetry(UUID executionId, String stepId) {
      return retries.merge(stepId, 1, Integer::sum);
    }

    @Override
    public void resetRetry(UUID executionId, String stepId) {
      // Approving an escalation resets ONLY the retry budget; the ascending
      // attempt ordinal below is deliberately untouched by the reset.
      retries.remove(stepId);
    }

    @Override
    public int nextAttempt(UUID executionId, String stepId) {
      return attemptOrdinals.merge(stepId, 1, Integer::sum);
    }

    @Override
    public void scheduleRetry(UUID executionId, long backoffSeconds, String errorMessage) {
      execution.setStatus(ExecutionStatus.PENDING);
      execution.setErrorMessage(errorMessage);
    }

    @Override
    public PipelineExecution transition(UUID executionId, ExecutionStatus newStatus,
        Map<String, ?> auditDetail) {
      execution.setStatus(newStatus);
      return execution;
    }

    @Override
    public boolean advanceStep(UUID executionId, int expectedStepIndex,
        ExecutionStatus expectedStatus) {
      if (execution.getStatus() != expectedStatus
          || execution.getCurrentStepIndex() != expectedStepIndex) {
        return false;
      }
      execution.setCurrentStepIndex(expectedStepIndex + 1);
      return true;
    }
  }
}
