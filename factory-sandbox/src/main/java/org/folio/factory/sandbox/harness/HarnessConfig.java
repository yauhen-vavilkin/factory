package org.folio.factory.sandbox.harness;

/**
 * Tuning bounds for one coding-harness run.
 *
 * @param maxFormatErrors total budget of malformed tool calls for the whole
 *     run: every malformed call in a model-reply batch increments the count,
 *     valid calls never reset or decrement it, and the limit is enforced at
 *     model-reply (batch) boundaries. A model that keeps answering
 *     {@code [malformed, valid]} batches therefore still exhausts the budget
 *     and stops with {@code FORMAT_ERRORS_EXCEEDED} — format errors are
 *     limited by total count, not by consecutive streaks.
 */
public record HarnessConfig(int maxSteps, int maxFormatErrors, long jobTimeoutMin, String modelId) {

  public static HarnessConfig defaults() {
    return new HarnessConfig(40, 3, 30L, "claude-sonnet-4-5");
  }
}
