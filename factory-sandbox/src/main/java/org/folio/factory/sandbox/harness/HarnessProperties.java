package org.folio.factory.sandbox.harness;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "factory.harness")
public record HarnessProperties(Integer maxSteps, Integer maxFormatErrors,
    Long jobTimeoutMin, String modelId) {

  public HarnessProperties {
    maxSteps = maxSteps == null ? 40 : maxSteps;
    maxFormatErrors = maxFormatErrors == null ? 3 : maxFormatErrors;
    jobTimeoutMin = jobTimeoutMin == null ? 30L : jobTimeoutMin;
    modelId = modelId == null ? "glm-5.3-flash" : modelId;
  }
}
