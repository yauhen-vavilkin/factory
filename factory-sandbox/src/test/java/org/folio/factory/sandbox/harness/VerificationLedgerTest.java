package org.folio.factory.sandbox.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class VerificationLedgerTest {

  private static final String CHECK_CMD = "cd repo && mvn -pl factory-core test -B";

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void ledgerDerivedFromContractMatchesCommandsAfterStripping() {
    VerificationLedger ledger = VerificationLedger
        .from(contractWithChecks(CHECK_CMD, "rg -n SearchHelper repo/src"));

    assertThat(ledger.isCheckCommand(CHECK_CMD)).isTrue();
    assertThat(ledger.isCheckCommand("  " + CHECK_CMD + "  ")).isTrue();
    assertThat(ledger.isCheckCommand("cd repo && mvn test")).isFalse();
    assertThat(ledger.isCheckCommand(null)).isFalse();
  }

  @Test
  void emptyLedgerForContractWithoutChecks() {
    VerificationLedger ledger = VerificationLedger.from(TaskContract.ofGoal("goal"));

    assertThat(ledger.isCheckCommand(CHECK_CMD)).isFalse();
    assertThat(ledger.evaluate("any").checks()).isEmpty();
  }

  /** T24 R3: a check that never ran leaves the obligation MISSING. */
  @Test
  void neverExecutedCheckIsMissing() {
    VerificationLedger ledger = VerificationLedger.from(contractWithChecks(CHECK_CMD));

    VerificationLedger.VerificationSummary summary = ledger.evaluate("identity");

    assertThat(summary.checks()).hasSize(1);
    VerificationLedger.CheckState state = summary.checks().get(0);
    assertThat(state.status()).isEqualTo(VerificationLedger.Status.MISSING);
    assertThat(state.boundIdentity()).isNull();
    assertThat(summary.allPassed()).isFalse();
  }

  /** T24 R3: a red or executor-failed check is FAILED with its error preserved. */
  @Test
  void failedCheckCarriesErrorDetail() {
    VerificationLedger ledger = VerificationLedger.from(contractWithChecks(CHECK_CMD));
    ledger.record(CHECK_CMD, false, "identity", "command exited with code 1");

    VerificationLedger.CheckState state = ledger.evaluate("identity").checks().get(0);

    assertThat(state.status()).isEqualTo(VerificationLedger.Status.FAILED);
    assertThat(state.detail()).contains("command exited with code 1");
  }

  /** T24 R3: a green check bound to an earlier tree state is STALE. */
  @Test
  void greenCheckBoundToEarlierTreeIsStale() {
    VerificationLedger ledger = VerificationLedger.from(contractWithChecks(CHECK_CMD));
    ledger.record(CHECK_CMD, true, "older-identity", null);

    VerificationLedger.CheckState state = ledger.evaluate("final-identity").checks().get(0);

    assertThat(state.status()).isEqualTo(VerificationLedger.Status.STALE);
    assertThat(state.boundIdentity()).isEqualTo("older-identity");
  }

  /** T24 R3: only a green receipt bound to the final identity PASSes. */
  @Test
  void greenCheckBoundToFinalIdentityPasses() {
    VerificationLedger ledger = VerificationLedger.from(contractWithChecks(CHECK_CMD));
    ledger.record(CHECK_CMD, true, "final-identity", null);

    VerificationLedger.VerificationSummary summary = ledger.evaluate("final-identity");

    assertThat(summary.checks().get(0).status()).isEqualTo(VerificationLedger.Status.PASS);
    assertThat(summary.allPassed()).isTrue();
  }

  /** T24 R3: a missing identity on either side can never count as fresh. */
  @Test
  void missingIdentityOnEitherSideIsNeverFresh() {
    VerificationLedger ledger = VerificationLedger.from(contractWithChecks(CHECK_CMD));
    ledger.record(CHECK_CMD, true, null, null);
    assertThat(ledger.evaluate("identity").checks().get(0).status())
        .isEqualTo(VerificationLedger.Status.STALE);

    VerificationLedger ledgerTwo = VerificationLedger.from(contractWithChecks(CHECK_CMD));
    ledgerTwo.record(CHECK_CMD, true, "identity", null);
    assertThat(ledgerTwo.evaluate(null).checks().get(0).status())
        .isEqualTo(VerificationLedger.Status.STALE);
  }

  /** T24 R3: re-running a check replaces the earlier receipt (latest wins). */
  @Test
  void latestReceiptWins() {
    VerificationLedger ledger = VerificationLedger.from(contractWithChecks(CHECK_CMD));
    ledger.record(CHECK_CMD, true, "older-identity", null);
    ledger.record(CHECK_CMD, true, "final-identity", null);

    assertThat(ledger.evaluate("final-identity").allPassed()).isTrue();
  }

  /** T24 R3: all obligations are evaluated, in declaration order. */
  @Test
  void multipleObligationsEvaluatedInDeclarationOrder() {
    VerificationLedger ledger = VerificationLedger
        .from(contractWithChecks(CHECK_CMD, "rg -n SearchHelper repo/src"));
    ledger.record(CHECK_CMD, true, "identity", null);

    List<VerificationLedger.CheckState> states = ledger.evaluate("identity").checks();

    assertThat(states).extracting(VerificationLedger.CheckState::command)
        .containsExactly(CHECK_CMD, "rg -n SearchHelper repo/src");
    assertThat(states).extracting(VerificationLedger.CheckState::status)
        .containsExactly(VerificationLedger.Status.PASS, VerificationLedger.Status.MISSING);
  }

  /**
   * T24 B1: the ledger binds identity by exact equality of the captured
   * tree ids — two distinct well-formed identities never bind, so any
   * delivered-content change invalidates the receipt.
   */
  @Test
  void identityBindsOnlyOnExactTreeIdEquality() {
    String treeIdOne = "1".repeat(64);
    String treeIdTwo = "2".repeat(64);
    VerificationLedger ledger = VerificationLedger.from(contractWithChecks(CHECK_CMD));
    ledger.record(CHECK_CMD, true, treeIdOne, null);

    assertThat(ledger.evaluate(treeIdOne).checks().get(0).status())
        .isEqualTo(VerificationLedger.Status.PASS);
    assertThat(ledger.evaluate(treeIdTwo).checks().get(0).status())
        .isEqualTo(VerificationLedger.Status.STALE);
    assertThat(ledger.evaluate(treeIdTwo).checks().get(0).boundIdentity())
        .isEqualTo(treeIdOne);
  }

  /**
   * T24 B1 fail-closed: an identity that could not be captured on either
   * side is not evidence — even a green receipt with no captured identity
   * cannot pass against a result whose identity is also unavailable.
   */
  @Test
  void unavailableIdentityOnBothSidesStillNeverPasses() {
    VerificationLedger ledger = VerificationLedger.from(contractWithChecks(CHECK_CMD));
    ledger.record(CHECK_CMD, true, null, null);

    VerificationLedger.VerificationSummary summary = ledger.evaluate(null);

    assertThat(summary.resultIdentity()).isNull();
    assertThat(summary.checks().get(0).status()).isEqualTo(VerificationLedger.Status.STALE);
    assertThat(summary.allPassed()).isFalse();
  }

  /**
   * T24 B2 boundary: a green check exec whose tested state cannot be proven
   * stable (the working-tree identity moved across the exec, or a capture
   * failed on either side) leaves an INDETERMINATE receipt. It binds no
   * identity, carries the explicit detail the harness captured, and can
   * never evaluate to PASS against any result identity.
   */
  @Test
  void indeterminateReceiptNeverEvaluatesToPass() {
    VerificationLedger ledger = VerificationLedger.from(contractWithChecks(CHECK_CMD));
    ledger.recordIndeterminate(CHECK_CMD,
        "working tree changed while the check ran (before id-a, after id-b)");

    VerificationLedger.VerificationSummary summary = ledger.evaluate("id-b");

    assertThat(summary.checks().get(0).status()).isEqualTo(VerificationLedger.Status.STALE);
    assertThat(summary.checks().get(0).boundIdentity()).isNull();
    assertThat(summary.checks().get(0).detail())
        .contains("changed while the check ran")
        .contains("(before id-a, after id-b)");
    assertThat(summary.allPassed()).isFalse();
  }

  /**
   * T24 B2: indeterminate is a property of the latest receipt, not a poison
   * for the obligation — a later cleanly bound green receipt replaces it
   * (latest wins) and can still PASS.
   */
  @Test
  void cleanReceiptAfterIndeterminateReplacesIt() {
    VerificationLedger ledger = VerificationLedger.from(contractWithChecks(CHECK_CMD));
    ledger.recordIndeterminate(CHECK_CMD,
        "working tree changed while the check ran (before id-a, after id-b)");
    ledger.record(CHECK_CMD, true, "id-b", null);

    VerificationLedger.VerificationSummary summary = ledger.evaluate("id-b");

    assertThat(summary.checks().get(0).status()).isEqualTo(VerificationLedger.Status.PASS);
    assertThat(summary.allPassed()).isTrue();
  }

  private TaskContract contractWithChecks(String... commands) {
    ObjectNode constraints = mapper.createObjectNode();
    ArrayNode checks = constraints.putArray("checks");
    for (String command : commands) {
      checks.addObject().put("id", "check-" + checks.size()).put("command", command);
    }
    return new TaskContract("goal", null, constraints, null);
  }
}
