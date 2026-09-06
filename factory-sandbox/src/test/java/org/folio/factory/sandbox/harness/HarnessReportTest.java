package org.folio.factory.sandbox.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    assertEquals(5, HarnessReport.StopReason.values().length);
    assertEquals(2, HarnessReport.Outcome.values().length);
    assertEquals(2, TaskOutcome.values().length);
    assertEquals(6, TaskOutcome.Reason.values().length);
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
