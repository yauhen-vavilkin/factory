package org.folio.factory.sandbox.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TrajectoryTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @TempDir
  Path workDir;

  @Test
  void writesOneJsonLinePerStepAndFinalReport() throws Exception {
    StepRecord first = new StepRecord("2026-09-04T10:00:00Z", 1, "read", 22, 430, true, 120L);
    StepRecord second = new StepRecord("2026-09-04T10:00:05Z", 2, "apply_patch", 512, 14, false, 900L);
    HarnessReport report = new HarnessReport(2, HarnessReport.Outcome.COMPLETED,
        HarnessReport.StopReason.COMPLETED, 1, 2048L, 0, 0L, 0L,
        TaskOutcome.SUCCEEDED, TaskOutcome.Reason.CHANGES_DELIVERED, "done");

    try (Trajectory trajectory = Trajectory.open(workDir)) {
      trajectory.append(first);
      trajectory.append(second);
      trajectory.append(report);
    }

    List<String> lines = Files.readAllLines(workDir.resolve(Trajectory.FILE_NAME));
    assertEquals(3, lines.size());
    JsonNode stepOne = mapper.readTree(lines.get(0));
    assertEquals("2026-09-04T10:00:00Z", stepOne.get("ts").textValue());
    assertEquals(1, stepOne.get("step").intValue());
    assertEquals("read", stepOne.get("tool").textValue());
    assertEquals(22, stepOne.get("args_len").intValue());
    assertEquals(430, stepOne.get("out_len").intValue());
    assertEquals(120L, stepOne.get("duration_ms").longValue());
    assertTrue(stepOne.get("ok").booleanValue());
    JsonNode stepTwo = mapper.readTree(lines.get(1));
    assertFalse(stepTwo.get("ok").booleanValue());
    JsonNode reportNode = mapper.readTree(lines.get(2));
    assertEquals(2, reportNode.get("steps").intValue());
    assertEquals("COMPLETED", reportNode.get("outcome").textValue());
    assertEquals("COMPLETED", reportNode.get("stop_reason").textValue());
    assertEquals(1, reportNode.get("files_changed").intValue());
    assertEquals(2048L, reportNode.get("diff_size_bytes").longValue());
    assertEquals(0, reportNode.get("format_errors").intValue());
    assertEquals(0L, reportNode.get("tokens_in").longValue());
    assertEquals(0L, reportNode.get("tokens_out").longValue());
  }

  @Test
  void reportLineCarriesTokenTotals() throws Exception {
    HarnessReport report = new HarnessReport(3, HarnessReport.Outcome.COMPLETED,
        HarnessReport.StopReason.COMPLETED, 1, 512L, 0, 137L, 63L,
        TaskOutcome.SUCCEEDED, TaskOutcome.Reason.CHANGES_DELIVERED, "done");

    try (Trajectory trajectory = Trajectory.open(workDir)) {
      trajectory.append(report);
    }

    List<String> lines = Files.readAllLines(workDir.resolve(Trajectory.FILE_NAME));
    assertEquals(1, lines.size());
    JsonNode reportNode = mapper.readTree(lines.get(0));
    assertEquals(137L, reportNode.get("tokens_in").longValue());
    assertEquals(63L, reportNode.get("tokens_out").longValue());
  }

  @Test
  void reportLineCarriesTaskOutcomeDistinctFromModelOutcome() throws Exception {
    HarnessReport report = new HarnessReport(1, HarnessReport.Outcome.COMPLETED,
        HarnessReport.StopReason.COMPLETED, 0, 0L, 0, 0L, 0L,
        TaskOutcome.FAILED, TaskOutcome.Reason.NO_OP_NOT_PERMITTED, "done");

    try (Trajectory trajectory = Trajectory.open(workDir)) {
      trajectory.append(report);
    }

    List<String> lines = Files.readAllLines(workDir.resolve(Trajectory.FILE_NAME));
    assertEquals(1, lines.size());
    JsonNode reportNode = mapper.readTree(lines.get(0));
    assertEquals("COMPLETED", reportNode.get("outcome").textValue());
    assertEquals("FAILED", reportNode.get("task_outcome").textValue());
    assertEquals("NO_OP_NOT_PERMITTED", reportNode.get("task_outcome_reason").textValue());
  }

  @Test
  void finalRecordCarriesTextAfterTheStandardStepFields() throws Exception {
    try (Trajectory trajectory = Trajectory.open(workDir)) {
      trajectory.appendFinal(
          new StepRecord("2026-09-05T16:48:28Z", 3, "final", 0, 361, true, 6829L),
          "Could not reproduce; no change made.");
    }

    JsonNode node = mapper.readTree(
        Files.readString(workDir.resolve(Trajectory.FILE_NAME)));
    assertEquals("final", node.get("tool").textValue());
    assertEquals(0, node.get("args_len").intValue());
    assertEquals(361, node.get("out_len").intValue());
    assertEquals("Could not reproduce; no change made.", node.get("text").textValue());
  }

  @Test
  void regularStepRecordsStayTextFree() throws Exception {
    try (Trajectory trajectory = Trajectory.open(workDir)) {
      trajectory.append(new StepRecord("2026-09-05T16:48:21Z", 2, "exec", 120, 1165, true, 4890L));
    }

    JsonNode node = mapper.readTree(
        Files.readString(workDir.resolve(Trajectory.FILE_NAME)));
    assertFalse(node.has("text"));
    assertFalse(node.has("args"));
  }

  @Test
  void persistsEachStepImmediately() throws Exception {
    try (Trajectory trajectory = Trajectory.open(workDir)) {
      trajectory.append(new StepRecord("2026-09-04T10:00:00Z", 1, "exec", 30, 100, true, 5L));

      assertEquals(1, Files.readAllLines(workDir.resolve(Trajectory.FILE_NAME)).size());
    }
  }

  @Test
  void createsWorkDirWhenMissing() throws Exception {
    try (Trajectory trajectory = Trajectory.open(workDir.resolve("nested/deep"))) {
      trajectory.append(new StepRecord("ts", 1, "exec", 10, 10, true, 1L));
    }

    assertTrue(Files.exists(workDir.resolve("nested/deep/trajectory.jsonl")));
  }
}
