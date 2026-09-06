package org.folio.factory.sandbox.harness;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.tools.GitDiffTool;
import org.folio.factory.sandbox.tools.ToolResult;
import org.springframework.stereotype.Component;

@Component
public class CodingHarness {

  /** Bound for the preserved final model response, in characters. */
  public static final int FINAL_TEXT_MAX_CHARS = 4096;

  private static final String TRUNCATION_MARKER = "[final response truncated: %d chars omitted]";

  private final ChatModelAdapter adapter;
  private final ToolDispatcher dispatcher;
  private final GitDiffTool gitDiffTool;
  private final Clock clock;
  private final HarnessConfig config;
  private final String systemPrompt;

  public CodingHarness(ChatModelAdapter adapter, ToolDispatcher dispatcher, GitDiffTool gitDiffTool,
      Clock clock, HarnessConfig config) {
    this.adapter = adapter;
    this.dispatcher = dispatcher;
    this.gitDiffTool = gitDiffTool;
    this.clock = clock;
    this.config = config;
    this.systemPrompt = Prompts.codingWorkerSystemPrompt();
  }

  public HarnessReport run(SandboxHandle handle, TaskContract contract, Path workDir) {
    long startedAt = clock.millis();
    long timeoutMs = config.jobTimeoutMin() * 60_000L;
    List<ChatMessage> history = new ArrayList<>();
    int steps = 0;
    int formatErrors = 0;
    int consecutiveFormatErrors = 0;
    long tokensIn = 0;
    long tokensOut = 0;
    String finalReply = null;
    int successfulToolCalls = 0;
    String taskGoal = contract.directive();
    try (Trajectory trajectory = Trajectory.open(workDir)) {
      while (true) {
        if (steps >= config.maxSteps()) {
          return finish(trajectory, handle, steps, formatErrors, tokensIn, tokensOut,
              successfulToolCalls, finalReply, contract,
              HarnessReport.Outcome.FAILED, HarnessReport.StopReason.STEPS_EXCEEDED);
        }
        if (consecutiveFormatErrors >= config.maxFormatErrors()) {
          return finish(trajectory, handle, steps, formatErrors, tokensIn, tokensOut,
              successfulToolCalls, finalReply, contract,
              HarnessReport.Outcome.FAILED, HarnessReport.StopReason.FORMAT_ERRORS_EXCEEDED);
        }
        if (clock.millis() - startedAt >= timeoutMs) {
          return finish(trajectory, handle, steps, formatErrors, tokensIn, tokensOut,
              successfulToolCalls, finalReply, contract,
              HarnessReport.Outcome.FAILED, HarnessReport.StopReason.TIMEOUT);
        }
        long stepStartedAt = clock.millis();
        ModelReply reply;
        try {
          reply = adapter.reply(systemPrompt, taskGoal, List.copyOf(history));
        } catch (RuntimeException e) {
          steps++;
          trajectory.append(new StepRecord(clock.instant().toString(), steps, "model_error",
              0, 0, false, clock.millis() - stepStartedAt));
          return finish(trajectory, handle, steps, formatErrors, tokensIn, tokensOut,
              successfulToolCalls, finalReply, contract,
              HarnessReport.Outcome.FAILED, HarnessReport.StopReason.MODEL_ERROR);
        }
        steps++;
        if (reply.usage() != null) {
          tokensIn += reply.usage().promptTokens();
          tokensOut += reply.usage().completionTokens();
        }
        if (!reply.isToolCall()) {
          finalReply = reply.text();
          trajectory.appendFinal(new StepRecord(clock.instant().toString(), steps, "final",
              0, safeLength(reply.text()), true, clock.millis() - stepStartedAt),
              boundedFinalText(reply.text()));
          return finish(trajectory, handle, steps, formatErrors, tokensIn, tokensOut,
              successfulToolCalls, finalReply, contract,
              HarnessReport.Outcome.COMPLETED, HarnessReport.StopReason.COMPLETED);
        }
        for (ToolCall toolCall : reply.toolCalls()) {
          ToolExecution execution = dispatcher.execute(handle, toolCall.name(), toolCall.arguments());
          if (execution.formatError()) {
            formatErrors++;
            consecutiveFormatErrors++;
          } else {
            consecutiveFormatErrors = 0;
          }
          if (execution.result().ok()) {
            successfulToolCalls++;
          }
          String resultText = resultText(execution.result());
          history.add(ChatMessage.assistantToolCall(toolCall.id(), toolCall.name(), toolCall.arguments()));
          history.add(ChatMessage.toolResult(toolCall.id(), toolCall.name(), resultText));
          trajectory.append(new StepRecord(clock.instant().toString(), steps, toolCall.name(),
              safeLength(toolCall.arguments()), safeLength(resultText), execution.result().ok(),
              clock.millis() - stepStartedAt));
        }
      }
    }
  }

  private HarnessReport finish(Trajectory trajectory, SandboxHandle handle, int steps, int formatErrors,
      long tokensIn, long tokensOut, int successfulToolCalls, String finalReply,
      TaskContract contract, HarnessReport.Outcome outcome, HarnessReport.StopReason stopReason) {
    int filesChanged = 0;
    long diffSizeBytes = 0L;
    ToolResult diff = gitDiffTool.diff(handle);
    if (diff.ok()) {
      DiffStats stats = DiffStats.parse(diff.output());
      filesChanged = stats.filesChanged();
      diffSizeBytes = stats.diffSizeBytes();
    }
    TaskOutcome taskOutcome;
    TaskOutcome.Reason taskOutcomeReason;
    if (outcome == HarnessReport.Outcome.FAILED) {
      taskOutcome = TaskOutcome.FAILED;
      taskOutcomeReason = TaskOutcome.Reason.MODEL_RUN_FAILED;
    } else if (finalReply == null || finalReply.isBlank()) {
      taskOutcome = TaskOutcome.FAILED;
      taskOutcomeReason = TaskOutcome.Reason.MISSING_FINAL_REPORT;
    } else if (filesChanged == 0) {
      if (!contract.allowsNoOp()) {
        taskOutcome = TaskOutcome.FAILED;
        taskOutcomeReason = TaskOutcome.Reason.NO_OP_NOT_PERMITTED;
      } else if (successfulToolCalls == 0) {
        taskOutcome = TaskOutcome.FAILED;
        taskOutcomeReason = TaskOutcome.Reason.NO_VERIFICATION_EVIDENCE;
      } else {
        taskOutcome = TaskOutcome.SUCCEEDED;
        taskOutcomeReason = TaskOutcome.Reason.NO_OP_VERIFIED;
      }
    } else {
      taskOutcome = TaskOutcome.SUCCEEDED;
      taskOutcomeReason = TaskOutcome.Reason.CHANGES_DELIVERED;
    }
    HarnessReport report = new HarnessReport(steps, outcome, stopReason, filesChanged, diffSizeBytes,
        formatErrors, tokensIn, tokensOut, taskOutcome, taskOutcomeReason,
        boundedFinalText(finalReply));
    trajectory.append(report);
    return report;
  }

  /**
   * Bounds the preserved final response so one runaway answer cannot blow up
   * the inspectable artifacts; the truncation marker follows the
   * {@code OutputLimiter} convention and the full original length stays
   * recorded as the final record's {@code out_len}.
   */
  static String boundedFinalText(String text) {
    if (text == null) {
      return "";
    }
    if (text.length() <= FINAL_TEXT_MAX_CHARS) {
      return text;
    }
    int omitted = text.length() - FINAL_TEXT_MAX_CHARS;
    return text.substring(0, FINAL_TEXT_MAX_CHARS) + "\n"
        + String.format(TRUNCATION_MARKER, omitted);
  }

  private static String resultText(ToolResult result) {
    if (result.ok()) {
      return result.output() == null ? "" : result.output();
    }
    String error = result.error() == null ? "unknown error" : result.error();
    String output = result.output() == null ? "" : result.output();
    return output.isBlank() ? "ERROR: " + error : "ERROR: " + error + "\n" + output;
  }

  private static int safeLength(String text) {
    return text == null ? 0 : text.length();
  }
}
