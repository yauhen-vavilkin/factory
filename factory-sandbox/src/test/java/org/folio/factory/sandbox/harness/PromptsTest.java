package org.folio.factory.sandbox.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PromptsTest {

  @Test
  void codingWorkerPromptLoadsAndIsSubstantial() {
    String prompt = Prompts.codingWorker();

    assertTrue(prompt.length() > 500);
    assertFalse(prompt.contains("[FOLIO context pack — added in T15"));
    assertFalse(prompt.contains("## FOLIO CONTEXT"));
    assertTrue(prompt.contains("apply_patch"));
    assertTrue(prompt.contains("exactly one tool call"));
  }

  @Test
  void folioContextPackIsBounded() {
    String[] lines = Prompts.folioContext().split("\n", -1);

    assertTrue(lines.length >= 100);
    assertTrue(lines.length <= 200);
  }

  @Test
  void folioContextPackCarriesStableMarkers() {
    String pack = Prompts.folioContext();

    assertTrue(pack.contains("ModuleDescriptor"));
    assertTrue(pack.contains("X-Okapi-Tenant"));
    assertTrue(pack.contains("X-Okapi-Url"));
    assertTrue(pack.contains("_tenant"));
    assertTrue(pack.contains("Eureka"));
    assertTrue(pack.contains("RAML"));
    assertTrue(pack.contains("Read the module's README"));
  }

  @Test
  void systemPromptComposesCodingWorkerThenFolioPack() {
    assertEquals(Prompts.codingWorker() + "\n" + Prompts.folioContext(),
        Prompts.codingWorkerSystemPrompt());
  }

  @Test
  void missingResourceFailsFast() {
    assertThrows(IllegalStateException.class, () -> Prompts.load("prompts/nope.md"));
  }
}
