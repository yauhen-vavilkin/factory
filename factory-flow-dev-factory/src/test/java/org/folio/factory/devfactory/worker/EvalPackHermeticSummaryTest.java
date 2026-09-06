package org.folio.factory.devfactory.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.sandbox.harness.ModelReply;
import org.folio.factory.sandbox.harness.TokenUsage;
import org.folio.factory.sandbox.harness.ToolCall;
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
}
