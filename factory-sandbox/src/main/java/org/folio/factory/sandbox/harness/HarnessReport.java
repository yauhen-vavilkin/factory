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
    COMPLETED, STEPS_EXCEEDED, FORMAT_ERRORS_EXCEEDED, TIMEOUT, MODEL_ERROR
  }
}
