package org.folio.factory.sandbox.harness;

public record HarnessConfig(int maxSteps, int maxFormatErrors, long jobTimeoutMin, String modelId) {

  public static HarnessConfig defaults() {
    return new HarnessConfig(40, 3, 30L, "glm-5.3-flash");
  }
}
