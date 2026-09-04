package org.folio.factory.sandbox.harness;

import java.nio.charset.StandardCharsets;

record DiffStats(int filesChanged, long diffSizeBytes) {

  private static final String STATUS_HEADER = "[status]";
  private static final String DIFF_SECTION = "\n[diff]\n";
  private static final String NO_CHANGES = "(no changes)";

  static DiffStats parse(String gitDiffOutput) {
    String statusSection;
    String diffSection;
    int diffIndex = gitDiffOutput.indexOf(DIFF_SECTION);
    if (diffIndex >= 0) {
      statusSection = gitDiffOutput.substring(0, diffIndex);
      diffSection = gitDiffOutput.substring(diffIndex + DIFF_SECTION.length());
    } else {
      statusSection = gitDiffOutput;
      diffSection = "";
    }
    int files = (int) statusSection.lines()
        .filter(line -> !line.isBlank() && !line.equals(STATUS_HEADER))
        .count();
    long bytes = NO_CHANGES.equals(diffSection.strip())
        ? 0L
        : diffSection.getBytes(StandardCharsets.UTF_8).length;
    return new DiffStats(files, bytes);
  }
}
