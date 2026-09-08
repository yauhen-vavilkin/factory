package org.folio.factory.sandbox.harness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * T24 B3 unit suite for the harness-layer check-output evidence policy
 * ({@link CheckEvidence}). Observed RED against the absent evaluator before
 * implementation, and the B3a/B3b inversions were observed RED against the
 * permissive evaluator before the fail-closed rule. Anchors: the surefire
 * prefix family 'Tests run: N, Failures: N' (Errors/Skipped optional) is
 * affirmative evidence — two/three-group clean summaries are accepted as
 * green; a maven-shaped check fails closed when the OutputLimiter truncation
 * marker is present (even with a surviving clean summary) and when no
 * summary line of the family matched at all (absent evidence); non-maven
 * commands keep exact exit-code-governed semantics.
 */
class CheckEvidenceTest {

  private static final String MAVEN_CHECK = "cd repo && mvn -pl factory-core test -B";

  /** The frozen scenario suite's green summary shape. */
  private static final String GREEN_SUMMARY =
      "[summary]\nTests run: 4, Failures: 0, Errors: 0, Skipped: 0";

  private static final String TRUNCATED_WITHOUT_SUMMARY = "[maven]\n[INFO] Building core\n"
      + "[INFO]BUILD SUCCESS\n\n[output truncated: 1234 bytes omitted]";

  @Test
  void cleanFourGroupSummaryIsClean() {
    CheckEvidence.Verdict verdict = CheckEvidence.evaluate(MAVEN_CHECK, GREEN_SUMMARY);
    assertTrue(verdict.clean());
    assertNull(verdict.detail());
  }

  @Test
  void skippedCaseIsDirty() {
    CheckEvidence.Verdict verdict = CheckEvidence.evaluate(MAVEN_CHECK,
        "[summary]\nTests run: 4, Failures: 0, Errors: 0, Skipped: 1");
    assertFalse(verdict.clean());
    assertTrue(verdict.detail().contains("Skipped: 1"));
  }

  @Test
  void failureCaseIsDirty() {
    CheckEvidence.Verdict verdict = CheckEvidence.evaluate(MAVEN_CHECK,
        "[summary]\nTests run: 4, Failures: 1, Errors: 0, Skipped: 0");
    assertFalse(verdict.clean());
    assertTrue(verdict.detail().contains("Failures: 1"));
  }

  @Test
  void errorCaseIsDirty() {
    CheckEvidence.Verdict verdict = CheckEvidence.evaluate(MAVEN_CHECK,
        "[summary]\nTests run: 4, Failures: 0, Errors: 1, Skipped: 0");
    assertFalse(verdict.clean());
    assertTrue(verdict.detail().contains("Errors: 1"));
  }

  @Test
  void zeroExecutedTestsIsDirty() {
    CheckEvidence.Verdict verdict = CheckEvidence.evaluate(MAVEN_CHECK,
        "[summary]\nTests run: 0, Failures: 0, Errors: 0, Skipped: 0");
    assertFalse(verdict.clean());
    assertTrue(verdict.detail().contains("Tests run: 0"));
  }

  @Test
  void perClassSkippedLineCarriesClassNameInDetail() {
    CheckEvidence.Verdict verdict = CheckEvidence.evaluate(MAVEN_CHECK,
        "[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 1"
            + " -- in org.folio.SearchIT");
    assertFalse(verdict.clean());
    assertTrue(verdict.detail().contains("Skipped: 1"));
    assertTrue(verdict.detail().contains("org.folio.SearchIT"));
  }

  @Test
  void mixedCleanAndDirtySummariesAreDirty() {
    CheckEvidence.Verdict verdict = CheckEvidence.evaluate(MAVEN_CHECK,
        "[summary]\nTests run: 4, Failures: 0, Errors: 0, Skipped: 0\n"
            + "Tests run: 2, Failures: 0, Errors: 0, Skipped: 1");
    assertFalse(verdict.clean());
    assertTrue(verdict.detail().contains("Skipped: 1"));
  }

  /**
   * The prefix family accepts the two-group clean shape as affirmative
   * green evidence (frozen harness/flow fixtures use exactly this shape).
   */
  @Test
  void twoGroupSummaryIsAcceptedCleanEvidence() {
    assertTrue(CheckEvidence.evaluate("cd repo && mvn test -B",
        "Tests run: 9, Failures: 0").clean());
  }

  /**
   * The prefix family accepts the three-group clean shape as affirmative
   * green evidence (the frozen scenario suite's green line).
   */
  @Test
  void threeGroupSummaryIsAcceptedCleanEvidence() {
    assertTrue(CheckEvidence.evaluate("cd repo && mvn -pl factory-core -am test -B",
        "Tests run: 37, Failures: 0, Errors: 0").clean());
  }

  @Test
  void summaryLessNonMavenCheckScriptOutputIsClean() {
    assertTrue(CheckEvidence.evaluate("cd repo && sh check.sh",
        "OK: greet.sh prints 'Hello, World.'").clean());
  }

  @Test
  void summaryLessNonMavenGrepOutputIsClean() {
    assertTrue(CheckEvidence.evaluate("cd repo && grep -n 'T16 scenario change.' README.md",
        "3:T16 scenario change.").clean());
  }

  @Test
  void truncatedMavenOutputWithoutSummaryFailsClosed() {
    CheckEvidence.Verdict verdict =
        CheckEvidence.evaluate(MAVEN_CHECK, TRUNCATED_WITHOUT_SUMMARY);
    assertFalse(verdict.clean());
    assertTrue(verdict.detail().contains("[output truncated: 1234 bytes omitted]"));
  }

  /**
   * T24 B3b inverted pin: a surviving complete clean summary does NOT
   * rehabilitate truncated maven output — truncation may have removed a
   * later dirty summary, so the marker alone fails closed. The permissive
   * rule used to accept this shape.
   */
  @Test
  void truncatedMavenOutputWithSurvivingCleanSummaryFailsClosed() {
    CheckEvidence.Verdict verdict = CheckEvidence.evaluate(MAVEN_CHECK,
        "[summary]\nTests run: 4, Failures: 0, Errors: 0, Skipped: 0\n\n"
            + "[maven]\n[output truncated: 1234 bytes omitted]");
    assertFalse(verdict.clean());
    assertTrue(verdict.detail().contains("[output truncated: 1234 bytes omitted]"));
  }

  /**
   * T24 B3a inverted pin: summary-less untruncated maven output is NOT
   * affirmative green evidence — the permissive rule used to accept it.
   */
  @Test
  void summaryLessUntruncatedMavenOutputFailsClosed() {
    CheckEvidence.Verdict verdict = CheckEvidence.evaluate(MAVEN_CHECK,
        "[maven]\n[INFO] Building core\n[INFO] BUILD SUCCESS");
    assertFalse(verdict.clean());
    assertTrue(verdict.detail().contains("no surefire test summary"));
  }

  /**
   * T24 B3a pin: maven's own "no tests" outputs are absent evidence, not
   * green evidence.
   */
  @Test
  void noTestsToRunOutputOnMavenCheckIsNotClean() {
    CheckEvidence.Verdict verdict = CheckEvidence.evaluate(MAVEN_CHECK,
        "[INFO] No tests to run.\n[INFO] BUILD SUCCESS");
    assertFalse(verdict.clean());
    assertTrue(verdict.detail().contains("no surefire test summary"));
  }

  /** T24 B3a pin: a skip-tests maven run is absent evidence. */
  @Test
  void testsAreSkippedOutputOnMavenCheckIsNotClean() {
    CheckEvidence.Verdict verdict = CheckEvidence.evaluate(MAVEN_CHECK,
        "[INFO] Tests are skipped.\n[INFO] BUILD SUCCESS");
    assertFalse(verdict.clean());
    assertTrue(verdict.detail().contains("no surefire test summary"));
  }

  /** T24 B3a pin: empty check output is absent evidence. */
  @Test
  void emptyOutputOnMavenCheckIsNotClean() {
    CheckEvidence.Verdict verdict = CheckEvidence.evaluate(MAVEN_CHECK, "");
    assertFalse(verdict.clean());
    assertTrue(verdict.detail().contains("no surefire test summary"));
  }

  /** T24 B3a pin: null check output is absent evidence. */
  @Test
  void nullOutputOnMavenCheckIsNotClean() {
    CheckEvidence.Verdict verdict = CheckEvidence.evaluate(MAVEN_CHECK, null);
    assertFalse(verdict.clean());
    assertTrue(verdict.detail().contains("no surefire test summary"));
  }

  @Test
  void mavenWordMatchesMvnAndMvnw() {
    assertFalse(CheckEvidence.evaluate("mvn test", TRUNCATED_WITHOUT_SUMMARY).clean());
    assertFalse(CheckEvidence.evaluate("./mvnw test", TRUNCATED_WITHOUT_SUMMARY).clean());
  }

  @Test
  void nonMavenCommandsAreNeverFailClosed() {
    assertTrue(CheckEvidence.evaluate("cd repo && sh check.sh",
        TRUNCATED_WITHOUT_SUMMARY).clean());
    assertTrue(CheckEvidence.evaluate("cd repo && grep -n 'T16 scenario change.' README.md",
        TRUNCATED_WITHOUT_SUMMARY).clean());
    assertTrue(CheckEvidence.evaluate("rg -n SearchHelper repo/src",
        TRUNCATED_WITHOUT_SUMMARY).clean());
    assertTrue(CheckEvidence.evaluate("cd repo && git log --oneline -3",
        TRUNCATED_WITHOUT_SUMMARY).clean());
  }

  /**
   * T24 B3c inverted pin: a clean summary does NOT rehabilitate maven's own
   * "no tests" marker — the mixed affirmative-plus-explicit-absence shape is
   * incomplete evidence and fails closed. The permissive rule (one
   * output-wide summaryFound boolean) used to accept this shape.
   */
  @Test
  void mixedCleanSummaryWithNoTestsMarkerIsNotClean() {
    CheckEvidence.Verdict verdict = CheckEvidence.evaluate(MAVEN_CHECK,
        GREEN_SUMMARY + "\n[INFO] No tests to run.\n[INFO] BUILD SUCCESS");
    assertFalse(verdict.clean());
    assertTrue(verdict.detail().contains("No tests to run."));
  }

  /** T24 B3c inverted pin: same mixed shape with the skip-tests marker. */
  @Test
  void mixedCleanSummaryWithSkipMarkerIsNotClean() {
    CheckEvidence.Verdict verdict = CheckEvidence.evaluate(MAVEN_CHECK,
        GREEN_SUMMARY + "\n[INFO] Tests are skipped.\n[INFO] BUILD SUCCESS");
    assertFalse(verdict.clean());
    assertTrue(verdict.detail().contains("Tests are skipped."));
  }

  /**
   * T24 B3c control: the same clean summary plus BUILD SUCCESS with NO
   * incomplete-evidence marker stays clean — the new rule rejects only the
   * mixed shape, not every green maven run.
   */
  @Test
  void cleanSummaryWithBuildSuccessAndNoMarkerStaysClean() {
    CheckEvidence.Verdict verdict = CheckEvidence.evaluate(MAVEN_CHECK,
        GREEN_SUMMARY + "\n[INFO] BUILD SUCCESS");
    assertTrue(verdict.clean());
    assertNull(verdict.detail());
  }

  /**
   * T24 B3c carve-out: the marker rejection is maven-shaped only — a non-maven
   * declared check whose output contains both marker strings keeps exact
   * exit-code-governed semantics and stays clean.
   */
  @Test
  void nonMavenCheckWithBothMarkersIsClean() {
    assertTrue(CheckEvidence.evaluate("cd repo && sh check.sh",
        "[INFO] No tests to run.\n[INFO] Tests are skipped.\nok").clean());
  }
}
