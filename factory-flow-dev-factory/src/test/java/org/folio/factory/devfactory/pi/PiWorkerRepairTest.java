package org.folio.factory.devfactory.pi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PiWorkerRepairTest {
  private static final String OLD_PATCH = "diff --git a/A b/A\nold\n";
  private static final String NEW_PATCH = "diff --git a/A b/A\nnew\n";
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void repairableFailureDispatchesOneFreshPiAttemptWithCompactEvidenceAndNewIdentity() {
    SandboxService sandboxes = mock(SandboxService.class);
    PiCodingRunner runner = mock(PiCodingRunner.class);
    SandboxHandle repair = new SandboxHandle("repair", "repair-container");
    SandboxHandle exporter = new SandboxHandle("export", "export-container");
    when(sandboxes.create(any())).thenReturn(repair);
    when(sandboxes.exec(eq(repair), contains("git apply"), anyLong()))
        .thenReturn(new CommandResult(0, "", "", 1));
    when(runner.run(eq(repair), any(), anyString(), anyString(), any(Duration.class), anyLong(), any()))
        .thenReturn(settledAttempt(1));
    when(sandboxes.freezeForExport(eq(repair), any())).thenReturn(exporter);
    when(sandboxes.exec(eq(exporter), contains("git add -A"), anyLong()))
        .thenReturn(new CommandResult(0, NEW_PATCH, "", 1));
    when(sandboxes.exec(eq(exporter), contains("git write-tree"), anyLong()))
        .thenReturn(new CommandResult(0, "new-tree\n", "", 1));
    String noisy = "discard-me-" + "x".repeat(30_000) + "-useful-tail";

    AgentResult result = repairWorker(sandboxes, runner, verification("FAIL", "VERIFICATION_FAILED",
        noisy));

    JsonNode candidate = json.readTree(result.outputs().get("candidate.json"));
    JsonNode repairArtifact = json.readTree(result.outputs().get("repair.json"));
    assertThat(candidate.path("candidateTree").asText()).isEqualTo("new-tree");
    assertThat(candidate.path("patchSha256").asText()).isEqualTo(ArtifactStore.sha256(NEW_PATCH));
    assertThat(candidate.path("previousCandidateTree").asText()).isEqualTo("old-tree");
    assertThat(repairArtifact.path("attempted").asBoolean()).isTrue();
    assertThat(repairArtifact.path("providerCalls").asInt()).isEqualTo(1);
    assertThat(repairArtifact.path("result").asText()).isEqualTo("NEW_CANDIDATE");
    assertThat(json.readTree(result.outputs().get("usage.json")).path("provider_calls").asInt())
        .isEqualTo(3);
    ArgumentCaptor<String> task = ArgumentCaptor.forClass(String.class);
    verify(runner).run(eq(repair), any(), anyString(), task.capture(), any(Duration.class),
        anyLong(), any());
    assertThat(task.getValue()).contains("REPAIR_EXISTING_CANDIDATE", "useful-tail",
        "Repair the existing frozen candidate", "original goal");
    assertThat(task.getValue()).doesNotContain("discard-me-").hasSizeLessThan(20_000);
  }

  @Test
  void environmentAndFactoryFailuresNeverDispatchRepair() {
    for (String verification : List.of(
        "{\"status\":\"BLOCKED_ENVIRONMENT\",\"reason\":\"REQUIRED_CHECK_CANNOT_RUN\"}",
        "{\"status\":\"CANCELLED\",\"reason\":\"CANCELLATION\"}",
        "{\"status\":\"FAIL\",\"stage\":\"PI_RUNTIME\",\"reason\":\"PROVIDER_ERROR\"}",
        "{\"status\":\"ERROR\",\"reason\":\"CANDIDATE_IDENTITY_FAILED\"}",
        "{\"status\":\"FAIL\",\"reason\":\"VERIFICATION_PLAN_MISSING\"}",
        verification("FAIL", "VERIFICATION_FAILED",
            "Could not transfer artifact from http://factory-gateway/maven/repository: "
                + "Temporary failure in name resolution"))) {
      SandboxService sandboxes = mock(SandboxService.class);
      PiCodingRunner runner = mock(PiCodingRunner.class);

      AgentResult result = repairWorker(sandboxes, runner, verification);

      JsonNode repair = json.readTree(result.outputs().get("repair.json"));
      assertThat(repair.path("attempted").asBoolean()).isFalse();
      assertThat(repair.path("providerCalls").asInt()).isZero();
      verify(runner, never()).run(any(), any(), anyString(), anyString(), any(), anyLong(), any());
      verify(sandboxes, never()).create(any());
    }
  }

  @Test
  void aSettledRepairThatDoesNotChangeIdentityStopsAfterTheSingleAttempt() {
    SandboxService sandboxes = mock(SandboxService.class);
    PiCodingRunner runner = mock(PiCodingRunner.class);
    SandboxHandle repair = new SandboxHandle("repair", "repair-container");
    SandboxHandle exporter = new SandboxHandle("export", "export-container");
    when(sandboxes.create(any())).thenReturn(repair);
    when(sandboxes.exec(eq(repair), contains("git apply"), anyLong()))
        .thenReturn(new CommandResult(0, "", "", 1));
    when(runner.run(any(), any(), anyString(), anyString(), any(), anyLong(), any()))
        .thenReturn(settledAttempt(1));
    when(sandboxes.freezeForExport(eq(repair), any())).thenReturn(exporter);
    when(sandboxes.exec(eq(exporter), contains("git add -A"), anyLong()))
        .thenReturn(new CommandResult(0, OLD_PATCH, "", 1));
    when(sandboxes.exec(eq(exporter), contains("git write-tree"), anyLong()))
        .thenReturn(new CommandResult(0, "old-tree\n", "", 1));

    AgentResult result = repairWorker(sandboxes, runner,
        verification("FAIL", "VERIFICATION_FAILED", "compile error"));

    assertThat(json.readTree(result.outputs().get("candidate.json")).path("reason").asText())
        .isEqualTo("REPAIR_DID_NOT_CHANGE_CANDIDATE");
    assertThat(json.readTree(result.outputs().get("repair.json")).path("attempt").asInt())
        .isEqualTo(1);
    verify(runner).run(any(), any(), anyString(), anyString(), any(), anyLong(), any());
  }

  @Test
  void terminalProviderFailureStopsWithoutSpendingAnExportSandbox() {
    SandboxService sandboxes = mock(SandboxService.class);
    PiCodingRunner runner = mock(PiCodingRunner.class);
    SandboxHandle repair = new SandboxHandle("repair", "repair-container");
    when(sandboxes.create(any())).thenReturn(repair);
    when(sandboxes.exec(eq(repair), contains("git apply"), anyLong()))
        .thenReturn(new CommandResult(0, "", "", 1));
    when(runner.run(any(), any(), anyString(), anyString(), any(), anyLong(), any()))
        .thenReturn(providerFailedAttempt());

    AgentResult result = repairWorker(sandboxes, runner,
        verification("FAIL", "VERIFICATION_FAILED", "checkstyle violation"));

    JsonNode repairArtifact = json.readTree(result.outputs().get("repair.json"));
    assertThat(repairArtifact.path("attempted").asBoolean()).isTrue();
    assertThat(repairArtifact.path("attempt").asInt()).isEqualTo(1);
    assertThat(repairArtifact.path("providerCalls").asInt()).isEqualTo(1);
    assertThat(repairArtifact.path("result").asText()).isEqualTo("REPAIR_PROVIDER_ERROR");
    JsonNode candidate = json.readTree(result.outputs().get("candidate.json"));
    assertThat(candidate.path("reason").asText()).isEqualTo("REPAIR_PROVIDER_ERROR");
    assertThat(candidate.path("retained").asBoolean()).isFalse();
    verify(sandboxes, never()).freezeForExport(any(), any());
    verify(runner).run(any(), any(), anyString(), anyString(), any(), anyLong(), any());
  }

  @Test
  void repairedCandidateGetsFullFreshVerificationAndStaleIdentityCannotPass() {
    SandboxService sandboxes = mock(SandboxService.class);
    PiCodingRunner runner = mock(PiCodingRunner.class);
    SandboxHandle fresh = new SandboxHandle("verify", "verify-container");
    when(sandboxes.create(any())).thenReturn(fresh);
    when(sandboxes.exec(eq(fresh), contains("git apply"), anyLong()))
        .thenReturn(new CommandResult(0, "", "", 1));
    when(sandboxes.exec(eq(fresh), contains("git write-tree"), anyLong()))
        .thenReturn(new CommandResult(0, "new-tree\n", "", 1));
    when(sandboxes.exec(eq(fresh), contains("mvn -B -ntp test"), anyLong()))
        .thenReturn(new CommandResult(0, "BUILD SUCCESS", "", 1));

    AgentResult result = reverifyWorker(sandboxes, runner, "new-tree",
        ArtifactStore.sha256(NEW_PATCH));

    JsonNode verification = json.readTree(result.outputs().get("verification.json"));
    assertThat(verification.path("status").asText()).isEqualTo("PASS");
    assertThat(verification.path("repairAttempt").asInt()).isEqualTo(1);
    assertThat(verification.path("checks")).hasSize(1);
    verify(sandboxes).exec(eq(fresh), contains("mvn -B -ntp test"), anyLong());
    verify(runner, never()).run(any(), any(), anyString(), anyString(), any(), anyLong(), any());

    SandboxService staleSandboxes = mock(SandboxService.class);
    SandboxHandle stale = new SandboxHandle("stale", "stale-container");
    when(staleSandboxes.create(any())).thenReturn(stale);
    when(staleSandboxes.exec(eq(stale), contains("git apply"), anyLong()))
        .thenReturn(new CommandResult(0, "", "", 1));
    when(staleSandboxes.exec(eq(stale), contains("git write-tree"), anyLong()))
        .thenReturn(new CommandResult(0, "different-tree\n", "", 1));

    AgentResult staleResult = reverifyWorker(staleSandboxes, mock(PiCodingRunner.class),
        "new-tree", ArtifactStore.sha256(NEW_PATCH));

    assertThat(json.readTree(staleResult.outputs().get("verification.json")).path("status").asText())
        .isEqualTo("ERROR");
    verify(staleSandboxes, never()).exec(eq(stale), contains("mvn"), anyLong());
  }

  private AgentResult repairWorker(SandboxService sandboxes, PiCodingRunner runner,
                                   String verification) {
    AgentContext context = new AgentContext(UUID.randomUUID(), "repair", Map.of(
        "contract.json", artifact("contract.json", contract()),
        "candidate.patch", artifact("candidate.patch", OLD_PATCH),
        "candidate.json", artifact("candidate.json", candidate("old-tree", ArtifactStore.sha256(OLD_PATCH))),
        "verification.json", artifact("verification.json", verification),
        "usage.json", artifact("usage.json", "{\"provider_calls\":2}"),
        "pi-session.jsonl", artifact("pi-session.jsonl", "initial\n")),
        payload(), Map.of(), List.of(), 1);
    return new PiWorker("pi-repair-worker", sandboxes, runner, "token", "gateway")
        .execute(context);
  }

  private AgentResult reverifyWorker(SandboxService sandboxes, PiCodingRunner runner,
                                     String candidateTree, String patchHash) {
    AgentContext context = new AgentContext(UUID.randomUUID(), "reverify", Map.of(
        "contract.json", artifact("contract.json", contract()),
        "baseline.json", artifact("baseline.json", "{\"status\":\"PASS\"}"),
        "candidate.patch", artifact("candidate.patch", NEW_PATCH),
        "candidate.json", artifact("candidate.json", candidate(candidateTree, patchHash)),
        "verification.json", artifact("verification.json", "{\"status\":\"FAIL\",\"reason\":\"VERIFICATION_FAILED\"}"),
        "repair.json", artifact("repair.json", "{\"attempted\":true,\"attempt\":1}")),
        payload(), Map.of(), List.of(), 1);
    return new PiWorker("pi-reverify-worker", sandboxes, runner, "", "gateway").execute(context);
  }

  private String verification(String status, String reason, String output) {
    ObjectNode node = json.createObjectNode();
    node.put("status", status);
    node.put("stage", "VERIFY");
    node.put("reason", reason);
    node.putArray("checks").addObject().put("id", "maven-tests")
        .put("command", "mvn -B -ntp test").put("exitCode", 1).put("outputTail", output);
    return json.writeValueAsString(node);
  }

  private ObjectNode payload() {
    ObjectNode payload = json.createObjectNode();
    payload.put("taskId", "TASK-1");
    payload.put("repoUrl", "repo");
    payload.put("baseRevision", "base");
    payload.put("branch", "task/TASK-1");
    payload.put("goal", "original goal");
    payload.putArray("acceptanceCriteria").addObject().put("id", "AC1").put("text", "works");
    ObjectNode resolved = payload.putObject("resolvedIntent");
    resolved.putObject("repository").put("canonicalSlug", "folio-org/example");
    resolved.set("verificationPlan", json.readTree(unitPlan()));
    return payload;
  }

  private String contract() {
    return "{\"status\":\"READY\",\"profile\":{\"imageReference\":\"factory-pi:jdk21\","
        + "\"platform\":\"linux/arm64\",\"modelProvider\":\"factory-zai\","
        + "\"modelId\":\"glm-5.3-flash\",\"networkPolicy\":{\"execution\":\"GATEWAY_ONLY\"}},"
        + "\"resolvedIntent\":{\"verificationPlan\":" + unitPlan() + "}}";
  }

  private String unitPlan() {
    return "{\"id\":\"java-maven-verify\",\"checks\":[{\"id\":\"maven-tests\","
        + "\"argv\":[\"mvn\",\"-B\",\"-ntp\",\"test\"],\"required\":true,"
        + "\"requiresDocker\":false}]}";
  }

  private String candidate(String tree, String patchHash) {
    return "{\"status\":\"READY\",\"base\":\"base\",\"candidateTree\":\"" + tree
        + "\",\"patchSha256\":\"" + patchHash + "\"}";
  }

  private ArtifactContent artifact(String name, String content) {
    return new ArtifactContent(name, 1, "application/json", content);
  }

  private PiCodingRunner.CodingAttempt settledAttempt(int calls) {
    List<JsonNode> events = java.util.stream.IntStream.range(0, calls)
        .mapToObj(ignored -> json.readTree("{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\"}}"))
        .toList();
    return new PiCodingRunner.CodingAttempt(0, true, "repair-session\n", events, 100, 0);
  }

  private PiCodingRunner.CodingAttempt providerFailedAttempt() {
    List<JsonNode> events = List.of(
        json.readTree("{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\"}}"),
        json.readTree("{\"type\":\"auto_retry_end\",\"success\":false,\"attempt\":3,"
            + "\"finalError\":\"502 upstream request failed\"}"));
    return new PiCodingRunner.CodingAttempt(-1, true, "repair-session\n", events, 100, 0);
  }
}
