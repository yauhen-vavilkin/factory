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
 * T21 R1 representative shape — a real regression fix, hermetic: the scripted
 * model (no live provider) reproduces a planted failing check, fixes it and
 * verifies, all through the real harness, tools, sandbox and export. The
 * observable criterion is not narrated: in a fresh consumer checkout of the
 * recorded base the check is red, and after applying the exported patch the
 * same check is green.
 */
@Tag("eval-pack")
class EvalPackHermeticRegressionFixTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final FrontmatterCodec codec = new FrontmatterCodec();

  @TempDir
  Path root;

  @Test
  void regressionFixFlipsPlantedFailingCheckRedToGreenInFreshConsumerCheckout() throws Exception {
    EvalPackScenarios pack = new EvalPackScenarios(root);
    Path fixture = pack.createGreetingFixture("REGRESSION-FIX", "Goodbye, $1.");
    ObjectNode contract = JSON.createObjectNode()
        .put("goal", EvalPackScenarios.REGRESSION_GOAL);
    ArrayNode acceptance = contract.putArray("acceptance");
    EvalPackScenarios.REGRESSION_ACCEPTANCE.lines()
        .filter(line -> !line.isBlank()).forEach(acceptance::add);
    ObjectNode constraints = contract.putObject("constraints");
    constraints.putArray("allow_paths").add("greet.sh").add("check.sh");
    contract.put("notes", EvalPackScenarios.REGRESSION_NOTES);

    EvalPackScenarios.PackRun run = pack.run("REGRESSION-FIX", fixture, contract, List.of(
        ModelReply.toolCalls(List.of(new ToolCall("t1", "exec",
            execArgs("cd repo && sh check.sh"))), new TokenUsage(1200, 48)),
        ModelReply.toolCalls(List.of(new ToolCall("t2", "apply_patch",
            patchArgs(EvalPackScenarios.GREETING_FIX))), new TokenUsage(2100, 96)),
        ModelReply.toolCalls(List.of(new ToolCall("t3", "exec",
            execArgs("cd repo && sh check.sh"))), new TokenUsage(1900, 64)),
        ModelReply.text("Root cause: greet.sh printed Goodbye instead of the specified "
            + "Hello. Fixed the greeting in greet.sh and verified sh check.sh passes.",
            new TokenUsage(300, 175))));

    assertThat(run.result().outputs().get("patch.diff"))
        .contains("diff --git a/greet.sh b/greet.sh")
        .contains("-echo \"Goodbye, $1.\"")
        .contains("+echo \"Hello, $1.\"");

    Frontmatter report = codec.parse(run.persistedReport());
    JsonNode metadata = report.metadata();
    assertThat(metadata.path("task_outcome").asString())
        .as("reproduce -> fix -> verify with a non-empty diff must be accepted")
        .isEqualTo("SUCCEEDED");
    assertThat(metadata.path("task_outcome_reason").asString()).isEqualTo("CHANGES_DELIVERED");
    assertThat(metadata.path("stop_reason").asString()).isEqualTo("COMPLETED");
    assertThat(metadata.path("tokens_in").asLong())
        .as("tokens must be the scripted provider's actual reported usage")
        .isEqualTo(5500L);
    assertThat(metadata.path("tokens_out").asLong()).isEqualTo(383L);
    assertThat(report.body()).contains("verified sh check.sh passes");
    assertThat(run.persistedTrajectory())
        .contains("\"task_outcome\":\"SUCCEEDED\"")
        .contains("\"task_outcome_reason\":\"CHANGES_DELIVERED\"");

    Path baseConsumer = pack.consumerCheckout(run, "REGRESSION-FIX-base", false);
    EvalPackScenarios.ShellResult beforeFix = pack.shell(baseConsumer, "sh", "check.sh");
    assertThat(beforeFix.exitCode())
        .as("the planted check must be red on the recorded base before the change")
        .isNotZero();
    assertThat(beforeFix.stdout()).contains("FAIL: greet.sh printed 'Goodbye, World.'");

    Path fixedConsumer = pack.consumerCheckout(run, "REGRESSION-FIX-fixed", true);
    EvalPackScenarios.ShellResult afterFix = pack.shell(fixedConsumer, "sh", "check.sh");
    assertThat(afterFix.exitCode())
        .as("the same check must be green in the fresh consumer checkout after the patch")
        .isZero();
    assertThat(afterFix.stdout()).contains("OK: greet.sh prints 'Hello, World.'");

    assertThat(Files.readString(fixedConsumer.resolve("greet.sh")))
        .isEqualTo("#!/bin/sh\necho \"Hello, $1.\"\n");
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
