package org.folio.factory.devfactory.pi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.nio.file.Path;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxSpec;
import org.folio.factory.devfactory.worker.recovery.RecoveryBundleStore;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class PiWorkerFinalizationTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void successFinalizationContainsOutcomeUsageCleanupAndSessionReferences() {
    AgentResult result = finalizeWorker("PASS", "{\"status\":\"PASS\"}", "candidate.patch", "usage.json",
        "pi-session.jsonl");
    var node = json.readTree(result.outputs().get("result.json"));
    assertThat(node.path("outcome").asString()).isEqualTo("SUCCESS");
    assertThat(node.path("usage").isObject()).isTrue();
    assertThat(node.path("cleanup").path("status").asString()).isEqualTo("COMPLETE");
    assertThat(node.path("session").path("ref").asString()).isEqualTo("pi-session.jsonl");
  }

  @Test
  void failureFinalizationContainsFailureReasonAndStage() {
    AgentResult result = finalizeWorker("FAIL", "{\"reason\":\"TIMEOUT\",\"stage\":\"coding\"}",
        "candidate.patch", "usage.json", "pi-session.jsonl");
    var node = json.readTree(result.outputs().get("result.json"));
    assertThat(node.path("outcome").asString()).isEqualTo("FAILED");
    assertThat(node.path("failure").path("reason").asString()).isEqualTo("TIMEOUT");
    assertThat(node.path("failure").path("stage").asString()).isEqualTo("coding");
    assertThat(node.path("cleanup").path("status").asString()).isEqualTo("COMPLETE");
  }

  @Test
  void unsettledCodingRetainsSnapshotAndReportsFailure() {
    SandboxService sandboxes = mock(SandboxService.class);
    PiCodingRunner runner = mock(PiCodingRunner.class);
    SandboxHandle handle = new SandboxHandle("coding", "container");
    SandboxHandle exporter = new SandboxHandle("export", "export-container");
    when(sandboxes.create(org.mockito.ArgumentMatchers.any())).thenReturn(handle);
    when(sandboxes.freezeForExport(org.mockito.ArgumentMatchers.eq(handle),
        org.mockito.ArgumentMatchers.any())).thenReturn(exporter);
    when(runner.run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(Duration.class), org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyMap())).thenReturn(new PiCodingRunner.CodingAttempt(
            -1, false, "partial\n", List.of(), 1, 0));
    when(sandboxes.exec(org.mockito.ArgumentMatchers.eq(exporter),
        org.mockito.ArgumentMatchers.contains("git add -A"), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(new CommandResult(0, "diff --git a/A b/A\n", "", 1));
    when(sandboxes.exec(org.mockito.ArgumentMatchers.eq(exporter),
        org.mockito.ArgumentMatchers.contains("git write-tree"), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(new CommandResult(0, "base", "", 1));
    var payload = JsonMapper.builder().build().createObjectNode();
    payload.put("taskId", "T"); payload.put("repoUrl", "repo"); payload.put("baseRevision", "base");
    payload.put("branch", "task/T"); payload.put("goal", "edit"); payload.putObject("acceptance");
    AgentContext context = new AgentContext(java.util.UUID.randomUUID(), "coding", Map.of(
        "contract.json", new ArtifactContent("contract.json", 1, "application/json",
            "{\"profile\":{\"imageReference\":\"factory-pi:jdk21\",\"platform\":\"linux/arm64\",\"modelProvider\":\"factory-zai\",\"modelId\":\"glm-5.3-flash\",\"networkPolicy\":{\"execution\":\"GATEWAY_ONLY\"}}}")), payload,
        Map.of(), List.of(), 1);
    AgentResult result = new PiWorker("pi-coding-worker", sandboxes, runner, "", "gateway")
        .execute(context);
    assertThat(result.outputs().get("candidate.patch")).contains("diff --git");
    assertThat(json.readTree(result.outputs().get("candidate.json")).path("retained").asBoolean()).isFalse();
    assertThat(result.metrics()).containsEntry("failure_stage", "coding");
    verify(sandboxes).teardown(handle);
    verify(sandboxes).teardown(exporter);
    verify(sandboxes, org.mockito.Mockito.never()).exec(org.mockito.ArgumentMatchers.eq(handle),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void resolvedProfileDrivesSandboxAndPiRuntimeValues() {
    SandboxService sandboxes = mock(SandboxService.class);
    PiCodingRunner runner = mock(PiCodingRunner.class);
    SandboxHandle handle = new SandboxHandle("coding", "container");
    SandboxHandle exporter = new SandboxHandle("export", "export-container");
    when(sandboxes.create(org.mockito.ArgumentMatchers.any())).thenReturn(handle);
    when(sandboxes.freezeForExport(org.mockito.ArgumentMatchers.eq(handle),
        org.mockito.ArgumentMatchers.any())).thenReturn(exporter);
    when(runner.run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(Duration.class), org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyMap())).thenReturn(new PiCodingRunner.CodingAttempt(
            0, true, "", List.of(), 1, 0));
    when(sandboxes.exec(org.mockito.ArgumentMatchers.eq(exporter), org.mockito.ArgumentMatchers.contains("git add -A"),
        org.mockito.ArgumentMatchers.anyLong())).thenReturn(new CommandResult(0, "diff --git a/A b/A\n", "", 1));
    when(sandboxes.exec(org.mockito.ArgumentMatchers.eq(exporter), org.mockito.ArgumentMatchers.contains("git write-tree"),
        org.mockito.ArgumentMatchers.anyLong())).thenReturn(new CommandResult(0, "candidate\n", "", 1));
    var payload = JsonMapper.builder().build().createObjectNode();
    payload.put("taskId", "T"); payload.put("repoUrl", "repo"); payload.put("baseRevision", "base");
    payload.put("branch", "task/T"); payload.put("goal", "edit"); payload.putArray("acceptance");
    String contract = "{\"profile\":{\"imageReference\":\"maven:3.9-eclipse-temurin-21\","
        + "\"platform\":\"linux/amd64\",\"modelProvider\":\"java-provider\","
        + "\"modelId\":\"java-model\",\"networkPolicy\":{\"execution\":\"NONE\"}}}";
    AgentContext context = new AgentContext(java.util.UUID.randomUUID(), "coding",
        Map.of("contract.json", new ArtifactContent("contract.json", 1, "application/json", contract)),
        payload, Map.of(), List.of(), 1);
    new PiWorker("pi-coding-worker", sandboxes, runner, "", "gateway").execute(context);
    var spec = org.mockito.ArgumentCaptor.forClass(SandboxSpec.class);
    verify(sandboxes).create(spec.capture());
    assertThat(spec.getValue().image()).isEqualTo("maven:3.9-eclipse-temurin-21");
    assertThat(spec.getValue().platform()).isEqualTo("linux/amd64");
    assertThat(spec.getValue().networkPolicy()).isEqualTo("NONE");
    var argv = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(runner).run(org.mockito.ArgumentMatchers.eq(handle), argv.capture(), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Duration.class),
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyMap());
    assertThat(argv.getValue()).containsExactly("/opt/pi/node_modules/.bin/pi", "--mode", "rpc",
        "--provider", "java-provider", "--model", "java-model", "--no-approve", "--no-extensions",
        "--no-skills", "--no-prompt-templates", "--no-themes", "--no-context-files", "--tools",
        "read,bash,edit,write,grep,find,ls", "--thinking", "high", "--append-system-prompt",
        "Factory owns the repository boundary. Edit only the prepared repository; make no external writes; report completion after the requested checks.",
        "--session-dir", "/state/pi/sessions");
  }

  @Test
  void codingPublishesRecoveryBundleForTheResultBeforeCleanup(@TempDir Path tempDir) {
    SandboxService sandboxes = mock(SandboxService.class);
    PiCodingRunner runner = mock(PiCodingRunner.class);
    SandboxHandle handle = new SandboxHandle("coding", "container");
    SandboxHandle exporter = new SandboxHandle("export", "export-container");
    when(sandboxes.create(org.mockito.ArgumentMatchers.any())).thenReturn(handle);
    when(sandboxes.freezeForExport(org.mockito.ArgumentMatchers.eq(handle),
        org.mockito.ArgumentMatchers.any())).thenReturn(exporter);
    when(runner.run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(Duration.class), org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyMap())).thenReturn(new PiCodingRunner.CodingAttempt(
            0, true, "session\n", List.of(), 1, 0));
    when(sandboxes.exec(org.mockito.ArgumentMatchers.eq(exporter),
        org.mockito.ArgumentMatchers.contains("git add -A"), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(new CommandResult(0, "diff --git a/A b/A\n", "", 1));
    when(sandboxes.exec(org.mockito.ArgumentMatchers.eq(exporter),
        org.mockito.ArgumentMatchers.contains("git write-tree"), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(new CommandResult(0, "candidate-tree\n", "", 1));
    var payload = JsonMapper.builder().build().createObjectNode();
    payload.put("taskId", "T"); payload.put("repoUrl", "repo"); payload.put("baseRevision", "base");
    payload.put("branch", "task/T"); payload.put("goal", "edit"); payload.putObject("acceptance");
    AgentContext context = new AgentContext(java.util.UUID.randomUUID(), "coding", Map.of(
        "contract.json", new ArtifactContent("contract.json", 1, "application/json",
            "{\"status\":\"READY\",\"profile\":{\"imageReference\":\"factory-pi:jdk21\","
                + "\"platform\":\"linux/arm64\",\"modelProvider\":\"factory-zai\","
                + "\"modelId\":\"glm-5.3-flash\",\"networkPolicy\":{\"execution\":\"NONE\"}}}")),
        payload, Map.of(), List.of(), 1);
    RecoveryBundleStore recovery = new RecoveryBundleStore(tempDir.resolve("recovery"));

    AgentResult result = new PiWorker("pi-coding-worker", sandboxes, runner, "", "gateway",
        "high", recovery).execute(context);

    var bundle = recovery.find(context.executionId(), "coding", 1).orElseThrow();
    assertThat(bundle.completeness()).isEqualTo("COMPLETE");
    assertThat(bundle.artifactNames()).contains("candidate.patch", "candidate.json", "usage.json");
    assertThat(result.metrics()).containsKey("recovery_locator");
    verify(sandboxes).teardown(handle);
    verify(sandboxes).teardown(exporter);
  }

  @Test
  void verificationPreservesPiFailureReasonWhenCandidatePatchIsEmpty() {
    var payload = JsonMapper.builder().build().createObjectNode();
    payload.put("baseRevision", "base");
    AgentContext context = new AgentContext(java.util.UUID.randomUUID(), "verify", Map.of(
        "candidate.patch", new ArtifactContent("candidate.patch", 1, "text/plain", ""),
        "candidate.json", new ArtifactContent("candidate.json", 1, "application/json",
            "{\"status\":\"FAILED\",\"stage\":\"PI_RUNTIME\","
                + "\"reason\":\"PI_UNSETTLED\",\"retained\":true,\"base\":\"base\"}")),
        payload, Map.of(), List.of(), 1);

    AgentResult result = new PiWorker("pi-verify-worker", mock(SandboxService.class),
        mock(PiCodingRunner.class), "", "gateway").execute(context);

    var verification = json.readTree(result.outputs().get("verification.json"));
    assertThat(verification.path("reason").asString()).isEqualTo("PI_UNSETTLED");
    assertThat(verification.path("retained").asBoolean()).isTrue();
  }

  @Test
  void deliverPrSuccessRequiresADeliveredCandidate() {
    var delivered = json.readTree(finalizeWithDelivery("{\"schema\":\"DevFlowDelivery/v1\",\"mode\":\"DELIVER_PR\","
        + "\"status\":\"DELIVERED\",\"targetRepository\":\"operator/mod-x\",\"branch\":\"factory/X-1-abc\","
        + "\"commitSha\":\"c0ffee\",\"pullRequest\":{\"url\":\"https://github.com/operator/mod-x/pull/1\"}}")
        .outputs().get("result.json"));
    assertThat(delivered.path("outcome").asString()).isEqualTo("SUCCESS");
    assertThat(delivered.path("delivery").path("pullRequest").asString())
        .isEqualTo("https://github.com/operator/mod-x/pull/1");

    var failed = json.readTree(finalizeWithDelivery("{\"mode\":\"DELIVER_PR\",\"status\":\"REFUSED\","
        + "\"reason\":\"CANDIDATE_IDENTITY_MISMATCH\"}").outputs().get("result.json"));
    assertThat(failed.path("outcome").asString()).isEqualTo("ERROR");
    assertThat(failed.path("failure").path("stage").asString()).isEqualTo("DELIVERY");
    assertThat(failed.path("failure").path("reason").asString()).isEqualTo("CANDIDATE_IDENTITY_MISMATCH");

    var noCredential = json.readTree(finalizeWithDelivery("{\"mode\":\"DELIVER_PR\",\"status\":\"REFUSED\","
        + "\"reason\":\"DELIVERY_CREDENTIALS_NOT_CONFIGURED\"}").outputs().get("result.json"));
    assertThat(noCredential.path("outcome").asString()).isEqualTo("BLOCKED_ENVIRONMENT");

    var local = json.readTree(finalizeWithDelivery("{\"mode\":\"LOCAL_ONLY\",\"status\":\"NOT_REQUESTED\"}")
        .outputs().get("result.json"));
    assertThat(local.path("outcome").asString()).isEqualTo("SUCCESS");
    assertThat(local.has("failure")).isFalse();
  }

  private AgentResult finalizeWithDelivery(String delivery) {
    AgentContext context = new AgentContext(java.util.UUID.randomUUID(), "finalize",
        Map.of("candidate.patch", new ArtifactContent("candidate.patch", 1, "text/plain", "patch"),
            "verification.json", new ArtifactContent("verification.json", 1, "application/json",
                "{\"status\":\"PASS\"}"),
            "delivery.json", new ArtifactContent("delivery.json", 1, "application/json", delivery)),
        json.createObjectNode(), Map.of(), java.util.List.of());
    return new PiWorker("pi-finalize-worker", mock(SandboxService.class), mock(PiCodingRunner.class),
        "", "http://factory-gateway:8080/v1").execute(context);
  }

  private AgentResult finalizeWorker(String status, String verification, String candidate,
      String usage, String session) {
    AgentContext context = new AgentContext(java.util.UUID.randomUUID(), "finalize",
        Map.of("candidate.patch", new ArtifactContent("candidate.patch", 1, "text/plain", "patch"),
            "verification.json", new ArtifactContent("verification.json", 1, "application/json", verification),
            "usage.json", new ArtifactContent("usage.json", 1, "application/json", "{\"provider_calls\":2}"),
            "pi-session.jsonl", new ArtifactContent("pi-session.jsonl", 1, "application/jsonl", "session")),
        JsonMapper.builder().build().createObjectNode(), Map.of(), java.util.List.of());
    // The worker only needs its declared collaborators for finalization.
    return new PiWorker("pi-finalize-worker", mock(SandboxService.class), mock(PiCodingRunner.class),
        "", "http://factory-gateway:8080/v1").execute(context);
  }
}
