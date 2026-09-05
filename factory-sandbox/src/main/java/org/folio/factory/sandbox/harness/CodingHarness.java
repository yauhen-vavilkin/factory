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

  public HarnessReport run(SandboxHandle handle, String taskGoal, Path workDir) {
    long startedAt = clock.millis();
    long timeoutMs = config.jobTimeoutMin() * 60_000L;
    List<ChatMessage> history = new ArrayList<>();
    int steps = 0;
    int formatErrors = 0;
    int consecutiveFormatErrors = 0;
    long tokensIn = 0;
    long tokensOut = 0;
    try (Trajectory trajectory = Trajectory.open(workDir)) {
      while (true) {
        if (steps >= config.maxSteps()) {
          return finish(trajectory, handle, steps, formatErrors, tokensIn, tokensOut,
              HarnessReport.Outcome.FAILED, HarnessReport.StopReason.STEPS_EXCEEDED);
        }
        if (consecutiveFormatErrors >= config.maxFormatErrors()) {
          return finish(trajectory, handle, steps, formatErrors, tokensIn, tokensOut,
              HarnessReport.Outcome.FAILED, HarnessReport.StopReason.FORMAT_ERRORS_EXCEEDED);
        }
        if (clock.millis() - startedAt >= timeoutMs) {
          return finish(trajectory, handle, steps, formatErrors, tokensIn, tokensOut,
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
              HarnessReport.Outcome.FAILED, HarnessReport.StopReason.MODEL_ERROR);
        }
        steps++;
        if (reply.usage() != null) {
          tokensIn += reply.usage().promptTokens();
          tokensOut += reply.usage().completionTokens();
        }
        if (!reply.isToolCall()) {
          trajectory.append(new StepRecord(clock.instant().toString(), steps, "final",
              0, safeLength(reply.text()), true, clock.millis() - stepStartedAt));
          return finish(trajectory, handle, steps, formatErrors, tokensIn, tokensOut,
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
      long tokensIn, long tokensOut, HarnessReport.Outcome outcome, HarnessReport.StopReason stopReason) {
    int filesChanged = 0;
    long diffSizeBytes = 0L;
    ToolResult diff = gitDiffTool.diff(handle);
    if (diff.ok()) {
      DiffStats stats = DiffStats.parse(diff.output());
      filesChanged = stats.filesChanged();
      diffSizeBytes = stats.diffSizeBytes();
    }
    HarnessReport report = new HarnessReport(steps, outcome, stopReason, filesChanged, diffSizeBytes,
        formatErrors, tokensIn, tokensOut);
    trajectory.append(report);
    return report;
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
