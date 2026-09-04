package org.folio.factory.sandbox.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StepRecordTest {

  @Test
  void holdsStepObservation() {
    StepRecord record = new StepRecord("2026-09-04T10:00:00Z", 7, "apply_patch", 512, 4096, true, 1500L);

    assertEquals("2026-09-04T10:00:00Z", record.ts());
    assertEquals(7, record.stepNumber());
    assertEquals("apply_patch", record.toolName());
    assertEquals(512, record.argsLength());
    assertEquals(4096, record.outputLength());
    assertTrue(record.ok());
    assertEquals(1500L, record.durationMs());
  }
}
