package org.folio.factory.sandbox.harness;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T24 B3: the check-output evidence policy. A declared-check exec that
 * exited 0 is green only when the check's (50 KiB-truncated) ExecTool
 * output carries affirmative test evidence: at least one matched line of
 * the surefire prefix family {@code 'Tests run: N, Failures: N'} (Errors
 * and Skipped groups optional) that is fully clean — every present group 0
 * and at least one executed case. Any matched line with a non-zero group or
 * zero executed tests is dirty for every command shape. A maven-shaped
 * check ('mvn'/'mvnw' as a standalone word) fails closed when the
 * {@code OutputLimiter} truncation marker is present — even when complete
 * clean summaries survived, because truncation may have removed a later
 * dirty summary — when no prefix-family line matched at all (absent
 * evidence: null/empty output, "No tests to run.", "Tests are skipped.",
 * bare BUILD SUCCESS), and (B3c) when Maven's explicit incomplete-evidence
 * markers "No tests to run." or "Tests are skipped." appear ANYWHERE in
 * the output — even alongside a surviving clean summary, because that
 * mixed shape means at least one module ran no tests or skipped them
 * (accepted fail-closed trade-off: a legitimate multi-module check where
 * one module truly has no tests also fails closed; task authors who need
 * that shape use a non-maven-shaped declared command or per-module
 * checks). Non-maven commands keep exact exit-code-governed
 * semantics (summary-less clean, truncation alone clean). The policy only
 * ever turns receipts red, never green.
 *
 * <p>The prefix-family match reimplements {@code TestTool}'s surefire line
 * shape harness-locally: TestTool's parse is package-private in the tools
 * package and must not be refactored across packages. The truncation marker
 * is detected by its literal prefix; {@code OutputLimiter} is not
 * modified.</p>
 */
final class CheckEvidence {

  private static final Pattern SUREFIRE_LINE = Pattern.compile(
      "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+)(?:,\\s*Errors:\\s*(\\d+))?"
          + "(?:,\\s*Skipped:\\s*(\\d+))?");

  private static final Pattern MAVEN_WORD = Pattern.compile("\\bmvnw?\\b");

  private static final String TRUNCATION_MARKER_PREFIX = "[output truncated: ";

  private static final String NO_TESTS_MARKER = "No tests to run.";

  private static final String TESTS_SKIPPED_MARKER = "Tests are skipped.";

  private CheckEvidence() {
  }

  /** {@code clean} plus, when dirty, a detail quoting the offending summary
   * line or the observed truncation marker. */
  record Verdict(boolean clean, String detail) {
  }

  static Verdict evaluate(String command, String output) {
    String out = output == null ? "" : output;
    boolean summaryFound = false;
    for (String line : out.split("\n", -1)) {
      Matcher matcher = SUREFIRE_LINE.matcher(line);
      while (matcher.find()) {
        summaryFound = true;
        int tests = Integer.parseInt(matcher.group(1));
        int failures = Integer.parseInt(matcher.group(2));
        int errors = parseIntOrZero(matcher.group(3));
        int skipped = parseIntOrZero(matcher.group(4));
        if (failures > 0 || errors > 0 || skipped > 0 || tests == 0) {
          return new Verdict(false, "check output is not clean: " + line.strip());
        }
      }
    }
    if (isMavenShaped(command)) {
      // T24 B3b: truncation may have removed a later dirty summary, so a
      // surviving clean prefix cannot certify the run.
      if (out.contains(TRUNCATION_MARKER_PREFIX)) {
        return new Verdict(false,
            "check output was truncated; truncated check output is not green evidence: "
                + truncationMarkerLine(out));
      }
      // T24 B3a: absent evidence — no summary line of the prefix family.
      if (!summaryFound) {
        return new Verdict(false,
            "no surefire test summary was observed in the check output");
      }
      // T24 B3c: Maven's explicit incomplete-evidence markers are dirty
      // even when a clean summary survived — the run also contained a
      // module that ran no tests or skipped them.
      String marker = incompleteEvidenceMarkerLine(out);
      if (marker != null) {
        return new Verdict(false, "check output is not clean: " + marker);
      }
    }
    return new Verdict(true, null);
  }

  private static int parseIntOrZero(String group) {
    return group == null ? 0 : Integer.parseInt(group);
  }

  private static boolean isMavenShaped(String command) {
    return command != null && MAVEN_WORD.matcher(command.strip()).find();
  }

  private static String truncationMarkerLine(String output) {
    for (String line : output.split("\n", -1)) {
      if (line.contains(TRUNCATION_MARKER_PREFIX)) {
        return line.strip();
      }
    }
    return TRUNCATION_MARKER_PREFIX + "...)";
  }

  /** T24 B3c: the first line carrying one of Maven's explicit
   * incomplete-evidence markers, or {@code null} when neither appears. */
  private static String incompleteEvidenceMarkerLine(String output) {
    for (String line : output.split("\n", -1)) {
      if (line.contains(NO_TESTS_MARKER) || line.contains(TESTS_SKIPPED_MARKER)) {
        return line.strip();
      }
    }
    return null;
  }
}
