package org.folio.factory.app.web;

import org.folio.factory.app.web.DeveloperRunView.ActivityRow;
import org.folio.factory.app.web.DeveloperRunView.ActivityTone;
import org.folio.factory.app.web.DeveloperRunView.Step;
import org.folio.factory.app.web.DeveloperRunView.StepState;
import org.folio.factory.core.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.folio.factory.app.web.DeveloperRunView.StepState.CURRENT;
import static org.folio.factory.app.web.DeveloperRunView.StepState.DONE;
import static org.folio.factory.app.web.DeveloperRunView.StepState.FAILED;
import static org.folio.factory.app.web.DeveloperRunView.StepState.PENDING;
import static org.folio.factory.app.web.DeveloperRunView.StepState.QUESTION;

class DeveloperExecutionViewTest {
    private final DeveloperExecutionView view = new DeveloperExecutionView(JsonMapper.builder().build());

    @Test void theHeaderSettingsComeFromTheCodingStartAndStayEmptyWithoutAModel() {
        var execution = new PipelineExecution("dev-factory", "0.8.0", "{}");
        Instant start = Instant.parse("2026-09-22T10:00:00Z");
        assertThat(view.build(execution, List.of(), List.of(), start).settings()).isEmpty();

        var runtimeOnly = runtime(Map.of("activity", "coding_starting", "runtime", "pi"), start);
        assertThat(view.build(execution, List.of(), List.of(runtimeOnly), start).settings()).isEmpty();

        var identified = runtime(Map.of("activity", "coding_starting", "runtime", "pi",
                "model", "gemini-3.8-flash", "provider", "example-provider",
                "image", "factory-dev-pi:0.85.1-java21"), start.plusSeconds(1));
        var facts = view.build(execution, List.of(), List.of(identified, runtimeOnly), start.plusSeconds(2));
        assertThat(facts.settings()).isEqualTo("gemini-3.8-flash");
        // Provider, runtime and image are not on the run page any more; model and effort are in the header.
        assertThat(facts.toString()).doesNotContain("example-provider", "factory-dev-pi");

        var restarted = runtime(Map.of("activity", "pi_starting", "runtime", "pi"), start.plusSeconds(3));
        assertThat(view.build(execution, List.of(), List.of(restarted, identified), start.plusSeconds(4)).settings())
                .isEmpty();
    }

    @Test void theSettingsNameTheChosenModelAndItsEffort() {
        var execution = new PipelineExecution("dev-factory", "0.9.0", "{}");
        Instant start = Instant.parse("2026-11-03T10:00:00Z");
        var started = runtime(Map.of("activity", "coding_starting", "runtime", "claude-code",
                "provider", "anthropic-messages", "modelId", "codemie-sonnet-5.5", "model", "claude-sonnet-5-5",
                "effort", "high", "maxTurns", 200), start);

        assertThat(view.build(execution, List.of(), List.of(started), start.plusSeconds(1)).settings())
                .isEqualTo("codemie-sonnet-5.5 · high");
    }

    @Test void runsFromBeforeTheModelsFileShowTheirRunProfileAsTheModel() {
        var execution = new PipelineExecution("dev-factory", "0.9.0", "{}");
        Instant start = Instant.parse("2026-10-27T10:00:00Z");
        var started = runtime(Map.of("activity", "coding_starting", "runtime", "claude-code",
                "provider", "anthropic-messages", "profile", "glm-5.3", "profileLabel", "GLM-5.3 (Z.ai)",
                "model", "glm-5.3", "effort", "high (CLI default)", "maxTurns", 100), start);

        assertThat(view.build(execution, List.of(), List.of(started), start.plusSeconds(1)).settings())
                .isEqualTo("GLM-5.3 (Z.ai) · high (CLI default)");
    }

    @Test void configuredModelIsVisibleBeforeCodingAndDoesNotImplyAgentStarted() {
        var execution = new PipelineExecution("dev-factory", "0.8.0", "{}");
        Instant start = Instant.parse("2026-09-22T10:00:00Z");
        var configured = runtime(Map.of("activity", "coding_configured", "runtime", "pi",
                "model", "planned-model", "provider", "example-provider", "image", "coding-image"), start);
        var facts = view.build(execution, List.of(), List.of(configured), start);
        assertThat(facts.settings()).isEqualTo("planned-model");
        assertThat(facts.steps().cards()).extracting(Step::state).containsExactly(PENDING, PENDING, PENDING, PENDING);
        assertThat(facts.activity().rows()).isEmpty();

        execution.setErrorMessage("Starting build hit a network/download failure");
        assertThat(view.build(execution, List.of(), List.of(configured), start).reason())
                .isEqualTo(execution.getErrorMessage());
        var started = runtime(Map.of("activity", "coding_starting", "runtime", "pi", "model", "actual-model"),
                start.plusSeconds(1));
        var laterConfig = runtime(Map.of("activity", "coding_configured", "model", "other-model"), start.plusSeconds(2));
        assertThat(view.build(execution, List.of(), List.of(configured, started, laterConfig), start.plusSeconds(3))
                .settings()).isEqualTo("actual-model");
    }

    @Test void theSettingsDoNotAdoptTheModelOfAFeedbackRound() {
        var execution = new PipelineExecution("dev-factory", "0.9.0", "{}");
        Instant start = Instant.parse("2026-09-24T10:00:00Z");
        var initial = runtime(Map.of("activity", "coding_starting", "model", "initial-model", "runtime", "pi"), start);
        var correction = runtime(Map.of("activity", "coding_starting", "model", "correction-model",
                "runtime", "scripted", "round", 1), start.plusSeconds(60));
        assertThat(view.build(execution, List.of(), List.of(initial, correction), start.plusSeconds(61)).settings())
                .isEqualTo("initial-model");
    }

    @Test void absentVerificationCountersRemainUnknown() {
        var execution = new PipelineExecution("dev-factory", "0.8.0", "{}");
        execution.setStatus(ExecutionStatus.COMPLETED);
        var facts = view.build(execution, List.of(artifact("dev_verification.json", 1,
                "{\"result\":\"FAIL\",\"exitCode\":1,\"testCount\":null,\"failureCount\":null,\"errorCount\":null}")),
                List.of(), Instant.now());
        assertThat(facts.reason()).contains("tests unknown", "failures unknown", "errors unknown");
        assertThat(facts.steps().cards().get(2).stateLabel()).isEqualTo("Failed");
    }

    @Test void technicalDetailsLeaveOutRawRepairStates() {
        var execution = new PipelineExecution("dev-factory", "0.8.0", "{}");
        var facts = view.build(execution, List.of(artifact("dev_result.json", 1,
                "{\"repair\":\"NOT_ATTEMPTED\",\"repairReason\":\"Durable single attempt is not supported\"}")),
                List.of(), Instant.now());
        assertThat(facts.technical().toString()).doesNotContain("NOT_ATTEMPTED", "Durable single attempt");
    }

    @Test void productStatusUsesLatestOutcomeAndDoesNotTreatSkippedDeliveryAsSuccess() {
        var execution = new PipelineExecution("dev-factory", "1", "{}");
        execution.setStatus(ExecutionStatus.COMPLETED);
        for (String state : List.of("DEVELOPMENT_FAILED", "VERIFICATION_FAILED", "DELIVERY_BLOCKED",
                "UNSUPPORTED", "BLOCKED", "BLOCKED_ENVIRONMENT", "REPOSITORY_MISMATCH",
                "DELIVERED", "VERIFIED")) {
            assertThat(productStatus(execution, List.of(
                    artifact("dev_result.json", 2, "{\"state\":\"" + state + "\"}"),
                    artifact("dev_result.json", 1, "{\"state\":\"VERIFIED\"}"),
                    artifact("dev_delivery.json", 1, "{\"state\":\"NOT_RUN\"}")))).isEqualTo(state);
        }
        assertThat(productStatus(execution, List.of())).isEqualTo("OUTCOME_UNAVAILABLE");
        assertThat(productStatus(execution, List.of(artifact("dev_result.json", 1, "not json"))))
                .isEqualTo("OUTCOME_UNAVAILABLE");
        assertThat(productStatus(execution, List.of(
                artifact("dev_result.json", 1, "{\"state\":\"secret message\"}"))))
                .isEqualTo("OUTCOME_UNAVAILABLE");
        execution.setStatus(ExecutionStatus.RUNNING);
        assertThat(productStatus(execution, List.of(artifact("dev_result.json", 1,
                "{\"state\":\"DEVELOPMENT_FAILED\"}")))).isEqualTo("RUNNING");
        execution.setStatus(ExecutionStatus.FAILED_ESCALATED);
        assertThat(productStatus(execution, List.of(artifact("dev_result.json", 1,
                "{\"state\":\"VERIFIED\"}")))).isEqualTo("FAILED_ESCALATED");
    }

    @Test void prLifecycleStatusIsTheSameForDetailAndListAndPreservesOldCompletions() {
        var execution = new PipelineExecution("dev-factory", "0.9.0", "{}");
        var waiting = artifact("dev_pr_state.json", 1,
                "{\"phase\":\"READY\",\"pr\":{\"repository\":\"fork/repo\"}}");
        execution.setStatus(ExecutionStatus.AWAITING_HITL);
        assertThat(productStatus(execution, List.of(waiting))).isEqualTo("AWAITING_PR");
        execution.setStatus(ExecutionStatus.COMPLETED);
        assertThat(productStatus(execution, List.of(waiting,
                artifact("dev_delivery.json", 1, "{\"state\":\"DELIVERED\"}")))).isEqualTo("DELIVERED");
        assertThat(productStatus(execution, List.of(artifact("dev_pr_state.json", 2,
                "{\"phase\":\"FINISHED\",\"completionReason\":\"MERGED\"}")))).isEqualTo("COMPLETED");
        execution.setStatus(ExecutionStatus.CANCELLED);
        assertThat(productStatus(execution, List.of(artifact("dev_pr_state.json", 3,
                "{\"phase\":\"STOPPED\",\"completionReason\":\"CLOSED_WITHOUT_MERGE\"}")))).isEqualTo("CANCELLED");
    }

    @Test void productStatusUsesDeliveryAndFailureEvidenceWhenFinalResultIsMissing() {
        var execution = new PipelineExecution("dev-factory", "1", "{}");
        execution.setStatus(ExecutionStatus.COMPLETED);
        assertThat(productStatus(execution, List.of(
                artifact("dev_result.json", 1, "{\"state\":\"VERIFIED\"}"),
                artifact("dev_delivery.json", 1, "{\"state\":\"DELIVERY_BLOCKED\"}"))))
                .isEqualTo("DELIVERY_BLOCKED");
        assertThat(productStatus(execution, List.of(
                artifact("dev_verification.json", 1, "{\"result\":\"FAIL\"}"))))
                .isEqualTo("VERIFICATION_FAILED");
        assertThat(productStatus(execution, List.of(
                artifact("dev_candidate.json", 1, "{\"state\":\"DEVELOPMENT_FAILED\"}"))))
                .isEqualTo("DEVELOPMENT_FAILED");
    }

    @Test void needsDecisionIsVisibleWhilePausedAndAfterTheSingleContinuation() {
        var execution = new PipelineExecution("dev-factory", "0.7.0", "{}");
        execution.setCurrentStepIndex(4);
        execution.setStatus(ExecutionStatus.AWAITING_HITL);
        var outcome = artifact("dev_coding_outcome.json", 1,
                "{\"status\":\"NEEDS_DECISION\",\"decision\":{\"question\":\"Which API?\"}}");

        assertThat(productStatus(execution, List.of(outcome))).isEqualTo("NEEDS_DECISION");
        var paused = view.build(execution, List.of(outcome), List.of(), Instant.now());
        assertThat(paused.steps().current()).isEqualTo("Implement changes");
        assertThat(paused.reasonKind()).isEqualTo(RunFacts.ReasonKind.QUESTION);
        assertThat(paused.reason()).isEqualTo("Which API?");
        assertThat(paused.asked()).isTrue();

        execution.setCurrentStepIndex(8);
        execution.setStatus(ExecutionStatus.COMPLETED);
        assertThat(productStatus(execution, List.of(outcome))).isEqualTo("NEEDS_DECISION");
        assertThat(states(view.build(execution, List.of(outcome), List.of(), Instant.now())))
                .containsExactly(DONE, DONE, DONE, DONE);

        execution.setStatus(ExecutionStatus.REJECTED);
        assertThat(productStatus(execution, List.of(outcome))).isEqualTo("REJECTED");
    }

    @Test void pendingRetryAndExhaustedRetryHaveDistinctReasons() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(3);
        execution.setErrorMessage("Starting build hit a network/download failure");
        var pending = view.build(execution, List.of(), List.of(runtime("baseline_retryable_failure",
                "Starting build hit a network/download failure", Instant.now())), Instant.now());
        assertThat(pending.reasonKind()).isEqualTo(RunFacts.ReasonKind.RETRY_PENDING);
        assertThat(pending.reason()).isEqualTo("Starting build hit a network/download failure");
        assertThat(texts(pending))
                .containsExactly("Starting build network failure · Starting build hit a network/download failure");
        execution.setStatus(ExecutionStatus.FAILED_ESCALATED);
        assertThat(view.build(execution, List.of(), List.of(), Instant.now()).reasonKind())
                .isEqualTo(RunFacts.ReasonKind.STOP);
    }

    @Test void supersededRetryErrorDisappearsAfterStartingBuildRecovery() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(3);
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setErrorMessage("Starting build hit a network/download failure; coding has not started.");

        var retrying = view.build(execution, List.of(), List.of(), Instant.now());
        assertThat(retrying.reasonKind()).isEqualTo(RunFacts.ReasonKind.RETRYING);
        assertThat(retrying.reason()).isEqualTo(execution.getErrorMessage());

        var runtimeRunning = view.build(execution, List.of(),
                List.of(runtime("coding_starting", "Coding runtime is starting", Instant.now())), Instant.now());
        assertThat(runtimeRunning.reason()).isEmpty();
        assertThat(runtimeRunning.reasonKind()).isEqualTo(RunFacts.ReasonKind.STOP);

        execution.setCurrentStepIndex(6);
        execution.setStatus(ExecutionStatus.COMPLETED);
        assertThat(view.build(execution, List.of(), List.of(), Instant.now()).reason()).isEmpty();
    }

    @Test void aPrFeedbackPhaseReadsAsPrFeedbackWithThePhaseAsPlainText() {
        var events = List.of(runtime("feedback_phase", "Updating the existing PR",
                Instant.parse("2026-09-18T10:00:00Z")));

        var rows = DeveloperRuntimeActivity.from(events, JsonMapper.builder().build(), true).view(false).rows();

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.verb()).isEqualTo("PR feedback");
            assertThat(row.target()).isEqualTo("Updating the existing PR");
            assertThat(row.code()).isFalse();
        });
        assertThat(texts(rows).getFirst()).doesNotContain("feedback phase");
    }

    @Test void theStartingBuildIsOneRowWithItsTimeAndNoLogLines() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var events = new java.util.ArrayList<AuditEvent>();
        events.add(runtime(Map.of("activity", "baseline_started", "command", "mvn -B test\nsecond line"), start));
        for (int i = 1; i <= 20; i++)
            events.add(runtime("baseline_progress", "Starting build is still running", start.plusSeconds(i)));
        events.add(runtime("baseline_progress", "Downloaded from central: dependency.jar", start.plusSeconds(21)));
        events.add(runtime("baseline_progress", "Downloaded from central: dependency.jar", start.plusSeconds(22)));
        events.add(runtime("baseline_progress", "Starting build is still running", start.plusSeconds(23)));
        events.add(runtime("baseline_completed", "Build passed", start.plusSeconds(24)));

        var activity = view.build(execution, List.of(), events, start.plusSeconds(25)).activity();

        assertThat(texts(activity.rows())).containsExactly("Built mvn -B test · took 24 s");
        // The time column shows only the clock; the full date and time go into the title attribute.
        assertThat(activity.rows().getFirst().time().text()).isEqualTo("10:00:00 UTC");
        assertThat(activity.rows().getFirst().time().title()).isEqualTo("2026-09-18 10:00:00 UTC");
        assertThat(activity.total()).isEqualTo(1);

        events.add(runtime("agent_start", "Pi started", start.plusSeconds(26)));
        var newest = view.build(execution, List.of(), events, start.plusSeconds(27)).activity().rows().getFirst();
        assertThat(newest.verb()).isNull();
        assertThat(newest.code()).isFalse();
        assertThat(newest.target()).isEqualTo("Coding agent started");
    }

    @Test void buildLogLinesAndTheCodingStartGetNoRows() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var events = List.of(
                runtime("baseline_progress", "Starting build is still running", start),
                event(AuditEventType.STEP_FAILED, "implement", start.plusSeconds(1)),
                runtime("baseline_progress", "Starting build is still running", start.plusSeconds(2)),
                runtime("tool_execution_start", "mvn test", start.plusSeconds(3)),
                runtime("baseline_progress", "Starting build is still running", start.plusSeconds(4)),
                runtime("baseline_progress", "[ERROR] Connection timed out", start.plusSeconds(5)),
                runtime("coding_starting", "Coding runtime is starting", start.plusSeconds(6)));
        assertThat(view.build(execution, List.of(), events, start.plusSeconds(7)).activity().rows()).hasSize(1);
    }

    @Test void aFailedStartingBuildSaysSoWithItsExitCode() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var events = List.of(runtime("baseline_started", "mvn test", start),
                runtime(Map.of("activity", "baseline_completed", "exitCode", 1, "summary", ""), start.plusSeconds(40)));
        var rows = view.build(execution, List.of(), events, start.plusSeconds(41)).activity().rows();
        assertThat(texts(rows)).containsExactly("Built the project · failed · exit 1");
        assertThat(rows.getFirst().tone()).isEqualTo(ActivityTone.FAILED);
    }

    @Test void rendersPiToolActivityWithoutRawBooleans() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var events = List.of(
                runtime(Map.of("activity", "tool_execution_start", "tool", "edit",
                        "path", "/workspace/src/KeycloakAuthorizationService.java"), start),
                runtime(Map.of("activity", "tool_execution_end", "tool", "edit", "error", false), start.plusSeconds(1)),
                runtime(Map.of("activity", "tool_execution_start", "tool", "write",
                        "path", "/workspace/src/AdvisoryLockService.java"), start.plusSeconds(2)),
                runtime(Map.of("activity", "tool_execution_end", "tool", "write", "error", false), start.plusSeconds(3)),
                runtime(Map.of("activity", "tool_execution_start", "tool", "read",
                        "path", "/workspace/src/Foo.java"), start.plusSeconds(4)),
                runtime(Map.of("activity", "tool_execution_start", "tool", "bash", "command", "mvn test"), start.plusSeconds(5)),
                runtime(Map.of("activity", "tool_execution_end", "tool", "bash", "error", false), start.plusSeconds(6)),
                runtime(Map.of("activity", "tool_execution_start", "tool", "bash", "command", "mvn test"), start.plusSeconds(7)),
                runtime(Map.of("activity", "tool_execution_end", "tool", "bash", "error", true), start.plusSeconds(8)));

        var rows = view.build(execution, List.of(), events, start.plusSeconds(9)).activity().rows();

        // One row per tool call, newest first; Pi reports no duration, so it comes from the event times.
        assertThat(texts(rows)).containsExactly(
                "Ran mvn test · failed",
                "Ran mvn test · took 1 s",
                "Read /workspace/src/Foo.java",
                "Wrote /workspace/src/AdvisoryLockService.java · took 1 s",
                "Edited /workspace/src/KeycloakAuthorizationService.java · took 1 s");
        assertThat(rows).extracting(row -> row.time().text()).containsExactly(
                "10:00:07 UTC", "10:00:05 UTC", "10:00:04 UTC", "10:00:02 UTC", "10:00:00 UTC");
        assertThat(rows).allMatch(ActivityRow::code);
        assertThat(String.join("\n", texts(rows))).doesNotContain("false", "true", "error=");
    }

    @Test void labelsClaudeCodeToolsWithTheirTargetResultAndDuration() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var events = List.of(
                runtime(Map.of("activity", "tool_execution_start", "tool", "Bash",
                        "command", "cat > Notes.java <<'EOF'\nHIDDEN_HEREDOC_SENTINEL\nEOF"), start),
                runtime(Map.of("activity", "tool_execution_start", "tool", "Read",
                        "path", "/workspace/src/Foo.java"), start.plusSeconds(1)),
                runtime(Map.of("activity", "tool_execution_end", "tool", "Read", "path", "/workspace/src/Foo.java",
                        "error", false, "durationMs", 15), start.plusSeconds(2)),
                runtime(Map.of("activity", "tool_execution_start", "tool", "Glob", "pattern", "**/*.java"),
                        start.plusSeconds(3)),
                runtime(Map.of("activity", "tool_execution_start", "tool", "Grep", "pattern", "TODO"),
                        start.plusSeconds(4)),
                runtime(Map.of("activity", "tool_execution_start", "tool", "WebFetch",
                        "url", "https://example.org/doc"), start.plusSeconds(5)),
                runtime(Map.of("activity", "tool_execution_start", "tool", "Edit",
                        "path", "/workspace/src/main/java/org/folio/integration/kong/KongGatewayService.java"),
                        start.plusSeconds(6)),
                runtime(Map.of("activity", "tool_execution_end", "tool", "Bash",
                        "command", "cat > Notes.java <<'EOF'\nHIDDEN_HEREDOC_SENTINEL\nEOF", "error", true,
                        "durationMs", 72_000, "errorText", "Exit code 1\nHIDDEN_STDERR_SENTINEL BUILD FAILURE"),
                        start.plusSeconds(7)));

        var rows = view.build(execution, List.of(), events, start.plusSeconds(8)).activity().rows();

        // Calls that overlap still pair up by their file or command; a command shows only its first line.
        assertThat(texts(rows)).containsExactly(
                "Edited …/integration/kong/KongGatewayService.java",
                "Fetched example.org/doc",
                "Searched TODO",
                "Found **/*.java",
                "Read /workspace/src/Foo.java · took 15 ms",
                "Ran cat > Notes.java <<'EOF' · failed · exit 1");
        assertThat(rows.toString()).doesNotContain("HIDDEN_HEREDOC_SENTINEL", "HIDDEN_STDERR_SENTINEL", "BUILD FAILURE");
    }

    @Test void aCallThatHasNotEndedRunsNowWhileTheRunWorks() {
        var execution = new PipelineExecution("dev-factory", "0.9.0", "{}");
        execution.setStatus(ExecutionStatus.RUNNING);
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var events = List.of(
                runtime(Map.of("activity", "tool_execution_start", "tool", "Read", "path", "Foo.java"), start),
                runtime(Map.of("activity", "tool_execution_end", "tool", "Read", "path", "Foo.java",
                        "durationMs", 9), start.plusSeconds(1)),
                runtime(Map.of("activity", "tool_execution_start", "tool", "Bash",
                        "command", "mvn -o test -Dtest=KongGatewayServiceTest"), start.plusSeconds(2)));

        var rows = view.build(execution, List.of(), events, start.plusSeconds(3)).activity().rows();

        assertThat(texts(rows)).containsExactly("Running mvn -o test -Dtest=KongGatewayServiceTest · running now",
                "Read Foo.java · took 9 ms");
        assertThat(rows).extracting(ActivityRow::tone).containsExactly(ActivityTone.LIVE, ActivityTone.NORMAL);
    }

    @Test void parallelBashCompletionsUseNeutralLabelsInsteadOfGuessing() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var events = List.of(
                runtime(Map.of("activity", "tool_execution_start", "tool", "bash", "command", "mvn test"), start),
                runtime(Map.of("activity", "tool_execution_start", "tool", "bash", "command", "npm test"), start.plusSeconds(1)),
                runtime(Map.of("activity", "tool_execution_end", "tool", "bash", "error", true), start.plusSeconds(2)),
                runtime(Map.of("activity", "tool_execution_start", "tool", "bash", "command", "go test"), start.plusSeconds(3)),
                runtime(Map.of("activity", "tool_execution_end", "tool", "bash", "error", false), start.plusSeconds(4)),
                runtime(Map.of("activity", "tool_execution_end", "tool", "bash", "error", false), start.plusSeconds(5)));

        var rows = view.build(execution, List.of(), events, start.plusSeconds(6)).activity().rows();

        assertThat(texts(rows)).containsExactly(
                "Ran shell command",
                "Ran shell command",
                "Ran go test",
                "Ran shell command · failed",
                "Ran npm test",
                "Ran mvn test");
    }

    @Test void rendersConservativePiLifecycleLabelsWithoutMessageOrReasoningContent() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var events = List.of(
                runtime(Map.of("activity", "agent_start"), start),
                runtime(Map.of("activity", "turn_start", "message", "hidden message",
                        "summary", "hidden reasoning"), start.plusSeconds(1)),
                runtime(Map.of("activity", "auto_retry_start"), start.plusSeconds(2)),
                runtime(Map.of("activity", "auto_retry_end"), start.plusSeconds(3)),
                runtime(Map.of("activity", "compaction_start"), start.plusSeconds(4)),
                runtime(Map.of("activity", "compaction_end"), start.plusSeconds(5)),
                runtime(Map.of("activity", "agent_end"), start.plusSeconds(6)));

        var rows = view.build(execution, List.of(), events, start.plusSeconds(7)).activity().rows();

        assertThat(texts(rows)).containsExactly(
                "Coding agent finished",
                "Coding runtime compaction finished",
                "Coding runtime compacting context",
                "Coding runtime retry finished",
                "Coding runtime retrying request",
                "Coding agent started");
        assertThat(rows.toString()).doesNotContain("hidden message", "hidden reasoning", "reasoning");
    }

    @Test void activityKeepsItsNoticeAndTheNewestRowsAndCountsThemAll() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(3);
        execution.setStatus(ExecutionStatus.RUNNING);
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var events = new java.util.ArrayList<AuditEvent>();
        events.add(runtime(Map.of("activity", "tool_execution_start", "tool", "bash", "command", "mvn test",
                "notice", "Runtime activity limit reached; usage reporting continues"), start));
        for (int i = 1; i <= 24; i++)
            events.add(runtime(Map.of("activity", "tool_execution_start", "tool", "read",
                    "path", "Foo" + i + ".java"), start.plusSeconds(i)));

        var facts = view.build(execution, List.of(), events, start.plusSeconds(25));

        assertThat(facts.activity().notice()).isEqualTo("Runtime activity limit reached; usage reporting continues");
        assertThat(facts.steps().current()).isEqualTo("Implement changes");
        assertThat(facts.activity().total()).isEqualTo(25);
        assertThat(facts.activity().earlier()).isEqualTo(5);
        assertThat(texts(facts.activity().rows()).getFirst()).isEqualTo("Reading Foo24.java · running now");
        assertThat(texts(facts.activity().rows()).getLast()).isEqualTo("Running mvn test · running now");

        for (int i = 25; i <= 204; i++)
            events.add(runtime(Map.of("activity", "agent_start"), start.plusSeconds(i)));
        var capped = view.build(execution, List.of(), events, start.plusSeconds(205)).activity();
        assertThat(capped.rows()).hasSize(DeveloperRuntimeActivity.KEPT_ROWS);
        assertThat(capped.total()).isEqualTo(205);
    }

    @Test void terminalExecutionKeepsPiEventHistoricalAndUsesProductStage() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(6);
        execution.setStatus(ExecutionStatus.COMPLETED);
        var result = artifact("dev_result.json", 1, "{\"state\":\"DELIVERED\"}");
        var latest = runtime(Map.of("activity", "tool_execution_start", "tool", "edit",
                "path", "/workspace/Foo.java"), Instant.parse("2026-09-18T10:00:00Z"));

        var facts = view.build(execution, List.of(result), List.of(latest), Instant.now());

        assertThat(facts.steps().current()).isNull();
        assertThat(texts(facts.activity().rows())).containsExactly("Edited /workspace/Foo.java");
        assertThat(facts.steps().cards()).extracting(Step::stateLabel)
                .containsExactly("Passed", "Passed", "Passed", "Passed");
    }

    @Test void verificationFailureIsMoreUsefulThanSkippedDelivery() {
        var execution = new PipelineExecution("dev-factory", "1", "{}");
        execution.setStatus(ExecutionStatus.COMPLETED);
        var verification = artifact("dev_verification.json", 1,
                "{\"result\":\"FAIL\",\"exitCode\":1,\"testCount\":8,\"failureCount\":2,\"errorCount\":0}");
        var delivery = artifact("dev_delivery.json", 1,
                "{\"state\":\"NOT_RUN\",\"reason\":\"Candidate was not independently verified\"}");
        assertThat(view.build(execution, List.of(verification, delivery), List.of(), Instant.now()).reason())
                .isEqualTo("Independent verification failed: exit 1, tests 8, failures 2, errors 0");
    }

    @Test void latestArtifactsKeepFailureVisibleAfterPipelineCompletion() {
        var execution = new PipelineExecution("dev-factory", "1", "{\"issueKey\":\"TASK-1\"}");
        execution.setStatus(ExecutionStatus.COMPLETED);
        var old = artifact("dev_result.json", 1, "{\"state\":\"VERIFIED\"}");
        var failed = artifact("dev_result.json", 2, "{\"state\":\"DELIVERY_BLOCKED\",\"reason\":\"Destination unavailable\"}");
        var facts = view.build(execution, List.of(failed, old), List.of(), Instant.now());
        assertThat(facts.reason()).isEqualTo("Destination unavailable");
        assertThat(facts.task().key()).isEqualTo("TASK-1");
        assertThat(facts.productStatus()).isEqualTo("DELIVERY_BLOCKED");
    }

    @Test void mapsPinnedBriefChecksAndDeliveryAndRejectsUnsafeLinks() {
        var execution = new PipelineExecution("dev-factory", "1", "{}");
        var brief = artifact("dev_task_brief.md", 1, "---\nissue_key: TASK-1\nissue:\n  summary: Fix route\nrepository:\n  source_repo: folio/sidecar\n  base_branch: master\n  base_sha: abc123\n---\n");
        var verification = artifact("dev_verification.json", 1,
                "{\"result\":\"PASS\",\"exitCode\":0,\"testCount\":8,\"planId\":\"maven-test\",\"argv\":[\"mvn\",\"test\"]}");
        var delivery = artifact("dev_delivery.json", 1,
                "{\"state\":\"DELIVERED\",\"deliveryRepository\":\"user/repo\",\"deliveryBranch\":\"dev/task\",\"deliveryCommitSha\":\"def456\",\"pullRequestUrl\":\"https://github.com/user/repo/pull/1\"}");
        var facts = view.build(execution, List.of(brief, verification, delivery), List.of(), Instant.now());
        assertThat(facts.task()).isEqualTo(new RunFacts.Task("TASK-1", "Fix route", "folio/sidecar", "master"));
        assertThat(facts.pullRequest()).isEqualTo(new RunFacts.PullRequest("https://github.com/user/repo/pull/1", false));
        assertThat(facts.technical()).isEqualTo(new DeveloperRunView.Technical("folio/sidecar@master", "abc123",
                null, null, null, "maven-test", "mvn test", "0", "user/repo", "dev/task", "def456"));

        var draft = artifact("dev_delivery.json", 2, "{\"pullRequestUrl\":\"https://github.com/user/repo/pull/1\","
                + "\"pullRequestDraft\":true,\"draftReasons\":[\"1 acceptance criterion is not met.\"]}");
        assertThat(view.build(execution, List.of(delivery, draft), List.of(), Instant.now()).pullRequest().draft())
                .isTrue();
        var unsafe = artifact("dev_delivery.json", 2, "{\"pullRequestUrl\":\"javascript:alert(1)\"}");
        assertThat(view.build(execution, List.of(delivery, unsafe), List.of(), Instant.now()).pullRequest().url())
                .isEmpty();
        var unsafeDraft = artifact("dev_delivery.json", 2, "{\"pullRequestUrl\":\"javascript:alert(1)\",\"pullRequestDraft\":true}");
        assertThat(view.build(execution, List.of(delivery, unsafeDraft), List.of(), Instant.now()).pullRequest().draft())
                .isFalse();
    }

    @Test void technicalDetailsShowTheChecksOnlyWhenTheyRanAndTheDeliveryRepositoryOnlyWhenItDiffers() {
        var execution = new PipelineExecution("dev-factory", "1", "{}");
        var brief = artifact("dev_task_brief.md", 1,
                "---\nrepository:\n  source_repo: folio/sidecar\n  base_branch: master\n---\n");
        var notRun = artifact("dev_verification.json", 1, "{\"state\":\"NOT_RUN\",\"argv\":[\"mvn\",\"test\"]}");
        var sameRepository = artifact("dev_delivery.json", 1,
                "{\"state\":\"NOT_RUN\",\"deliveryRepository\":\"folio/sidecar\"}");

        var technical = view.build(execution, List.of(brief, notRun, sameRepository), List.of(), Instant.now())
                .technical();

        assertThat(technical.checksCommand()).isNull();
        assertThat(technical.checksExitCode()).isNull();
        assertThat(technical.deliveredRepository()).isNull();
        assertThat(technical.toString()).doesNotContain("NOT_RUN");
    }

    @Test void durationsUseCompactHumanUnits() {
        assertThat(UiFormat.duration(59)).isEqualTo("59 s");
        assertThat(UiFormat.duration(1_209)).isEqualTo("20 min");
        assertThat(UiFormat.duration(7_205)).isEqualTo("2 h");
        assertThat(UiFormat.duration(4_830)).isEqualTo("1 h 20 min");
        assertThat(UiFormat.milliseconds(9)).isEqualTo("9 ms");
    }

    @Test void derivesFourStepCardsAndCombinesPreparationTime() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setCurrentStepIndex(2);
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var events = List.of(
                event(AuditEventType.STEP_STARTED, "read-task", start),
                event(AuditEventType.STEP_COMPLETED, "read-task", start.plusMillis(1600)),
                event(AuditEventType.STEP_STARTED, "select-repository", start.plusSeconds(2)),
                event(AuditEventType.STEP_COMPLETED, "select-repository", start.plusMillis(4600)),
                event(AuditEventType.STEP_STARTED, "prepare-task", start.plusSeconds(5)));

        var steps = view.build(execution, List.of(), events, start.plusMillis(10500)).steps();

        assertThat(steps.cards()).extracting(Step::title)
                .containsExactly("1 · Prepare task", "2 · Implement changes", "3 · Verify changes",
                        "4 · Create pull request");
        assertThat(steps.cards()).extracting(Step::state).containsExactly(CURRENT, PENDING, PENDING, PENDING);
        assertThat(steps.cards()).extracting(Step::stateLabel)
                .containsExactly("Working", "Waiting", "Waiting", "Waiting");
        assertThat(steps.cards().getFirst().detail()).isEqualTo("9 s");
        assertThat(steps.cards().getFirst().elapsedStart()).isNotNull();
        assertThat(steps.current()).isEqualTo("Prepare task");
    }

    @Test void aRunThatStoppedOnAQuestionShowsItsImplementStepAsStoppedAndTheRestAsNotStarted() {
        var execution = new PipelineExecution("dev-factory", "0.9.0", "{}");
        execution.setCurrentStepIndex(10);
        execution.setStatus(ExecutionStatus.COMPLETED);
        var at = Instant.parse("2026-11-10T18:00:00Z");
        var events = List.of(
                event(AuditEventType.STEP_STARTED, "implement", at),
                event(AuditEventType.STEP_COMPLETED, "implement", at.plusSeconds(840)),
                event(AuditEventType.STEP_STARTED, "verify", at.plusSeconds(840)),
                event(AuditEventType.STEP_COMPLETED, "verify", at.plusSeconds(840)),
                event(AuditEventType.STEP_STARTED, "publish", at.plusSeconds(840)),
                event(AuditEventType.STEP_COMPLETED, "publish", at.plusSeconds(840)));
        var notRun = List.of(artifact("dev_verification.json", 1, "{\"state\":\"NOT_RUN\"}"),
                artifact("dev_delivery.json", 1, "{\"state\":\"NOT_RUN\"}"));

        var withoutQuestion = view.build(execution, notRun, events, Instant.now()).steps().cards();
        assertThat(withoutQuestion).extracting(Step::state).containsExactly(DONE, DONE, PENDING, PENDING);
        assertThat(withoutQuestion).extracting(Step::stateLabel)
                .containsExactly("Passed", "Passed", "Not started", "Not started");

        var asked = new java.util.ArrayList<>(notRun);
        asked.add(artifact("dev_coding_outcome.json", 1,
                "{\"status\":\"NEEDS_DECISION\",\"decision\":{\"question\":\"Which API?\"}}"));
        var cards = view.build(execution, asked, events, Instant.now()).steps().cards();
        assertThat(cards).extracting(Step::state).containsExactly(DONE, QUESTION, PENDING, PENDING);
        assertThat(cards.get(1).stateLabel()).isEqualTo("Stopped with a question");
        assertThat(cards.get(1).detail()).isEqualTo("14 min");
        assertThat(cards.get(1).tone()).isEqualTo("question");
    }

    @Test void domainOutcomesMarkTheOriginFailedAndLaterStepsPending() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(6);
        execution.setStatus(ExecutionStatus.COMPLETED);

        assertThat(states(view.build(execution, List.of(
                artifact("dev_readiness.json", 1, "{\"state\":\"BASELINE_FAILED\"}")), List.of(), Instant.now())))
                .containsExactly(DONE, FAILED, PENDING, PENDING);
        assertThat(states(view.build(execution, List.of(
                artifact("dev_verification.json", 1, "{\"result\":\"FAIL\"}"),
                artifact("dev_result.json", 1, "{\"state\":\"VERIFICATION_FAILED\"}")), List.of(), Instant.now())))
                .containsExactly(DONE, DONE, FAILED, PENDING);
        assertThat(states(view.build(execution, List.of(
                artifact("dev_delivery.json", 1, "{\"state\":\"DELIVERY_BLOCKED\"}")), List.of(), Instant.now())))
                .containsExactly(DONE, DONE, DONE, FAILED);
    }

    @Test void terminalFailureStopsAnOpenStepClock() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(3);
        execution.setStatus(ExecutionStatus.FAILED_ESCALATED);
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        ReflectionTestUtils.setField(execution, "completedAt", start.plusSeconds(10));
        var readiness = artifact("dev_readiness.json", 1,
                "{\"state\":\"BASELINE_PASSED\",\"command\":[\"mvn\",\"test\"],\"output\":\"ok\"}");

        var facts = view.build(execution, List.of(readiness),
                List.of(event(AuditEventType.STEP_STARTED, "implement", start)), start.plusSeconds(90));

        var implement = facts.steps().cards().get(1);
        assertThat(implement.state()).isEqualTo(FAILED);
        assertThat(implement.stateLabel()).isEqualTo("Failed");
        assertThat(implement.detail()).isEqualTo("10 s");
        assertThat(implement.elapsedStart()).isNull();
        assertThat(facts.steps().current()).isNull();
        assertThat(facts.technical().buildResult()).isEqualTo("Passed");
        assertThat(facts.technical().buildCommand()).isEqualTo("mvn test");
        // A build that passed needs no output on the page.
        assertThat(facts.technical().buildOutput()).isNull();
    }

    @Test void aFailedBuildShowsItsOutputAndACutOutputStartsAtAWholeLine() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        var partial = "g.junit.platform.commons.Exception\n";
        var output = (partial + "[ERROR] failed\n".repeat(MavenBaselineOutput.KEPT_TAIL / 15))
                .substring(0, MavenBaselineOutput.KEPT_TAIL);
        var cut = artifact("dev_readiness.json", 1, JsonMapper.builder().build().writeValueAsString(
                Map.of("state", "BASELINE_FAILED", "output", output)));
        var whole = artifact("dev_readiness.json", 1,
                "{\"state\":\"BASELINE_FAILED\",\"output\":\"first line\\n[ERROR] failed\"}");

        assertThat(view.build(execution, List.of(cut), List.of(), Instant.now()).technical().buildOutput())
                .isEqualTo(output.substring(partial.length())).startsWith("[ERROR] failed\n");
        assertThat(view.build(execution, List.of(whole), List.of(), Instant.now()).technical().buildOutput())
                .isEqualTo("first line\n[ERROR] failed");
    }

    @Test void pendingRetryPreservesCompletedStepsAndCurrentCursor() {
        var fresh = new PipelineExecution("dev-factory", "0.6.0", "{}");
        assertThat(states(view.build(fresh, List.of(), List.of(), Instant.now())))
                .containsExactly(PENDING, PENDING, PENDING, PENDING);

        var retry = new PipelineExecution("dev-factory", "0.6.0", "{}");
        retry.setCurrentStepIndex(4);
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var events = List.of(
                event(AuditEventType.STEP_STARTED, "verify", start),
                event(AuditEventType.STEP_FAILED, "verify", start.plusSeconds(7)));

        var steps = view.build(retry, List.of(), events, start.plusSeconds(20)).steps();

        assertThat(steps.cards()).extracting(Step::state).containsExactly(DONE, DONE, CURRENT, PENDING);
        assertThat(steps.cards().get(2).stateLabel()).isEqualTo("Retry pending");
        assertThat(steps.cards().get(2).detail()).isEqualTo("7 s");
        assertThat(steps.cards().get(2).elapsedStart()).isNull();
        assertThat(steps.current()).isEqualTo("Verify changes");
    }

    @Test void resumedExecutionIgnoresStaleTerminalTimestamp() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        ReflectionTestUtils.setField(execution, "createdAt", start);
        execution.setCurrentStepIndex(3);
        execution.setStatus(ExecutionStatus.FAILED_ESCALATED);
        ReflectionTestUtils.setField(execution, "completedAt", start.plusSeconds(10));
        execution.setStatus(ExecutionStatus.RUNNING);
        var events = List.of(
                event(AuditEventType.STEP_STARTED, "implement", start),
                event(AuditEventType.STEP_FAILED, "implement", start.plusSeconds(10)),
                event(AuditEventType.STEP_STARTED, "implement", start.plusSeconds(20)));

        var implement = view.build(execution, List.of(), events, start.plusSeconds(50)).steps().cards().get(1);

        assertThat(implement.state()).isEqualTo(CURRENT);
        assertThat(implement.detail()).isEqualTo("40 s");
    }

    /** The run list reads each latest artifact on its own; the run page must show the same status. */
    private String productStatus(PipelineExecution execution, List<Artifact> artifacts) {
        var row = view.listFacts(execution, DeveloperExecutionView.latest(artifacts));
        var facts = view.build(execution, artifacts, List.of(), Instant.now());
        // The run list row and the run page read the same status, task key and pull request.
        assertThat(facts.productStatus()).isEqualTo(row.productStatus());
        assertThat(facts.task().key()).isEqualTo(row.taskKey());
        assertThat(facts.pullRequest()).isEqualTo(row.pullRequest());
        return row.productStatus();
    }

    private static List<StepState> states(RunFacts facts) {
        return facts.steps().cards().stream().map(Step::state).toList();
    }

    private static List<String> texts(RunFacts facts) {
        return texts(facts.activity().rows());
    }

    /** "Ran mvn test · took 1 s": verb, target and result as the row shows them. */
    private static List<String> texts(List<ActivityRow> rows) {
        return rows.stream().map(row -> (row.verb() == null ? "" : row.verb() + " ") + row.target()
                + (row.result() == null ? "" : " · " + row.result())).toList();
    }

    private static Artifact artifact(String name, int version, String content) {
        return new Artifact(java.util.UUID.randomUUID(), name, version, "text/markdown", content, "hash", "worker");
    }
    private static AuditEvent event(AuditEventType type, String stepId, Instant when) {
        var event = new AuditEvent(java.util.UUID.randomUUID(), type, stepId, "system", "{}");
        ReflectionTestUtils.setField(event, "occurredAt", when);
        return event;
    }
    private static AuditEvent runtime(String activity, String message, Instant when) {
        var event = new AuditEvent(java.util.UUID.randomUUID(), AuditEventType.RUNTIME_PROGRESS, "implement", "system",
                JsonMapper.builder().build().writeValueAsString(Map.of("activity", activity, "message", message)));
        ReflectionTestUtils.setField(event, "occurredAt", when);
        return event;
    }
    private static AuditEvent runtime(Map<String, ?> detail, Instant when) {
        var event = new AuditEvent(java.util.UUID.randomUUID(), AuditEventType.RUNTIME_PROGRESS, "implement", "system",
                JsonMapper.builder().build().writeValueAsString(detail));
        ReflectionTestUtils.setField(event, "occurredAt", when);
        return event;
    }
}
