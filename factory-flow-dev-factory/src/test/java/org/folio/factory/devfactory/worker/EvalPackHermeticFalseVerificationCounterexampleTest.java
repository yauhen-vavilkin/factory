package org.folio.factory.devfactory.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.devfactory.worker.recovery.RecoveryBundleStore;
import org.folio.factory.sandbox.harness.ModelReply;
import org.folio.factory.sandbox.harness.TokenUsage;
import org.folio.factory.sandbox.harness.ToolCall;
import org.folio.factory.sandbox.tools.OutputLimiter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T26 S01 false-verification counterexamples, hermetic: the T24 escaped
 * shapes ported into the real pack machinery as data. Every member is a
 * scripted run through the real CodingWorker/CodingHarness/LocalSandboxService
 * over a real git fixture; the escaped shape is injected purely as DATA (a
 * fixture-committed fake {@code mvnw} wrapper whose output body is fixture
 * data, or scripted post-check model edits), never by touching production
 * code. Each method asserts the persisted FAILED outcome/reason on BOTH
 * terminal paths — the changed path never surfaces CHANGES_DELIVERED and the
 * allow_noop path never NO_OP_VERIFIED — and proves the user-visible
 * consequence in a fresh consumer checkout. The pack summary rows for the new
 * members are rendered by one shared method from the persisted report.md and
 * recovery-bundle-manifest data under the unchanged honesty rules.
 */
@Tag("eval-pack")
class EvalPackHermeticFalseVerificationCounterexampleTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The declared maven-shaped check: 'mvnw' is a standalone word. */
  private static final String MVNW_CHECK = "cd repo && sh mvnw -B test";

  /** The declared real (non-maven) check used by the drift shapes. */
  private static final String CHECK_SH = "cd repo && sh check.sh";

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

  /** Untracked requested change created (and later removed) by model exec turns. */
  private static final String CREATE_FAREWELL =
      "cd repo && printf '#!/bin/sh\\necho \"Goodbye, $1.\"\\n' > farewell.sh";

  private static final String REMOVE_FAREWELL = "cd repo && rm farewell.sh";

  private static final String CREATE_SCRATCH =
      "cd repo && printf 'unverified scratch\\n' > scratch.txt";

  private static final Path FALSE_VERIFICATION_SUMMARY =
      Path.of("target", "eval-pack-false-verification-summary.md");

  private static final String SUMMARY_HEADER = """
      # Dev Factory eval pack — T24 false-verification counterexamples (hermetic)

      provider: scripted ChatModel (hermetic); live-provider runs in this pack: none
      outcome source: persisted report.md task_outcome (never test counts)
      tokens: numbers are the provider's reported usage; `unknown` means the provider reported no usage
      durations: measured wall time of the full coding-worker execution
      bundle: completeness as persisted in the run's recovery-bundle manifest.json

      | scenario | acceptance | stop_reason | tokens_in | tokens_out | wall_ms | bundle |
      |---|---|---|---|---|---|---|
      """;

  private final FrontmatterCodec codec = new FrontmatterCodec();

  @TempDir
  Path root;

  @Test
  void absentOrSkippedTestEvidenceIsRejectedOnBothTerminalPathsAndCriterionStaysRed()
      throws Exception {
    EvalPackScenarios pack = new EvalPackScenarios(root);

    // Changed path: a cosmetic README comment (the planted regression stays),
    // then the declared maven-shaped check whose fixture-committed output
    // body is absent evidence — exit 0, 'No tests to run.'.
    Path changedFixture = pack.createMavenCheckFixture("T24C1-CHANGED",
        "[INFO] No tests to run.\n[INFO] BUILD SUCCESS");
    EvalPackScenarios.PackRun changed = pack.run("T24C1-CHANGED", changedFixture,
        regressionContractWithCheck(false, MVNW_CHECK), List.of(
            ModelReply.toolCalls(List.of(new ToolCall("t1", "apply_patch",
                EvalPackScenarios.patchArgs(README_COSMETIC_COMMENT))), new TokenUsage(1500, 70)),
            ModelReply.toolCalls(List.of(new ToolCall("t2", "exec",
                EvalPackScenarios.execArgs(MVNW_CHECK))), new TokenUsage(2200, 90)),
            ModelReply.text("mvnw -B test reported BUILD SUCCESS — the required check is green.",
                new TokenUsage(300, 120))));

    JsonNode changedMeta = assertPersistedFailedOutcome(changed, "REQUIRED_CHECK_FAILED",
        "CHANGES_DELIVERED", "no surefire test summary");
    assertThat(changedMeta.path("files_changed").asInt())
        .as("the changed path really was taken: the cosmetic README change counts")
        .isEqualTo(1);

    // allow_noop path: no change at all; the output body is the other
    // absent-evidence marker — 'Tests are skipped.'.
    Path noopFixture = pack.createMavenCheckFixture("T24C1-NOOP",
        "[INFO] Tests are skipped.\n[INFO] BUILD SUCCESS");
    EvalPackScenarios.PackRun noop = pack.run("T24C1-NOOP", noopFixture,
        regressionContractWithCheck(true, MVNW_CHECK), List.of(
            ModelReply.toolCalls(List.of(new ToolCall("t1", "exec",
                EvalPackScenarios.execArgs(MVNW_CHECK))), new TokenUsage(900, 40)),
            ModelReply.text("The build skips the suite but reports success; no change needed.")));

    JsonNode noopMeta = assertPersistedFailedOutcome(noop, "REQUIRED_CHECK_FAILED",
        "NO_OP_VERIFIED", "no surefire test summary");
    assertThat(noopMeta.path("files_changed").asInt())
        .as("the allow_noop path really was taken: no change at all")
        .isZero();
    assertThat(noop.result().outputs().get("patch.diff")).isEqualTo("(no changes)\n");

    // Consumer consequence: the criterion is still red in a fresh checkout of
    // the recorded base despite the claimed exit-0 verification.
    assertCriterionStillRed(pack, pack.consumerCheckout(changed, "T24C1-CHANGED-base", false),
        "T24C1-CHANGED");
    assertCriterionStillRed(pack, pack.consumerCheckout(noop, "T24C1-NOOP-base", false),
        "T24C1-NOOP");

    String summary = renderFalseVerificationSummaryRows(changed, noop);
    assertThat(summary)
        .contains(persistedRow(changed))
        .contains(persistedRow(noop));
    assertHonestSummaryRows(summary);
  }

  @Test
  void truncatedOrMarkerMixedCheckOutputIsRejectedOnBothTerminalPathsAndCriterionStaysRed()
      throws Exception {
    EvalPackScenarios pack = new EvalPackScenarios(root);

    String truncatedBody = "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\n"
        + "[INFO] progress\n".repeat(3400)
        + "Tests run: 1, Failures: 0, Errors: 0, Skipped: 1\n"
        + "[INFO] BUILD SUCCESS";
    assertThat(truncatedBody.getBytes(StandardCharsets.UTF_8).length)
        .as("the fixture body must overflow the real OutputLimiter bound so the "
            + "truncation marker is produced by the real ExecTool, never forged")
        .isGreaterThan(OutputLimiter.MAX_OUTPUT_BYTES);

    // Changed path: the green head survives the real truncation while the
    // skipped tail is cut — the surviving clean prefix must not certify.
    Path truncatedFixture = pack.createMavenCheckFixture("T24C2-TRUNCATED", truncatedBody);
    EvalPackScenarios.PackRun truncated = pack.run("T24C2-TRUNCATED", truncatedFixture,
        regressionContractWithCheck(false, MVNW_CHECK), List.of(
            ModelReply.toolCalls(List.of(new ToolCall("t1", "apply_patch",
                EvalPackScenarios.patchArgs(README_COSMETIC_COMMENT))), new TokenUsage(1600, 75)),
            ModelReply.toolCalls(List.of(new ToolCall("t2", "exec",
                EvalPackScenarios.execArgs(MVNW_CHECK))), new TokenUsage(2400, 95)),
            ModelReply.text("The build log shows a clean summary and BUILD SUCCESS.",
                new TokenUsage(320, 130))));

    JsonNode truncatedMeta = assertPersistedFailedOutcome(truncated, "REQUIRED_CHECK_FAILED",
        "CHANGES_DELIVERED", "[output truncated: ");
    assertThat(truncatedMeta.path("files_changed").asInt()).isEqualTo(1);

    // allow_noop path: a clean four-group summary mixed with maven's explicit
    // incomplete-evidence markers — both marker lines present in one body.
    Path mixedFixture = pack.createMavenCheckFixture("T24C2-MARKER-MIXED",
        "Tests run: 4, Failures: 0, Errors: 0, Skipped: 0\n"
            + "[INFO] Tests are skipped.\n[INFO] No tests to run.\n[INFO] BUILD SUCCESS");
    EvalPackScenarios.PackRun mixed = pack.run("T24C2-MARKER-MIXED", mixedFixture,
        regressionContractWithCheck(true, MVNW_CHECK), List.of(
            ModelReply.toolCalls(List.of(new ToolCall("t1", "exec",
                EvalPackScenarios.execArgs(MVNW_CHECK))), null),
            ModelReply.text("A clean summary is present, so the suite is green; no change needed.")));

    assertPersistedFailedOutcome(mixed, "REQUIRED_CHECK_FAILED", "NO_OP_VERIFIED",
        "Tests are skipped.");
    assertThat(mixed.result().outputs().get("patch.diff")).isEqualTo("(no changes)\n");

    assertCriterionStillRed(pack, pack.consumerCheckout(truncated, "T24C2-TRUNCATED-base", false),
        "T24C2-TRUNCATED");
    assertCriterionStillRed(pack, pack.consumerCheckout(mixed, "T24C2-MARKER-MIXED-base", false),
        "T24C2-MARKER-MIXED");

    String summary = renderFalseVerificationSummaryRows(truncated, mixed);
    assertThat(summary)
        .contains(persistedRow(truncated))
        .contains(persistedRow(mixed));
    assertHonestSummaryRows(summary);
  }

  @Test
  void postCheckContentDriftIncludingUntrackedShapesIsStaleNeverDelivered() throws Exception {
    EvalPackScenarios pack = new EvalPackScenarios(root);

    // (a) tracked re-edit: the fix is verified green, then greet.sh is
    // re-edited after the check — filesChanged stays 1, the receipt is stale.
    Path trackedFixture = pack.createGreetingFixture("T24C3-TRACKED-REEDIT", "Goodbye, $1.");
    EvalPackScenarios.PackRun tracked = pack.run("T24C3-TRACKED-REEDIT", trackedFixture,
        regressionContractWithCheck(false, CHECK_SH), List.of(
            ModelReply.toolCalls(List.of(new ToolCall("t1", "apply_patch",
                EvalPackScenarios.patchArgs(EvalPackScenarios.GREETING_FIX))),
                new TokenUsage(2100, 96)),
            ModelReply.toolCalls(List.of(new ToolCall("t2", "exec",
                EvalPackScenarios.execArgs(CHECK_SH))), new TokenUsage(1900, 64)),
            ModelReply.toolCalls(List.of(new ToolCall("t3", "apply_patch",
                EvalPackScenarios.patchArgs(GREETING_HOWDY_REEDIT))), new TokenUsage(1700, 88)),
            ModelReply.text("Fixed and verified; the final wording tweak is cosmetic.",
                new TokenUsage(280, 150))));

    JsonNode trackedMeta = assertPersistedFailedOutcome(tracked, "REQUIRED_CHECK_STALE",
        "CHANGES_DELIVERED", "required check passed only against an earlier working-tree state");
    assertThat(trackedMeta.path("files_changed").asInt())
        .as("the re-edit targets the same tracked file, so filesChanged stays 1")
        .isEqualTo(1);
    Path trackedConsumer = pack.consumerCheckout(tracked, "T24C3-TRACKED-REEDIT-fixed", true);
    EvalPackScenarios.ShellResult trackedCheck = pack.shell(trackedConsumer, "sh", "check.sh");
    assertThat(trackedCheck.exitCode())
        .as("the delivered re-edit leaves the criterion red for the consumer")
        .isNotZero();
    assertThat(trackedCheck.stdout())
        .contains("FAIL: greet.sh printed 'Howdy, World.'");

    // (b) untracked lossy-display: the green receipt was bound to a tree that
    // included the untracked farewell.sh, which a later exec removes — the
    // display diff never moved, the content identity did.
    Path untrackedFixture = pack.createGreetingFixture("T24C3-UNTRACKED-LOSS", "Goodbye, $1.");
    EvalPackScenarios.PackRun untracked = pack.run("T24C3-UNTRACKED-LOSS", untrackedFixture,
        regressionContractWithCheck(false, CHECK_SH), List.of(
            ModelReply.toolCalls(List.of(new ToolCall("t1", "apply_patch",
                EvalPackScenarios.patchArgs(EvalPackScenarios.GREETING_FIX))), null),
            ModelReply.toolCalls(List.of(new ToolCall("t2", "exec",
                EvalPackScenarios.execArgs(CREATE_FAREWELL))), null),
            ModelReply.toolCalls(List.of(new ToolCall("t3", "exec",
                EvalPackScenarios.execArgs(CHECK_SH))), null),
            ModelReply.toolCalls(List.of(new ToolCall("t4", "exec",
                EvalPackScenarios.execArgs(REMOVE_FAREWELL))), null),
            ModelReply.text("Fixed the greeting, added farewell.sh and verified check.sh.")));

    assertPersistedFailedOutcome(untracked, "REQUIRED_CHECK_STALE", "CHANGES_DELIVERED",
        "required check passed only against an earlier working-tree state");
    assertThat(untracked.result().outputs().get("patch.diff"))
        .as("farewell.sh was removed before the export, so the delivered patch "
            + "cannot contain the requested change")
        .doesNotContain("farewell.sh");
    Path untrackedConsumer = pack.consumerCheckout(untracked, "T24C3-UNTRACKED-LOSS-fixed", true);
    assertThat(Files.notExists(untrackedConsumer.resolve("farewell.sh")))
        .as("the requested farewell.sh change is absent in the consumer checkout "
            + "despite the green check that was bound to it")
        .isTrue();

    // (c) allow_noop drift: green base, a verified no-op receipt, then an
    // untracked scratch file remains — never NO_OP_VERIFIED, and the export
    // still delivers the unverified bytes to the consumer.
    Path noopFixture = pack.createGreetingFixture("T24C3-NOOP-DRIFT", "Hello, $1.");
    EvalPackScenarios.PackRun noop = pack.run("T24C3-NOOP-DRIFT", noopFixture,
        regressionContractWithCheck(true, CHECK_SH), List.of(
            ModelReply.toolCalls(List.of(new ToolCall("t1", "exec",
                EvalPackScenarios.execArgs(CHECK_SH))), new TokenUsage(1200, 50)),
            ModelReply.toolCalls(List.of(new ToolCall("t2", "exec",
                EvalPackScenarios.execArgs(CREATE_SCRATCH))), null),
            ModelReply.text("Verified with check.sh; the scratch file is only local noise.")));

    JsonNode noopMeta = assertPersistedFailedOutcome(noop, "REQUIRED_CHECK_STALE",
        "NO_OP_VERIFIED", "required check passed only against an earlier working-tree state");
    assertThat(noopMeta.path("files_changed").asInt())
        .as("untracked content shows in git status --porcelain, so the run is not a "
            + "display-diff no-op: the stale receipt is what blocks the success outcome")
        .isEqualTo(1);
    assertThat(noop.result().outputs().get("patch.diff"))
        .as("the export stages the whole worktree, so the unverified untracked bytes "
            + "travel in the delivered patch")
        .contains("scratch.txt");
    Path noopConsumer = pack.consumerCheckout(noop, "T24C3-NOOP-DRIFT-fixed", true);
    assertThat(Files.exists(noopConsumer.resolve("scratch.txt")))
        .as("unverified scratch.txt is delivered to the consumer by the exported patch")
        .isTrue();

    String summary = renderFalseVerificationSummaryRows(tracked, untracked, noop);
    assertThat(summary)
        .contains(persistedRow(tracked))
        .contains(persistedRow(untracked))
        .contains(persistedRow(noop));
    assertHonestSummaryRows(summary);
  }

  // ------------------------------------------------------------------
  // Shared assertions and the summary method.
  // ------------------------------------------------------------------

  /**
   * Asserts the persisted rejection of one false-verification run: FAILED
   * task_outcome (never SUCCEEDED), the expected reason (never either
   * success reason), a clean COMPLETED stop, and the receipt detail carried
   * in the persisted trajectory.
   */
  private JsonNode assertPersistedFailedOutcome(EvalPackScenarios.PackRun run,
      String expectedReason, String neverSuccessReason, String detailFragment) {
    Frontmatter report = codec.parse(run.persistedReport());
    JsonNode metadata = report.metadata();
    assertThat(metadata.path("task_outcome").asString())
        .as("%s: the false verification must not be accepted", run.id())
        .isEqualTo("FAILED")
        .isNotEqualTo("SUCCEEDED");
    assertThat(metadata.path("task_outcome_reason").asString())
        .as("%s: persisted rejection reason (never %s)", run.id(), neverSuccessReason)
        .isEqualTo(expectedReason)
        .isNotEqualTo(neverSuccessReason);
    assertThat(metadata.path("stop_reason").asString())
        .as("%s: the model stopped cleanly — that is exactly why the "
            + "verification boundary, not the stop, must decide", run.id())
        .isEqualTo("COMPLETED");
    assertThat(run.persistedTrajectory())
        .as("%s: the persisted trajectory carries the FAILED outcome and the "
            + "receipt's rejection detail", run.id())
        .contains("\"task_outcome\":\"FAILED\"")
        .contains("\"task_outcome_reason\":\"" + expectedReason + "\"")
        .contains(detailFragment);
    return metadata;
  }

  private void assertCriterionStillRed(EvalPackScenarios pack, Path consumer, String id)
      throws Exception {
    EvalPackScenarios.ShellResult check = pack.shell(consumer, "sh", "check.sh");
    assertThat(check.exitCode())
        .as("%s: the criterion is still red for the consumer despite the "
            + "claimed verification", id)
        .isNotZero();
    assertThat(check.stdout()).contains("FAIL: greet.sh printed");
  }

  private JsonNode regressionContractWithCheck(boolean allowNoop, String checkCommand) {
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

  /**
   * The single summary method for the new members: renders their rows from
   * the runs' PERSISTED report.md metadata plus the published recovery-bundle
   * manifest — never from in-memory assertions — under the unchanged honesty
   * rules: outcome from persisted task_outcome, tokens only when the scripted
   * provider reported usage, durations measured. Idempotent per scenario id,
   * so the three members' test methods merge into one file across re-runs.
   */
  private String renderFalseVerificationSummaryRows(EvalPackScenarios.PackRun... runs)
      throws IOException {
    StringBuilder freshRows = new StringBuilder();
    for (EvalPackScenarios.PackRun run : runs) {
      JsonNode metadata = codec.parse(run.persistedReport()).metadata();
      long tokensIn = metadata.path("tokens_in").asLong();
      long tokensOut = metadata.path("tokens_out").asLong();
      freshRows.append("| ").append(run.id())
          .append(" | ").append(metadata.path("task_outcome").asString())
          .append(" (").append(metadata.path("task_outcome_reason").asString()).append(")")
          .append(" | ").append(metadata.path("stop_reason").asString())
          .append(" | ").append(tokenCell(tokensIn, tokensOut, tokensIn))
          .append(" | ").append(tokenCell(tokensIn, tokensOut, tokensOut))
          .append(" | ").append(run.durationMs())
          .append(" | ").append(bundleCell(run))
          .append(" |\n");
    }
    List<String> surviving = new ArrayList<>();
    if (Files.isRegularFile(FALSE_VERIFICATION_SUMMARY)) {
      Set<String> currentIds = new HashSet<>();
      for (EvalPackScenarios.PackRun run : runs) {
        currentIds.add("| " + run.id() + " |");
      }
      for (String line : Files.readAllLines(FALSE_VERIFICATION_SUMMARY)) {
        if (line.startsWith("| ") && !line.startsWith("| scenario") && !line.startsWith("|---")
            && currentIds.stream().noneMatch(line::startsWith)) {
          surviving.add(line);
        }
      }
    }
    StringBuilder summary = new StringBuilder(SUMMARY_HEADER);
    for (String line : surviving) {
      summary.append(line).append('\n');
    }
    summary.append(freshRows);
    Files.createDirectories(FALSE_VERIFICATION_SUMMARY.getParent());
    Files.writeString(FALSE_VERIFICATION_SUMMARY, summary.toString());
    return summary.toString();
  }

  /** A run whose scripted provider reported no usage is rendered {@code unknown}. */
  private static String tokenCell(long tokensIn, long tokensOut, long cell) {
    return tokensIn == 0 && tokensOut == 0 ? "unknown" : Long.toString(cell);
  }

  /** Bundle completeness as persisted in the run's recovery-bundle manifest. */
  private String bundleCell(EvalPackScenarios.PackRun run) throws IOException {
    Object locator = run.result().metrics().get("recovery_locator");
    if (locator == null || locator.toString().isBlank()) {
      return "unknown";
    }
    Path manifestFile = Path.of(locator.toString()).resolve(RecoveryBundleStore.MANIFEST_FILE);
    if (!Files.isRegularFile(manifestFile)) {
      return "unknown";
    }
    JsonNode manifest = JSON.readTree(Files.readString(manifestFile));
    return manifest.path("completeness").asString("unknown") + " ("
        + manifest.path("artifacts").size() + " artifacts)";
  }

  private String persistedRow(EvalPackScenarios.PackRun run) {
    JsonNode metadata = codec.parse(run.persistedReport()).metadata();
    return "| " + run.id() + " | " + metadata.path("task_outcome").asString()
        + " (" + metadata.path("task_outcome_reason").asString() + ") | "
        + metadata.path("stop_reason").asString() + " | ";
  }

  /** Pins the unchanged honesty rules on every rendered data row. */
  private static void assertHonestSummaryRows(String summary) {
    List<String> rows = summary.lines()
        .filter(line -> line.startsWith("| ") && !line.startsWith("| scenario")
            && !line.startsWith("|---"))
        .toList();
    assertThat(rows).as("the false-verification summary must have data rows").isNotEmpty();
    for (String row : rows) {
      assertThat(row).as("every row must carry persisted outcome, tokens-or-unknown "
          + "and a measured duration: %s", row)
          .matches("\\| [A-Z0-9-]+ \\| (SUCCEEDED|FAILED) \\([A-Z_]+\\) \\| [A-Z_]+ \\| "
              + "(unknown|\\d+) \\| (unknown|\\d+) \\| \\d+ \\| "
              + "(COMPLETE \\(\\d+ artifacts\\)|unknown) \\|");
    }
    assertThat(summary)
        .as("a run whose scripted provider reported no usage must never show a "
            + "fabricated token number")
        .doesNotContain("| unknown | 0 |")
        .doesNotContain("| 0 | unknown |");
  }
}
