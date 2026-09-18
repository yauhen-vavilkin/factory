package org.folio.factory.app.web;

import org.folio.factory.core.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DeveloperExecutionViewTest {
    private final DeveloperExecutionView view = new DeveloperExecutionView(JsonMapper.builder().build());

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
        assertThat(model.get("fields").toString()).contains("TASK-1", "DELIVERY_BLOCKED", "Not started");
    }

    @Test void mapsPinnedBriefVerificationAndDeliveryAndRejectsUnsafeLinks() {
        var execution = new PipelineExecution("dev-factory", "1", "{}");
        var brief = artifact("dev_task_brief.md", 1, "---\nissue_key: TASK-1\nissue:\n  summary: Fix route\nrepository:\n  source_repo: folio/sidecar\n  base_branch: master\n  base_sha: abc123\n---\n");
        var verification = artifact("dev_verification.json", 1,
                "{\"result\":\"PASS\",\"exitCode\":0,\"testCount\":8,\"argv\":[\"mvn\",\"test\"]}");
        var delivery = artifact("dev_delivery.json", 1,
                "{\"state\":\"DELIVERED\",\"deliveryRepository\":\"user/repo\",\"deliveryBranch\":\"dev/task\",\"deliveryCommitSha\":\"def456\",\"pullRequestUrl\":\"https://github.com/user/repo/pull/1\"}");
        var model = view.build(execution, List.of(brief, verification, delivery), List.of(), Instant.now());
        assertThat(model.get("fields").toString()).contains("Fix route", "folio/sidecar", "master", "abc123", "PASS", "mvn test", "8", "user/repo", "dev/task", "def456");
        assertThat(model).containsEntry("pullRequestUrl", "https://github.com/user/repo/pull/1");
        var unsafe = artifact("dev_delivery.json", 2, "{\"pullRequestUrl\":\"javascript:alert(1)\"}");
        assertThat(view.build(execution, List.of(delivery, unsafe), List.of(), Instant.now())).containsEntry("pullRequestUrl", "");
    }

    @Test void stageDurationsUseAttemptBoundariesAndTerminalTime() {
        var execution = new PipelineExecution("dev-factory", "1", "{}");
        Instant start = Instant.parse("2026-09-18T10:00:00Z");
        var began = event(AuditEventType.STEP_STARTED, start);
        var ended = event(AuditEventType.STEP_COMPLETED, start.plusSeconds(12));
        assertThat(DeveloperExecutionView.stageDuration("develop", List.of(began, ended), execution, start.plusSeconds(90))).isEqualTo("12s");
        assertThat(DeveloperExecutionView.stageDuration("develop", List.of(began), execution, start.plusSeconds(30))).isEqualTo("30s");
        assertThat(DeveloperExecutionView.stageDuration("verify", List.of(began), execution, start.plusSeconds(30))).isEqualTo("Not started");
    }
    private static Artifact artifact(String name, int version, String content) {
        return new Artifact(java.util.UUID.randomUUID(), name, version, "text/markdown", content, "hash", "worker");
    }
    private static AuditEvent event(AuditEventType type, Instant when) {
        var event = new AuditEvent(java.util.UUID.randomUUID(), type, "develop", "system", "{}");
        ReflectionTestUtils.setField(event, "occurredAt", when);
        return event;
    }
}
