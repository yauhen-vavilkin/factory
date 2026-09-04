package org.folio.factory.sandbox.harness;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DiffStatsTest {

  @Test
  void countsChangedFilesAndDiffBytes() {
    String diffBody = "diff --git a/pom.xml b/pom.xml\n--- a/pom.xml\n+++ b/pom.xml\n@@ -1 +1 @@\n-a\n+b\n";

    DiffStats stats = DiffStats.parse("[status]\n M pom.xml\n?? scratch.txt\n\n[diff]\n" + diffBody);

    assertEquals(2, stats.filesChanged());
    assertEquals(diffBody.getBytes(UTF_8).length, stats.diffSizeBytes());
  }

  @Test
  void cleanTreeIsZeroZero() {
    DiffStats stats = DiffStats.parse("[status]\n\n[diff]\n(no changes)");

    assertEquals(0, stats.filesChanged());
    assertEquals(0L, stats.diffSizeBytes());
  }
}
