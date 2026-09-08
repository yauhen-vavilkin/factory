package org.folio.factory.sandbox.harness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T24: the verification obligations derived from the frozen task contract,
 * plus the check receipts collected during the run. An obligation is
 * discharged only by an {@code exec} of its exact command (whitespace
 * stripped) whose receipt is green AND bound — via the working-tree identity
 * captured right after the check ran — to the same tree identity the
 * terminal decision sees. Any later edit therefore invalidates the receipt.
 *
 * <p>B1: the identity is the content-addressed working-tree id captured by
 * {@code GitDiffTool#worktreeIdentity}; the ledger treats it as opaque and
 * binds by exact equality. Fail-closed: a capture failure yields no identity,
 * and a missing identity on either side can never count as fresh.</p>
 *
 * <p>B2: the harness wraps every exec of a declared check in a conservative
 * before/after identity boundary. A receipt binds only when both captures
 * succeed and are equal — the provably stable tested state; otherwise the
 * harness records an indeterminate receipt (with the explicit reason as its
 * detail), which binds nothing and can never evaluate to PASS.</p>
 */
public final class VerificationLedger {

  /** Terminal state of one obligation's latest receipt against the final identity. */
  public enum Status {
    PASS, MISSING, FAILED, STALE
  }

  /** Per-obligation verdict carried on the report (R4 diagnostics). */
  public record CheckState(String id, String command, Status status, String boundIdentity,
      String detail) {
  }

  /** All obligations' verdicts bound against the final result identity. */
  public record VerificationSummary(String resultIdentity, List<CheckState> checks) {

    public static final VerificationSummary NONE = new VerificationSummary(null, List.of());

    public VerificationSummary {
      checks = checks == null ? List.of() : List.copyOf(checks);
    }

    public boolean allPassed() {
      return !checks.isEmpty()
          && checks.stream().allMatch(state -> state.status() == Status.PASS);
    }
  }

  private record CheckReceipt(boolean ok, String identity, String error,
      boolean indeterminate) {
  }

  private final Map<String, TaskContract.RequiredCheck> obligations = new LinkedHashMap<>();
  private final Map<String, CheckReceipt> receipts = new HashMap<>();

  private VerificationLedger() {
  }

  /** Derives the ledger's obligations from the contract's {@code checks} constraint. */
  public static VerificationLedger from(TaskContract contract) {
    VerificationLedger ledger = new VerificationLedger();
    for (TaskContract.RequiredCheck check : contract.requiredChecks()) {
      ledger.obligations.put(normalize(check.command()), check);
    }
    return ledger;
  }

  /** {@code true} when {@code command} (after stripping) is a declared check command. */
  public boolean isCheckCommand(String command) {
    return command != null && obligations.containsKey(normalize(command));
  }

  /**
   * Records the latest receipt for the given check command: whether the check
   * execution succeeded, the tree identity it was bound to (the provably
   * stable tested state captured around the check exec), and its error text
   * when it failed.
   */
  public void record(String command, boolean ok, String identity, String error) {
    receipts.put(normalize(command), new CheckReceipt(ok, identity, error, false));
  }

  /**
   * T24 B2: records an INDETERMINATE receipt — the check exec was green, but
   * its tested state cannot be proven stable (the working-tree identity moved
   * across the exec, or a capture failed on either side of it). The receipt
   * binds no identity and can never evaluate to PASS; {@code detail} carries
   * the explicit reason the harness observed.
   */
  public void recordIndeterminate(String command, String detail) {
    receipts.put(normalize(command), new CheckReceipt(true, null, detail, true));
  }

  /** Evaluates every obligation against the final result identity. */
  public VerificationSummary evaluate(String resultIdentity) {
    List<CheckState> states = new ArrayList<>();
    for (TaskContract.RequiredCheck check : obligations.values()) {
      states.add(stateFor(check, resultIdentity));
    }
    return new VerificationSummary(resultIdentity, states);
  }

  private CheckState stateFor(TaskContract.RequiredCheck check, String resultIdentity) {
    CheckReceipt receipt = receipts.get(normalize(check.command()));
    if (receipt == null) {
      return new CheckState(check.id(), check.command(), Status.MISSING, null,
          "required check was never executed");
    }
    if (receipt.indeterminate()) {
      // T24 B2: the tested state cannot be proven stable, so the receipt is
      // not fresh evidence against ANY result identity — never PASS.
      return new CheckState(check.id(), check.command(), Status.STALE, null,
          receipt.error());
    }
    if (!receipt.ok()) {
      return new CheckState(check.id(), check.command(), Status.FAILED, receipt.identity(),
          "required check failed: " + (receipt.error() == null ? "unknown error"
              : receipt.error()));
    }
    if (receipt.identity() == null || !receipt.identity().equals(resultIdentity)) {
      return new CheckState(check.id(), check.command(), Status.STALE, receipt.identity(),
          "required check passed only against an earlier working-tree state");
    }
    return new CheckState(check.id(), check.command(), Status.PASS, receipt.identity(),
        "fresh: bound to the final result identity");
  }

  private static String normalize(String command) {
    return command.strip();
  }
}
