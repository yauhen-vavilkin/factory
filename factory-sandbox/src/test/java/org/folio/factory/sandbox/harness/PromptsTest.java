package org.folio.factory.sandbox.harness;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PromptsTest {

  @Test
  void codingWorkerPromptLoadsAndIsSubstantial() {
    String prompt = Prompts.codingWorker();

    assertTrue(prompt.length() > 500);
    assertTrue(prompt.contains("FOLIO CONTEXT"));
    assertTrue(prompt.contains("T15"));
    assertTrue(prompt.contains("apply_patch"));
    assertTrue(prompt.contains("exactly one tool call"));
  }

  @Test
  void missingResourceFailsFast() {
    assertThrows(IllegalStateException.class, () -> Prompts.load("prompts/nope.md"));
  }
}
