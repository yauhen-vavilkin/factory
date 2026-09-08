package org.folio.factory.sandbox.harness;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.tools.GitDiffTool;
import org.folio.factory.sandbox.tools.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class CodingHarness {

  /** Bound for the preserved final model response, in characters. */
  public static final int FINAL_TEXT_MAX_CHARS = 4096;

  private static final String TRUNCATION_MARKER = "[final response truncated: %d chars omitted]";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

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
    // Total malformed tool calls so far; the ONLY format-error counter. Per
    // the documented rule (HarnessConfig#maxFormatErrors) it is a whole-run
    // budget: valid calls never reset it, and it is enforced at model-reply
    // (batch) boundaries below.
    int formatErrors = 0;
    long tokensIn = 0;
    long tokensOut = 0;
    String finalReply = null;
    String taskGoal = contract.directive();
    // T24 R1: the verification obligations derived from the frozen contract;
    // terminal acceptance depends on their fresh, identity-bound evidence.
    VerificationLedger ledger = VerificationLedger.from(contract);
    try (Trajectory trajectory = Trajectory.open(workDir)) {
      while (true) {
        if (steps >= config.maxSteps()) {
          return finish(trajectory, handle, steps, formatErrors, tokensIn, tokensOut,
              finalReply, contract, ledger,
              HarnessReport.Outcome.FAILED, HarnessReport.StopReason.STEPS_EXCEEDED);
        }
        // T23 R2: enforce the documented whole-run total budget of malformed
        // tool calls, checked before the next model reply.
        if (formatErrors >= config.maxFormatErrors()) {
          return finish(trajectory, handle, steps, formatErrors, tokensIn, tokensOut,
              finalReply, contract, ledger,
              HarnessReport.Outcome.FAILED, HarnessReport.StopReason.FORMAT_ERRORS_EXCEEDED);
        }
        if (clock.millis() - startedAt >= timeoutMs) {
          return finish(trajectory, handle, steps, formatErrors, tokensIn, tokensOut,
              finalReply, contract, ledger,
              HarnessReport.Outcome.FAILED, HarnessReport.StopReason.TIMEOUT);
        }
        long stepStartedAt = clock.millis();
        ModelReply reply;
        try {
          reply = adapter.reply(systemPrompt, taskGoal, List.copyOf(history));
        } catch (EmptyModelResponseException e) {
          // T23 R1: zero generations / no assistant output is a preserved
          // diagnostic, distinct from a provider failure (MODEL_ERROR).
          steps++;
          trajectory.append(new StepRecord(clock.instant().toString(), steps, "empty_model_response",
              0, 0, false, clock.millis() - stepStartedAt));
          return finish(trajectory, handle, steps, formatErrors, tokensIn, tokensOut,
              finalReply, contract, ledger,
              HarnessReport.Outcome.FAILED, HarnessReport.StopReason.EMPTY_MODEL_RESPONSE);
        } catch (RuntimeException e) {
          steps++;
          trajectory.append(new StepRecord(clock.instant().toString(), steps, "model_error",
              0, 0, false, clock.millis() - stepStartedAt));
          return finish(trajectory, handle, steps, formatErrors, tokensIn, tokensOut,
              finalReply, contract, ledger,
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
              finalReply, contract, ledger,
              HarnessReport.Outcome.COMPLETED, HarnessReport.StopReason.COMPLETED);
        }
        for (ToolCall toolCall : reply.toolCalls()) {
          // T23 R3: each trajectory step reports this call's own execution
          // time, not time accumulated since the model reply began.
          long callStartedAt = clock.millis();
          // T24 B2: for an exec of a declared check command, capture the
          // working-tree identity immediately BEFORE dispatching it; the
          // receipt binds only if the identity captured immediately AFTER
          // the exec equals this one (the provably stable tested state).
          String checkCommand = declaredCheckCommand(ledger, toolCall);
          ToolResult identityBefore =
              checkCommand == null ? null : gitDiffTool.worktreeIdentity(handle);
          ToolExecution execution = dispatcher.execute(handle, toolCall.name(), toolCall.arguments());
          long callDurationMs = clock.millis() - callStartedAt;
          if (execution.formatError()) {
            // T23 R2: the total budget only ever grows; a valid call in the
            // same batch does not reset it.
            formatErrors++;
          }
          String resultText = resultText(execution.result());
          history.add(ChatMessage.assistantToolCall(toolCall.id(), toolCall.name(), toolCall.arguments()));
          history.add(ChatMessage.toolResult(toolCall.id(), toolCall.name(), resultText));
          trajectory.append(new StepRecord(clock.instant().toString(), steps, toolCall.name(),
              safeLength(toolCall.arguments()), safeLength(resultText), execution.result().ok(),
              callDurationMs));
          recordCheckReceipt(handle, ledger, execution, checkCommand, identityBefore);
        }
      }
    }
  }

  /**
   * T24 R1/R3 + B1/B2/B3: an {@code exec} of a contract-declared check
   * command leaves a receipt bound to the delivered-content identity
   * ({@link GitDiffTool#worktreeIdentity}) only when the identity captured
   * immediately before dispatch and immediately after the exec both succeed
   * and are equal — the provably stable tested state. B3: after a clean exit,
   * the check's own output must also evaluate clean under the
   * {@link CheckEvidence} output-evidence policy before the receipt can be
   * green. Any other outcome (a dirty summary such as a skipped case, the
   * check mutated relevant content after its assertions, or an identity
   * capture failed on either side) records a non-green receipt that can never
   * evaluate to PASS. Fail-closed throughout.
   */
  private void recordCheckReceipt(SandboxHandle handle, VerificationLedger ledger,
      ToolExecution execution, String checkCommand, ToolResult identityBefore) {
    if (checkCommand == null) {
      return;
    }
    ToolResult identityAfter = gitDiffTool.worktreeIdentity(handle);
    if (!execution.result().ok()) {
      ledger.record(checkCommand, false,
          identityAfter.ok() ? identityAfter.output() : null, execution.result().error());
      return;
    }
    // T24 B3: exit 0 alone is not green evidence — the check's own output
    // must carry affirmative clean test evidence (no non-clean
    // prefix-family summary, and on a maven-shaped check neither a
    // truncation marker, an absent summary, nor Maven's explicit
    // incomplete-evidence markers "No tests to run."/"Tests are skipped."
    // even alongside a surviving clean summary).
    CheckEvidence.Verdict verdict =
        CheckEvidence.evaluate(checkCommand, execution.result().output());
    if (!verdict.clean()) {
      ledger.record(checkCommand, false,
          identityAfter.ok() ? identityAfter.output() : null, verdict.detail());
      return;
    }
    if (identityBefore != null && identityBefore.ok() && identityAfter.ok()
        && identityBefore.output().equals(identityAfter.output())) {
      ledger.record(checkCommand, true, identityBefore.output(), null);
      return;
    }
    ledger.recordIndeterminate(checkCommand, indeterminateDetail(identityBefore, identityAfter));
  }

  /**
   * T24 B2: the command when this call is an {@code exec} of a contract
   * declared check command, else {@code null} (non-check calls get no
   * identity captures and no boundary).
   */
  private static String declaredCheckCommand(VerificationLedger ledger, ToolCall toolCall) {
    if (!"exec".equals(toolCall.name())) {
      return null;
    }
    String command = execCommand(toolCall.arguments());
    return command != null && ledger.isCheckCommand(command) ? command : null;
  }

  /** T24 B2: the explicit reason a green check exec could not be bound. */
  private static String indeterminateDetail(ToolResult before, ToolResult after) {
    if (before == null || !before.ok()) {
      return "working-tree identity could not be captured before the check ran: "
          + (before == null || before.error() == null ? "unknown error" : before.error());
    }
    if (!after.ok()) {
      return "working-tree identity could not be captured after the check ran: "
          + (after.error() == null ? "unknown error" : after.error());
    }
    return "working tree changed while the check ran (before " + before.output()
        + ", after " + after.output() + "); the tested state is indeterminate";
  }

  private static String execCommand(String argumentsJson) {
    try {
      JsonNode args = MAPPER.readTree(
          argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      JsonNode cmd = args.get("cmd");
      return cmd != null && cmd.isString() ? cmd.textValue() : null;
    } catch (JacksonException e) {
      return null;
    }
  }

  private HarnessReport finish(Trajectory trajectory, SandboxHandle handle, int steps, int formatErrors,
      long tokensIn, long tokensOut, String finalReply, TaskContract contract,
      VerificationLedger ledger, HarnessReport.Outcome outcome, HarnessReport.StopReason stopReason) {
    // T18/R4-compatible display fields, still computed from the display diff.
    int filesChanged = 0;
    long diffSizeBytes = 0L;
    ToolResult diff = gitDiffTool.diff(handle);
    if (diff.ok()) {
      DiffStats stats = DiffStats.parse(diff.output());
      filesChanged = stats.filesChanged();
      diffSizeBytes = stats.diffSizeBytes();
    }
    // T24 B1: the terminal decision's identity is the delivered-content tree
    // id. Fail-closed: a capture failure leaves no identity, and no receipt
    // can ever be fresh against a missing identity.
    String resultIdentity = null;
    ToolResult identity = gitDiffTool.worktreeIdentity(handle);
    if (identity.ok()) {
      resultIdentity = identity.output();
    }
    // T24 R1-R3: every obligation needs fresh, green evidence bound to the
    // final result identity before any terminal success outcome.
    VerificationLedger.VerificationSummary verification = ledger.evaluate(resultIdentity);
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
      } else if (verification.checks().isEmpty()) {
        // T24 R2: with no declared check there is no discriminating
        // evidence, so a no-op can never be a verified success.
        taskOutcome = TaskOutcome.FAILED;
        taskOutcomeReason = TaskOutcome.Reason.NO_VERIFICATION_EVIDENCE;
      } else if (!verification.allPassed()) {
        taskOutcome = TaskOutcome.FAILED;
        taskOutcomeReason = reasonFor(verification);
      } else {
        taskOutcome = TaskOutcome.SUCCEEDED;
        taskOutcomeReason = TaskOutcome.Reason.NO_OP_VERIFIED;
      }
    } else {
      if (verification.checks().isEmpty()) {
        // T24 R1: a changed-file count alone is not requirement evidence.
        taskOutcome = TaskOutcome.FAILED;
        taskOutcomeReason = TaskOutcome.Reason.NO_REQUIRED_CHECKS;
      } else if (!verification.allPassed()) {
        taskOutcome = TaskOutcome.FAILED;
        taskOutcomeReason = reasonFor(verification);
      } else {
        taskOutcome = TaskOutcome.SUCCEEDED;
        taskOutcomeReason = TaskOutcome.Reason.CHANGES_DELIVERED;
      }
    }
    HarnessReport report = new HarnessReport(steps, outcome, stopReason, filesChanged, diffSizeBytes,
        formatErrors, tokensIn, tokensOut, taskOutcome, taskOutcomeReason,
        boundedFinalText(finalReply), verification);
    trajectory.append(report);
    return report;
  }

  private static TaskOutcome.Reason reasonFor(
      VerificationLedger.VerificationSummary verification) {
    VerificationLedger.Status status = verification.checks().stream()
        .map(VerificationLedger.CheckState::status)
        .filter(s -> s != VerificationLedger.Status.PASS)
        .findFirst()
        .orElse(VerificationLedger.Status.MISSING);
    return switch (status) {
      case MISSING -> TaskOutcome.Reason.REQUIRED_CHECK_MISSING;
      case FAILED -> TaskOutcome.Reason.REQUIRED_CHECK_FAILED;
      case STALE -> TaskOutcome.Reason.REQUIRED_CHECK_STALE;
      case PASS -> TaskOutcome.Reason.CHANGES_DELIVERED;
    };
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
