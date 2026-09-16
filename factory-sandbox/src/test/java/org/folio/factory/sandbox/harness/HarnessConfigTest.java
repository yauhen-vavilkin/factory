package org.folio.factory.sandbox.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HarnessConfigTest {

  @Test
  void defaultsMatchSpecLimits() {
    HarnessConfig config = HarnessConfig.defaults();

    assertEquals(40, config.maxSteps());
    assertEquals(3, config.maxFormatErrors());
    assertEquals(30L, config.jobTimeoutMin());
    assertEquals("claude-sonnet-4-5", config.modelId());
  }

  @Test
  void customValuesAreKept() {
    HarnessConfig config = new HarnessConfig(3, 2, 1L, "test-model");

    assertEquals(3, config.maxSteps());
    assertEquals(2, config.maxFormatErrors());
    assertEquals(1L, config.jobTimeoutMin());
    assertEquals("test-model", config.modelId());
  }
}
