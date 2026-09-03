package org.folio.factory.sandbox.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolResultTest {

  @Test
  void successHasOutputAndNoError() {
    ToolResult result = ToolResult.success("done");

    assertTrue(result.ok());
    assertEquals("done", result.output());
    assertNull(result.error());
  }

  @Test
  void failureHasErrorOnly() {
    ToolResult result = ToolResult.failure("boom");

    assertFalse(result.ok());
    assertEquals("", result.output());
    assertEquals("boom", result.error());
  }

  @Test
  void failureCanCarryOutput() {
    ToolResult result = ToolResult.failure("partial", "exit 1");

    assertFalse(result.ok());
    assertEquals("partial", result.output());
    assertEquals("exit 1", result.error());
  }
}
