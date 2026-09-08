package org.folio.factory.sandbox.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class HarnessReportTest {

  @Test
  void holdsRunOutcome() {
    HarnessReport report = new HarnessReport(12, HarnessReport.Outcome.FAILED,
        HarnessReport.StopReason.STEPS_EXCEEDED, 3, 8192L, 1, 0L, 0L,
        TaskOutcome.FAILED, TaskOutcome.Reason.MODEL_RUN_FAILED, "done");

    assertEquals(12, report.steps());
    assertEquals(HarnessReport.Outcome.FAILED, report.outcome());
    assertEquals(HarnessReport.StopReason.STEPS_EXCEEDED, report.stopReason());
    assertEquals(3, report.filesChanged());
    assertEquals(8192L, report.diffSizeBytes());
    assertEquals(1, report.formatErrors());
    assertEquals(0L, report.tokensIn());
    assertEquals(0L, report.tokensOut());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertEquals(TaskOutcome.Reason.MODEL_RUN_FAILED, report.taskOutcomeReason());
  }

  @Test
  void holdsTokenTotals() {
    HarnessReport report = new HarnessReport(12, HarnessReport.Outcome.FAILED,
        HarnessReport.StopReason.STEPS_EXCEEDED, 3, 8192L, 1, 137L, 63L,
        TaskOutcome.FAILED, TaskOutcome.Reason.MODEL_RUN_FAILED, "done");

    assertEquals(137L, report.tokensIn());
    assertEquals(63L, report.tokensOut());
  }

  @Test
  void enumsCoverAllExits() {
    assertEquals(6, HarnessReport.StopReason.values().length);
    assertEquals(2, HarnessReport.Outcome.values().length);
    assertEquals(2, TaskOutcome.values().length);
    assertEquals(10, TaskOutcome.Reason.values().length);
  }

  /** T24 R4: the report carries the verification diagnostics additively. */
  @Test
  void holdsVerificationSummary() {
    VerificationLedger.CheckState state = new VerificationLedger.CheckState(
        "tests", "mvn test", VerificationLedger.Status.PASS, "abc123", "fresh");
    VerificationLedger.VerificationSummary verification =
        new VerificationLedger.VerificationSummary("abc123", List.of(state));
    HarnessReport report = new HarnessReport(2, HarnessReport.Outcome.COMPLETED,
        HarnessReport.StopReason.COMPLETED, 1, 64L, 0, 0L, 0L,
        TaskOutcome.SUCCEEDED, TaskOutcome.Reason.CHANGES_DELIVERED, "done", verification);

    assertEquals(verification, report.verification());
    assertEquals(VerificationLedger.Status.PASS, report.verification().checks().get(0).status());
    assertEquals("abc123", report.verification().resultIdentity());
  }

  /** T24 R4: the T18 eleven-argument constructor still compiles, with no checks. */
  @Test
  void legacyConstructorDefaultsToEmptyVerification() {
    HarnessReport report = new HarnessReport(1, HarnessReport.Outcome.COMPLETED,
        HarnessReport.StopReason.COMPLETED, 0, 0L, 0, 0L, 0L,
        TaskOutcome.FAILED, TaskOutcome.Reason.NO_OP_NOT_PERMITTED, "done");

    assertEquals(VerificationLedger.VerificationSummary.NONE, report.verification());
    assertEquals(0, report.verification().checks().size());
    assertEquals(null, report.verification().resultIdentity());
  }

  @Test
  void taskOutcomeIsSeparateFromModelOutcome() {
    HarnessReport report = new HarnessReport(1, HarnessReport.Outcome.COMPLETED,
        HarnessReport.StopReason.COMPLETED, 0, 0L, 0, 0L, 0L,
        TaskOutcome.FAILED, TaskOutcome.Reason.NO_OP_NOT_PERMITTED, "done");

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
  }
}
