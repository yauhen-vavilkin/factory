package org.folio.factory.sandbox.harness;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class Trajectory implements Closeable {

  public static final String FILE_NAME = "trajectory.jsonl";

  private final BufferedWriter writer;
  private final ObjectMapper mapper = JsonMapper.builder().build();

  private Trajectory(BufferedWriter writer) {
    this.writer = writer;
  }

  public static Trajectory open(Path workDir) {
    try {
      Files.createDirectories(workDir);
      BufferedWriter writer = Files.newBufferedWriter(workDir.resolve(FILE_NAME),
          StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      return new Trajectory(writer);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to open trajectory file in " + workDir, e);
    }
  }

  public void append(StepRecord record) {
    ObjectNode node = stepFields(record);
    writeLine(node.toString());
  }

  /**
   * The final record is the one place a step carries text: the model's last
   * answer, already bounded by the harness ({@code out_len} keeps the full
   * original length when the text was truncated).
   */
  public void appendFinal(StepRecord record, String text) {
    ObjectNode node = stepFields(record);
    node.put("text", text == null ? "" : text);
    writeLine(node.toString());
  }

  private ObjectNode stepFields(StepRecord record) {
    ObjectNode node = mapper.createObjectNode();
    node.put("ts", record.ts());
    node.put("step", record.stepNumber());
    node.put("tool", record.toolName());
    node.put("args_len", record.argsLength());
    node.put("out_len", record.outputLength());
    node.put("duration_ms", record.durationMs());
    node.put("ok", record.ok());
    return node;
  }

  public void append(HarnessReport report) {
    ObjectNode node = mapper.createObjectNode();
    node.put("steps", report.steps());
    node.put("outcome", report.outcome().name());
    node.put("stop_reason", report.stopReason().name());
    node.put("task_outcome", report.taskOutcome().name());
    node.put("task_outcome_reason", report.taskOutcomeReason().name());
    node.put("files_changed", report.filesChanged());
    node.put("diff_size_bytes", report.diffSizeBytes());
    node.put("format_errors", report.formatErrors());
    node.put("tokens_in", report.tokensIn());
    node.put("tokens_out", report.tokensOut());
    writeLine(node.toString());
  }

  @Override
  public void close() {
    try {
      writer.close();
    } catch (IOException e) {
      throw new UncheckedIOException("failed to close trajectory file", e);
    }
  }

  private void writeLine(String json) {
    try {
      writer.write(json);
      writer.write('\n');
      writer.flush();
    } catch (IOException e) {
      throw new UncheckedIOException("failed to append trajectory line", e);
    }
  }
}
