package org.folio.factory.app.web;

import org.folio.factory.core.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DeveloperExecutionViewTest {
    private final DeveloperExecutionView view = new DeveloperExecutionView(JsonMapper.builder().build());

    @Test void productStatusUsesLatestOutcomeAndDoesNotTreatSkippedDeliveryAsSuccess() {
        var execution = new PipelineExecution("dev-factory", "1", "{}");
        execution.setStatus(ExecutionStatus.COMPLETED);
        for (String state : List.of("DEVELOPMENT_FAILED", "VERIFICATION_FAILED", "DELIVERY_BLOCKED",
                "UNSUPPORTED", "BLOCKED", "BLOCKED_ENVIRONMENT", "DELIVERED", "VERIFIED")) {
            assertThat(view.productStatus(execution, List.of(
                    artifact("dev_result.json", 2, "{\"state\":\"" + state + "\"}"),
                    artifact("dev_result.json", 1, "{\"state\":\"VERIFIED\"}"),
                    artifact("dev_delivery.json", 1, "{\"state\":\"NOT_RUN\"}")))).isEqualTo(state);
        }
        assertThat(view.productStatus(execution, List.of())).isEqualTo("OUTCOME_UNAVAILABLE");
        assertThat(view.productStatus(execution, List.of(artifact("dev_result.json", 1, "not json"))))
                .isEqualTo("OUTCOME_UNAVAILABLE");
        assertThat(view.productStatus(execution, List.of(
                artifact("dev_result.json", 1, "{\"state\":\"secret message\"}"))))
                .isEqualTo("OUTCOME_UNAVAILABLE");
        execution.setStatus(ExecutionStatus.RUNNING);
        assertThat(view.productStatus(execution, List.of(artifact("dev_result.json", 1,
                "{\"state\":\"DEVELOPMENT_FAILED\"}")))).isEqualTo("RUNNING");
        execution.setStatus(ExecutionStatus.FAILED_ESCALATED);
        assertThat(view.productStatus(execution, List.of(artifact("dev_result.json", 1,
                "{\"state\":\"VERIFIED\"}")))).isEqualTo("FAILED_ESCALATED");
    }

    @Test void productStatusUsesDeliveryAndFailureEvidenceWhenFinalResultIsMissing() {
        var execution = new PipelineExecution("dev-factory", "1", "{}");
        execution.setStatus(ExecutionStatus.COMPLETED);
        assertThat(view.productStatus(execution, List.of(
                artifact("dev_result.json", 1, "{\"state\":\"VERIFIED\"}"),
                artifact("dev_delivery.json", 1, "{\"state\":\"DELIVERY_BLOCKED\"}"))))
                .isEqualTo("DELIVERY_BLOCKED");
        assertThat(view.productStatus(execution, List.of(
                artifact("dev_verification.json", 1, "{\"result\":\"FAIL\"}"))))
                .isEqualTo("VERIFICATION_FAILED");
        assertThat(view.productStatus(execution, List.of(
                artifact("dev_candidate.json", 1, "{\"state\":\"DEVELOPMENT_FAILED\"}"))))
                .isEqualTo("DEVELOPMENT_FAILED");
    }

    @Test void needsDecisionIsVisibleWhilePausedAndAfterTheSingleContinuation() {
        var execution = new PipelineExecution("dev-factory", "0.7.0", "{}");
        execution.setCurrentStepIndex(4);
        execution.setStatus(ExecutionStatus.AWAITING_HITL);
        var outcome = artifact("dev_coding_outcome.json", 1,
                "{\"status\":\"NEEDS_DECISION\",\"decision\":{\"question\":\"Which API?\"}}");

        assertThat(view.productStatus(execution, List.of(outcome))).isEqualTo("NEEDS_DECISION");
        var paused = view.build(execution, List.of(outcome), List.of(), Instant.now());
        assertThat(paused).containsEntry("currentPhase", "Implement changes")
                .containsEntry("reasonLabel", "Decision required")
                .containsEntry("reason", "Which API?");

        execution.setCurrentStepIndex(8);
        execution.setStatus(ExecutionStatus.COMPLETED);
        assertThat(view.productStatus(execution, List.of(outcome))).isEqualTo("NEEDS_DECISION");
        assertThat(phases(view.build(execution, List.of(outcome), List.of(), Instant.now())))
                .extracting(phase -> phase.get("state")).containsExactly("done", "done", "done", "done");

        execution.setStatus(ExecutionStatus.REJECTED);
        assertThat(view.productStatus(execution, List.of(outcome))).isEqualTo("REJECTED");
    }

    @Test void pendingRetryAndExhaustedRetryHaveDistinctReasons() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(3);
        execution.setErrorMessage("Starting build hit a network/download failure");
        var pending = view.build(execution, List.of(), List.of(runtime("baseline_retryable_failure",
                "Starting build hit a network/download failure", Instant.now())), Instant.now());
        assertThat(pending).containsEntry("reasonLabel", "Retry pending")
                .containsEntry("reason", "Starting build hit a network/download failure");
        assertThat(pending.get("activity").toString())
                .contains("Starting build network failure · Starting build hit a network/download failure");
        execution.setStatus(ExecutionStatus.FAILED_ESCALATED);
        assertThat(view.build(execution, List.of(), List.of(), Instant.now()))
                .containsEntry("reasonLabel", "Stop reason");
    }

    @Test void supersededRetryErrorDisappearsAfterStartingBuildRecovery() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(3);
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setErrorMessage("Starting build hit a network/download failure; coding has not started.");

        assertThat(view.build(execution, List.of(), List.of(), Instant.now()))
                .containsEntry("reasonLabel", "Previous attempt")
                .containsEntry("reason", execution.getErrorMessage());

        var runtimeRunning = view.build(execution, List.of(),
                List.of(runtime("coding_starting", "Coding runtime is starting", Instant.now())), Instant.now());
        assertThat(runtimeRunning).containsEntry("reason", "");

        execution.setCurrentStepIndex(6);
        execution.setStatus(ExecutionStatus.COMPLETED);
        assertThat(view.build(execution, List.of(), List.of(), Instant.now()))
                .containsEntry("reason", "");
    }

    @Test void compactsOnlyConsecutiveIdenticalHeartbeatsAndKeepsPiStartHonest() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var events = new java.util.ArrayList<AuditEvent>();
        events.add(runtime("baseline_started", "mvn test", start));
        for (int i = 1; i <= 20; i++)
            events.add(runtime("baseline_progress", "Starting build is still running", start.plusSeconds(i)));
        events.add(runtime("baseline_progress", "Downloaded from central: dependency.jar", start.plusSeconds(21)));
        events.add(runtime("baseline_progress", "Downloaded from central: dependency.jar", start.plusSeconds(22)));
        events.add(runtime("baseline_progress", "Starting build is still running", start.plusSeconds(23)));
        events.add(runtime("baseline_completed", "Build passed", start.plusSeconds(24)));

        var model = view.build(execution, List.of(), events, start.plusSeconds(25));

        @SuppressWarnings("unchecked")
        var activity = (List<Map<String, String>>) model.get("activity");
        assertThat(activity).extracting(row -> row.get("text")).containsExactly(
                "Starting build started · mvn test",
                "Starting build progress · Starting build is still running",
                "Starting build progress · Downloaded from central: dependency.jar",
                "Starting build progress · Downloaded from central: dependency.jar",
                "Starting build progress · Starting build is still running",
                "Starting build completed · Build passed");
        assertThat(activity.get(1)).containsEntry("time", "2026-09-18 10:00:20");
        assertThat(events).hasSize(25);
        assertThat(model.get("latestActivity").toString()).contains("Starting build completed");
        assertThat(DeveloperExecutionView.technicalStepLabel("implement")).isEqualTo("Implementation");

        events.add(runtime("agent_start", "Pi started", start.plusSeconds(26)));
        assertThat(view.build(execution, List.of(), events, start.plusSeconds(27)).get("latestActivity").toString())
                .contains("Coding agent started");
    }

    @Test void hiddenAuditEventsDoNotSplitHeartbeatsButMeaningfulRuntimeEventsDo() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var events = List.of(
                runtime("baseline_progress", "Starting build is still running", start),
                event(AuditEventType.STEP_FAILED, "implement", start.plusSeconds(1)),
                runtime("baseline_progress", "Starting build is still running", start.plusSeconds(2)),
                runtime("tool_execution_start", "mvn test", start.plusSeconds(3)),
                runtime("baseline_progress", "Starting build is still running", start.plusSeconds(4)),
                runtime("baseline_progress", "[ERROR] Connection timed out", start.plusSeconds(5)));
        assertThat((List<?>) view.build(execution, List.of(), events, start.plusSeconds(6)).get("activity"))
                .hasSize(4);
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

        @SuppressWarnings("unchecked")
        var activity = (List<Map<String, String>>) view.build(execution, List.of(), events,
                start.plusSeconds(9)).get("activity");

        assertThat(activity).extracting(row -> row.get("text")).containsExactly(
                "write completed",
                "Reading Foo.java",
                "Running mvn test",
                "mvn test completed",
                "Running mvn test",
                "mvn test failed");
        assertThat(activity.toString()).doesNotContain("false", "true", "error=");
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

        @SuppressWarnings("unchecked")
        var activity = (List<Map<String, String>>) view.build(execution, List.of(), events,
                start.plusSeconds(6)).get("activity");

        assertThat(activity).extracting(row -> row.get("text")).containsExactly(
                "Running mvn test",
                "Running npm test",
                "Shell command failed",
                "Running go test",
                "Shell command completed",
                "Shell command completed");
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

        @SuppressWarnings("unchecked")
        var activity = (List<Map<String, String>>) view.build(execution, List.of(), events,
                start.plusSeconds(7)).get("activity");

        assertThat(activity).extracting(row -> row.get("text")).containsExactly(
                "Coding agent started",
                "Coding runtime retrying request",
                "Coding runtime retry finished",
                "Coding runtime compacting context",
                "Coding runtime compaction finished",
                "Coding agent finished");
        assertThat(activity.toString()).doesNotContain("hidden message", "hidden reasoning", "reasoning");
    }

    @Test void activityLimitNoticeSurvivesRecentActivityWindow() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(3);
        execution.setStatus(ExecutionStatus.RUNNING);
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var events = new java.util.ArrayList<AuditEvent>();
        events.add(runtime(Map.of("activity", "tool_execution_start", "tool", "bash", "command", "mvn test",
                "notice", "Runtime activity limit reached; usage reporting continues"), start));
        for (int i = 1; i <= 8; i++)
            events.add(runtime(Map.of("activity", "tool_execution_start", "tool", "read",
                    "path", "/workspace/Foo" + i + ".java"), start.plusSeconds(i)));

        var model = view.build(execution, List.of(), events, start.plusSeconds(9));

        assertThat(model).containsEntry("activityNotice", "Runtime activity limit reached; usage reporting continues")
                .containsEntry("currentPhase", "Implement changes");
        assertThat((List<?>) model.get("activity")).hasSize(6);
        assertThat(model.get("latestActivity").toString()).contains("Reading Foo8.java");
    }

    @Test void terminalExecutionKeepsPiEventHistoricalAndUsesProductStage() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(6);
        execution.setStatus(ExecutionStatus.COMPLETED);
        var result = artifact("dev_result.json", 1, "{\"state\":\"DELIVERED\"}");
        var latest = runtime(Map.of("activity", "tool_execution_start", "tool", "edit",
                "path", "/workspace/Foo.java"), Instant.parse("2026-09-18T10:00:00Z"));

        var model = view.build(execution, List.of(result), List.of(latest), Instant.now());

        assertThat(model).containsEntry("currentPhase", "Complete");
        assertThat(model.get("latestActivity").toString()).contains("Editing Foo.java");
        assertThat(phases(model)).extracting(phase -> phase.get("stateLabel"))
                .containsExactly("Done", "Done", "Done", "Done");
    }

    @Test void verificationFailureIsMoreUsefulThanSkippedDelivery() {
        var execution = new PipelineExecution("dev-factory", "1", "{}");
        execution.setStatus(ExecutionStatus.COMPLETED);
        var verification = artifact("dev_verification.json", 1,
                "{\"result\":\"FAIL\",\"exitCode\":1,\"testCount\":8,\"failureCount\":2,\"errorCount\":0}");
        var delivery = artifact("dev_delivery.json", 1,
                "{\"state\":\"NOT_RUN\",\"reason\":\"Candidate was not independently verified\"}");
        assertThat(view.build(execution, List.of(verification, delivery), List.of(), Instant.now()).get("reason"))
                .isEqualTo("Independent verification failed: exit 1, tests 8, failures 2, errors 0");
    }

    @Test void latestArtifactsKeepFailureVisibleAfterPipelineCompletion() {
        var execution = new PipelineExecution("dev-factory", "1", "{\"issueKey\":\"TASK-1\"}");
        execution.setStatus(ExecutionStatus.COMPLETED);
        var old = artifact("dev_result.json", 1, "{\"state\":\"VERIFIED\"}");
        var failed = artifact("dev_result.json", 2, "{\"state\":\"DELIVERY_BLOCKED\",\"reason\":\"Destination unavailable\"}");
        var model = view.build(execution, List.of(failed, old), List.of(), Instant.now());
        assertThat(model).containsEntry("reason", "Destination unavailable").containsEntry("elapsedStart", null);
        assertThat(model).containsEntry("taskKey", "TASK-1").containsEntry("outcome", "DELIVERY_BLOCKED");
    }

    @Test void mapsPinnedBriefVerificationAndDeliveryAndRejectsUnsafeLinks() {
        var execution = new PipelineExecution("dev-factory", "1", "{}");
        var brief = artifact("dev_task_brief.md", 1, "---\nissue_key: TASK-1\nissue:\n  summary: Fix route\nrepository:\n  source_repo: folio/sidecar\n  base_branch: master\n  base_sha: abc123\n---\n");
        var verification = artifact("dev_verification.json", 1,
                "{\"result\":\"PASS\",\"exitCode\":0,\"testCount\":8,\"argv\":[\"mvn\",\"test\"]}");
        var delivery = artifact("dev_delivery.json", 1,
                "{\"state\":\"DELIVERED\",\"deliveryRepository\":\"user/repo\",\"deliveryBranch\":\"dev/task\",\"deliveryCommitSha\":\"def456\",\"pullRequestUrl\":\"https://github.com/user/repo/pull/1\"}");
        var model = view.build(execution, List.of(brief, verification, delivery), List.of(), Instant.now());
        assertThat(model).containsEntry("summary", "Fix route")
                .containsEntry("repository", "folio/sidecar")
                .containsEntry("branch", "master")
                .containsEntry("verification", "PASS")
                .containsEntry("testCount", "8")
                .containsEntry("deliveryRepository", "user/repo")
                .containsEntry("deliveryBranch", "dev/task")
                .containsEntry("deliveryCommit", "def456")
                .containsEntry("pullRequestUrl", "https://github.com/user/repo/pull/1");
        assertThat(model.get("technicalFields").toString()).contains("Starting commit", "abc123", "mvn test");
        var unsafe = artifact("dev_delivery.json", 2, "{\"pullRequestUrl\":\"javascript:alert(1)\"}");
        assertThat(view.build(execution, List.of(delivery, unsafe), List.of(), Instant.now())).containsEntry("pullRequestUrl", "");
    }

    @Test void technicalStepDurationsUseAttemptBoundariesAndTerminalTime() {
        var execution = new PipelineExecution("dev-factory", "1", "{}");
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var began = event(AuditEventType.STEP_STARTED, "implement", start);
        var ended = event(AuditEventType.STEP_COMPLETED, "implement", start.plusSeconds(12));
        var executionEvent = event(AuditEventType.EXECUTION_STARTED, null, start);
        assertThat(DeveloperExecutionView.technicalStepDuration("implement", List.of(executionEvent, began, ended), execution, start.plusSeconds(90))).isEqualTo("12s");
        assertThat(DeveloperExecutionView.technicalStepDuration("implement", List.of(began), execution, start.plusSeconds(30))).isEqualTo("30s");
        assertThat(DeveloperExecutionView.technicalStepDuration("verify", List.of(began), execution, start.plusSeconds(30))).isEqualTo("Not started");
    }

    @Test void durationsUseCompactHumanUnits() {
        assertThat(UiFormat.duration(59)).isEqualTo("59s");
        assertThat(UiFormat.duration(1_209)).isEqualTo("20m 9s");
        assertThat(UiFormat.duration(7_205)).isEqualTo("2h");
    }

    @Test void derivesFourProductPhasesAndCombinesPreparationDuration() {
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

        var phases = phases(view.build(execution, List.of(), events, start.plusMillis(10500)));

        assertThat(phases).extracting(p -> p.get("label"))
                .containsExactly("Prepare task", "Implement changes", "Verify changes", "Create pull request");
        assertThat(phases).extracting(p -> p.get("state"))
                .containsExactly("current", "pending", "pending", "pending");
        assertThat(phases.getFirst()).containsEntry("sublabel", "9s").containsKey("elapsedStart");
    }

    @Test void domainOutcomesMarkTheOriginFailedAndLaterPhasesPending() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(6);
        execution.setStatus(ExecutionStatus.COMPLETED);

        var startingBuild = phases(view.build(execution, List.of(
                artifact("dev_readiness.json", 1, "{\"state\":\"BASELINE_FAILED\"}")), List.of(), Instant.now()));
        assertThat(startingBuild).extracting(p -> p.get("state"))
                .containsExactly("done", "failed", "pending", "pending");

        var verification = phases(view.build(execution, List.of(
                artifact("dev_verification.json", 1, "{\"result\":\"FAIL\"}"),
                artifact("dev_result.json", 1, "{\"state\":\"VERIFICATION_FAILED\"}")), List.of(), Instant.now()));
        assertThat(verification).extracting(p -> p.get("state"))
                .containsExactly("done", "done", "failed", "pending");

        var delivery = phases(view.build(execution, List.of(
                artifact("dev_delivery.json", 1, "{\"state\":\"DELIVERY_BLOCKED\"}")), List.of(), Instant.now()));
        assertThat(delivery).extracting(p -> p.get("state"))
                .containsExactly("done", "done", "done", "failed");
    }

    @Test void terminalFailureStopsAnOpenPhaseClockAndUsesProductTerminology() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(3);
        execution.setStatus(ExecutionStatus.FAILED_ESCALATED);
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        ReflectionTestUtils.setField(execution, "completedAt", start.plusSeconds(10));
        var readiness = artifact("dev_readiness.json", 1,
                "{\"state\":\"BASELINE_PASSED\",\"command\":[\"mvn\",\"test\"],\"output\":\"ok\"}");

        var model = view.build(execution, List.of(readiness),
                List.of(event(AuditEventType.STEP_STARTED, "implement", start)), start.plusSeconds(90));

        assertThat(phases(model).get(1)).containsEntry("state", "failed").containsEntry("sublabel", "10s");
        assertThat(model).containsEntry("startingBuild", "Passed")
                .containsEntry("currentPhase", "Implement changes")
                .containsEntry("startingBuildOutput", "ok");
        assertThat(model.get("technicalFields").toString()).doesNotContain("Pinned base SHA", "Baseline command");
    }

    @Test void pendingRetryPreservesCompletedPhasesAndCurrentCursor() {
        var fresh = new PipelineExecution("dev-factory", "0.6.0", "{}");
        assertThat(phases(view.build(fresh, List.of(), List.of(), Instant.now())))
                .extracting(p -> p.get("state"))
                .containsExactly("pending", "pending", "pending", "pending");

        var retry = new PipelineExecution("dev-factory", "0.6.0", "{}");
        retry.setCurrentStepIndex(4);
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var events = List.of(
                event(AuditEventType.STEP_STARTED, "verify", start),
                event(AuditEventType.STEP_FAILED, "verify", start.plusSeconds(7)));

        var model = view.build(retry, List.of(), events, start.plusSeconds(20));

        assertThat(phases(model)).extracting(p -> p.get("state"))
                .containsExactly("done", "done", "current", "pending");
        assertThat(phases(model).get(2)).containsEntry("stateLabel", "Retry pending")
                .containsEntry("sublabel", "7s")
                .doesNotContainKey("elapsedStart");
        assertThat(model).containsEntry("currentPhase", "Verify changes");
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

        var model = view.build(execution, List.of(), events, start.plusSeconds(50));

        assertThat(phases(model).get(1)).containsEntry("state", "current").containsEntry("sublabel", "40s");
        assertThat(model).containsEntry("elapsed", "50s");
    }
    private static Artifact artifact(String name, int version, String content) {
        return new Artifact(java.util.UUID.randomUUID(), name, version, "text/markdown", content, "hash", "worker");
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> phases(Map<String, Object> model) {
        return (List<Map<String, Object>>) model.get("phases");
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
