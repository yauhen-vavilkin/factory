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
 * T21 R2 counterexamples, hermetic: scripted runs whose components and tool
 * calls all pass and whose model stops cleanly, yet the requested outcome is
 * not achieved. Acceptance must reject each one — the assertion reads the
 * persisted report.md task_outcome (from disk), never an in-memory callback,
 * and each case also proves non-achievement through the observable criterion
 * in a fresh consumer checkout.
 */
@Tag("eval-pack")
class EvalPackHermeticCounterexampleTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final FrontmatterCodec codec = new FrontmatterCodec();

  @TempDir
  Path root;

  @Test
  void earlyBailWithoutReproductionIsRejectedAndCheckStaysRedForConsumer() throws Exception {
    EvalPackScenarios pack = new EvalPackScenarios(root);
    Path fixture = pack.createGreetingFixture("EARLY-NO-REPRO", "Goodbye, $1.");
    JsonNode contract = regressionContract(false);

    EvalPackScenarios.PackRun run = pack.run("EARLY-NO-REPRO", fixture, contract, List.of(
        ModelReply.toolCalls(List.of(new ToolCall("t1", "exec",
            execArgs("cd repo && git log --oneline -3"))), new TokenUsage(900, 40)),
        ModelReply.text("I could not reproduce the failing greeting; the repository "
            + "looks correct to me, so no change is needed.", new TokenUsage(250, 60))));

    Frontmatter report = codec.parse(run.persistedReport());
    JsonNode metadata = report.metadata();
    assertThat(metadata.path("stop_reason").asString())
        .as("the model stopped cleanly — that is precisely why acceptance must judge "
            + "the outcome, not the stop")
        .isEqualTo("COMPLETED");
    assertThat(report.body()).contains("no change is needed");
    assertThat(metadata.path("task_outcome").asString())
        .as("clean stop with no change and no allow_noop must be rejected")
        .isEqualTo("FAILED");
    assertThat(metadata.path("task_outcome_reason").asString()).isEqualTo("NO_OP_NOT_PERMITTED");
    assertThat(run.result().outputs().get("patch.diff")).isEqualTo("(no changes)\n");

    Path consumer = pack.consumerCheckout(run, "EARLY-NO-REPRO", true);
    EvalPackScenarios.ShellResult check = pack.shell(consumer, "sh", "check.sh");
    assertThat(check.exitCode())
        .as("the requested outcome is not achieved: the regression is still present "
            + "in the consumer checkout")
        .isNotZero();
    assertThat(check.stdout()).contains("FAIL");
  }

  @Test
  void greenTestsButNoRequestedChangeIsRejected() throws Exception {
    EvalPackScenarios pack = new EvalPackScenarios(root);
    Path fixture = pack.createGreetingFixture("GREEN-NO-CHANGE", "Hello, $1.");
    ObjectNode contract = JSON.createObjectNode().put("goal",
        "Add farewell.sh printing 'Goodbye, <name>.' and extend check.sh to verify it.");
    ArrayNode acceptance = contract.putArray("acceptance");
    acceptance.add("sh check.sh verifies both greeting and farewell");

    EvalPackScenarios.PackRun run = pack.run("GREEN-NO-CHANGE", fixture, contract, List.of(
        ModelReply.toolCalls(List.of(new ToolCall("t1", "exec",
            execArgs("cd repo && sh check.sh"))), new TokenUsage(1100, 52)),
        ModelReply.text("All tests pass — greeting and farewell both behave as "
            + "specified. No change needed.", new TokenUsage(280, 70))));

    assertThat(run.persistedTrajectory())
        .as("the existing test suite really did pass — green tests are not the requested "
            + "outcome and must not buy acceptance")
        .contains("\"tool\":\"exec\"")
        .contains("\"ok\":true");

    Frontmatter report = codec.parse(run.persistedReport());
    JsonNode metadata = report.metadata();
    assertThat(metadata.path("task_outcome").asString())
        .as("tests passed and the model stopped cleanly, but no requested change was "
            + "delivered: acceptance must reject")
        .isEqualTo("FAILED");
    assertThat(metadata.path("task_outcome_reason").asString())
        .isEqualTo("NO_OP_NOT_PERMITTED");
    assertThat(metadata.path("stop_reason").asString()).isEqualTo("COMPLETED");
    assertThat(run.result().outputs().get("patch.diff")).isEqualTo("(no changes)\n");

    Path consumer = pack.consumerCheckout(run, "GREEN-NO-CHANGE", true);
    assertThat(Files.notExists(consumer.resolve("farewell.sh")))
        .as("the requested outcome is not achieved: farewell.sh does not exist in the "
            + "consumer checkout despite the green suite and the clean stop")
        .isTrue();
  }

  @Test
  void noopPermittedWithoutVerificationEvidenceIsRejected() throws Exception {
    EvalPackScenarios pack = new EvalPackScenarios(root);
    Path fixture = pack.createGreetingFixture("NOOP-NO-EVIDENCE", "Goodbye, $1.");
    JsonNode contract = regressionContract(true);

    EvalPackScenarios.PackRun run = pack.run("NOOP-NO-EVIDENCE", fixture, contract, List.of(
        ModelReply.text("The greeting already behaves as specified; no change is needed.")));

    Frontmatter report = codec.parse(run.persistedReport());
    JsonNode metadata = report.metadata();
    assertThat(metadata.path("task_outcome").asString())
        .as("allow_noop is not a free pass: a claim without a single successful "
            + "verification tool call is rejected")
        .isEqualTo("FAILED");
    assertThat(metadata.path("task_outcome_reason").asString())
        .isEqualTo("NO_VERIFICATION_EVIDENCE");
    assertThat(run.result().outputs().get("patch.diff")).isEqualTo("(no changes)\n");
  }

  private JsonNode regressionContract(boolean allowNoop) {
    ObjectNode contract = JSON.createObjectNode()
        .put("goal", EvalPackScenarios.REGRESSION_GOAL);
    ArrayNode acceptance = contract.putArray("acceptance");
    EvalPackScenarios.REGRESSION_ACCEPTANCE.lines()
        .filter(line -> !line.isBlank()).forEach(acceptance::add);
    if (allowNoop) {
      contract.putObject("constraints").put("allow_noop", true);
    }
    contract.put("notes", EvalPackScenarios.REGRESSION_NOTES);
    return contract;
  }

  private static String execArgs(String cmd) {
    ObjectNode args = JSON.createObjectNode();
    args.put("cmd", cmd);
    return args.toString();
  }
}
