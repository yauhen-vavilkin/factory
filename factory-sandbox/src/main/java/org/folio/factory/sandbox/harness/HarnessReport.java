package org.folio.factory.sandbox.harness;

public record HarnessReport(int steps, Outcome outcome, StopReason stopReason, int filesChanged,
    long diffSizeBytes, int formatErrors, long tokensIn, long tokensOut,
    TaskOutcome taskOutcome, TaskOutcome.Reason taskOutcomeReason, String finalText,
    VerificationLedger.VerificationSummary verification) {

  public HarnessReport {
    finalText = finalText == null ? "" : finalText;
    verification = verification == null
        ? VerificationLedger.VerificationSummary.NONE : verification;
  }

  /**
   * T18-compatible constructor: a report without verification diagnostics
   * (no declared checks). Kept so existing callers keep compiling while the
   * T24 verification summary travels as an additive field.
   */
  public HarnessReport(int steps, Outcome outcome, StopReason stopReason, int filesChanged,
      long diffSizeBytes, int formatErrors, long tokensIn, long tokensOut,
      TaskOutcome taskOutcome, TaskOutcome.Reason taskOutcomeReason, String finalText) {
    this(steps, outcome, stopReason, filesChanged, diffSizeBytes, formatErrors, tokensIn,
        tokensOut, taskOutcome, taskOutcomeReason, finalText,
        VerificationLedger.VerificationSummary.NONE);
  }

  public enum Outcome {
    COMPLETED, FAILED
  }

  public enum StopReason {
    // T23 R1: EMPTY_MODEL_RESPONSE is an additive diagnostic for a model
    // reply with zero generations / no assistant output (distinct from a
    // provider failure).
    COMPLETED, STEPS_EXCEEDED, FORMAT_ERRORS_EXCEEDED, TIMEOUT, MODEL_ERROR,
    EMPTY_MODEL_RESPONSE
  }
}
