package org.folio.factory.sandbox.harness;

public record HarnessReport(int steps, Outcome outcome, StopReason stopReason, int filesChanged,
    long diffSizeBytes, int formatErrors, long tokensIn, long tokensOut,
    TaskOutcome taskOutcome, TaskOutcome.Reason taskOutcomeReason, String finalText) {

  public HarnessReport {
    finalText = finalText == null ? "" : finalText;
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
