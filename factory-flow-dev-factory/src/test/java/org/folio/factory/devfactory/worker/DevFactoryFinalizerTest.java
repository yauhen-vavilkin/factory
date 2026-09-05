package org.folio.factory.devfactory.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.folio.factory.agents.artifact.ArtifactFormatException;
import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.service.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class DevFactoryFinalizerTest {

  private static final UUID EXECUTION_ID = UUID.randomUUID();

  @Mock
  private ArtifactStore artifactStore;

  private final FrontmatterCodec codec = new FrontmatterCodec();

  @Test
  void summaryCarriesIdentificationOutcomeAndFullInventory() {
    List<Artifact> rows = List.of(
        artifact("patch.diff", 1),
        artifact("report.md", 1),
        artifact("report.md", 2),
        artifact("trajectory.jsonl", 1));
    when(artifactStore.allForExecution(EXECUTION_ID)).thenReturn(rows);
    DevFactoryFinalizer finalizer = new DevFactoryFinalizer(artifactStore, codec);

    assertThat(finalizer.id()).isEqualTo("dev-factory-finalizer");
    AgentResult result = finalizer.execute(context(reportMd("COMPLETED", "COMPLETED")));

    Frontmatter summary = codec.parse(result.outputs().get("delivery-summary.md"));
    JsonNode metadata = summary.metadata();
    List<String> keys = new ArrayList<>(metadata.propertyNames());
    assertThat(keys).containsExactlyInAnyOrder("flow_id", "task_id", "repo_url", "branch",
        "outcome", "stop_reason", "artifact_count");
    assertThat(metadata.path("flow_id").asString()).isEqualTo("dev-factory");
    assertThat(metadata.path("task_id").asString()).isEqualTo("T-16");
    assertThat(metadata.path("repo_url").asString()).isEqualTo("https://github.com/folio/o-r.git");
    assertThat(metadata.path("branch").asString()).isEqualTo("dev/T16");
    assertThat(metadata.path("outcome").asString()).isEqualTo("COMPLETED");
    assertThat(metadata.path("stop_reason").asString()).isEqualTo("COMPLETED");
    assertThat(metadata.path("artifact_count").asInt()).isEqualTo(4);

    List<String> inventoryLines = summary.body().lines()
        .filter(line -> line.startsWith("- `"))
        .toList();
    assertThat(inventoryLines).containsExactly(
        inventoryLine("patch.diff", 1),
        inventoryLine("report.md", 1),
        inventoryLine("report.md", 2),
        inventoryLine("trajectory.jsonl", 1));
  }

  @Test
  void failedOutcomeFlowsThrough() {
    when(artifactStore.allForExecution(EXECUTION_ID))
        .thenReturn(List.of(artifact("report.md", 1)));
    DevFactoryFinalizer finalizer = new DevFactoryFinalizer(artifactStore, codec);

    AgentResult result = finalizer.execute(context(reportMd("FAILED", "TIMEOUT")));

    Frontmatter summary = codec.parse(result.outputs().get("delivery-summary.md"));
    assertThat(summary.metadata().path("outcome").asString()).isEqualTo("FAILED");
    assertThat(summary.metadata().path("stop_reason").asString()).isEqualTo("TIMEOUT");
    assertThat(summary.body()).contains("- Harness outcome: FAILED (stop reason: TIMEOUT)");
  }

  @Test
  void malformedReportFailsStep() {
    DevFactoryFinalizer finalizer = new DevFactoryFinalizer(artifactStore, codec);
    AgentContext malformed = new AgentContext(EXECUTION_ID, "finalize",
        Map.of("report.md", new ArtifactContent("report.md", 1, "text/markdown",
            "# Report without frontmatter")),
        decoyPayload(), Map.of(), List.of("delivery-summary.md"));

    assertThatThrownBy(() -> finalizer.execute(malformed))
        .isInstanceOf(AgentExecutionException.class)
        .hasCauseInstanceOf(ArtifactFormatException.class);
  }

  private AgentContext context(String reportMd) {
    return new AgentContext(EXECUTION_ID, "finalize",
        Map.of("report.md", new ArtifactContent("report.md", 2, "text/markdown", reportMd)),
        decoyPayload(), Map.of(), List.of("delivery-summary.md"));
  }

  private JsonNode decoyPayload() {
    return JsonMapper.builder().build().valueToTree(Map.of(
        "taskId", "DECOY-TASK",
        "repoUrl", "https://github.com/decoy/wrong.git",
        "branch", "decoy/wrong-branch"));
  }

  private String reportMd(String outcome, String stopReason) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("task_id", "T-16");
    metadata.put("repo_url", "https://github.com/folio/o-r.git");
    metadata.put("branch", "dev/T16");
    metadata.put("outcome", outcome);
    metadata.put("stop_reason", stopReason);
    metadata.put("steps", 3);
    metadata.put("files_changed", 2);
    metadata.put("diff_size_bytes", 1024);
    metadata.put("format_errors", 0);
    return codec.render(metadata, "");
  }

  private Artifact artifact(String name, int version) {
    String content = contentOf(name, version);
    return new Artifact(EXECUTION_ID, name, version, "text/plain", content,
        ArtifactStore.sha256(content), "coding-worker");
  }

  private String inventoryLine(String name, int version) {
    return "- `" + name + "` v" + version + " — sha256:" + ArtifactStore.sha256(contentOf(name, version));
  }

  private String contentOf(String name, int version) {
    return "content-of " + name + " v" + version;
  }
}
