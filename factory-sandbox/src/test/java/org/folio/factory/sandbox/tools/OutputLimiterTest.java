package org.folio.factory.sandbox.tools;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OutputLimiterTest {

  @Test
  void nullBecomesEmpty() {
    assertEquals("", OutputLimiter.truncate(null));
  }

  @Test
  void shortOutputUnchanged() {
    assertEquals("hello", OutputLimiter.truncate("hello"));
  }

  @Test
  void exactlyAtLimitUnchanged() {
    String atLimit = "x".repeat(OutputLimiter.MAX_OUTPUT_BYTES);

    assertEquals(atLimit, OutputLimiter.truncate(atLimit));
  }

  @Test
  void overLimitTruncatedWithMarker() {
    String output = "x".repeat(OutputLimiter.MAX_OUTPUT_BYTES + 1024);

    String truncated = OutputLimiter.truncate(output);

    assertTrue(truncated.getBytes(UTF_8).length <= OutputLimiter.MAX_OUTPUT_BYTES);
    assertTrue(truncated.startsWith("x".repeat(100)));
    assertTrue(truncated.contains("[output truncated: 1024 bytes omitted]"));
  }

  @Test
  void multibyteBoundaryNotSplit() {
    String output = "аб".repeat(26_000);

    String truncated = OutputLimiter.truncate(output);

    assertTrue(truncated.getBytes(UTF_8).length <= OutputLimiter.MAX_OUTPUT_BYTES);
    assertFalse(truncated.contains("\uFFFD"));
    assertTrue(truncated.contains("bytes omitted]"));
  }
}
