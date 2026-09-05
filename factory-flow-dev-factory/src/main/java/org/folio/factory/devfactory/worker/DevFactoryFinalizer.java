package org.folio.factory.devfactory.worker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.folio.factory.agents.artifact.ArtifactFormatException;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.service.ArtifactStore;
import tools.jackson.databind.JsonNode;

/**
 * Terminal step of the dev-factory flow: reads the coding step's report.md
 * (report.md is authoritative for task identification and outcome), inventories
 * every artifact version the execution recorded in the ArtifactStore and freezes
 * both into delivery-summary.md. Pure aggregation — no LLM, no connectors.
 */
public class DevFactoryFinalizer implements AgentWorker {

  public static final String ID = "dev-factory-finalizer";

  private static final String FLOW_ID = "dev-factory";

  private final ArtifactStore artifactStore;
  private final FrontmatterCodec frontmatterCodec;

  public DevFactoryFinalizer(ArtifactStore artifactStore, FrontmatterCodec frontmatterCodec) {
    this.artifactStore = artifactStore;
    this.frontmatterCodec = frontmatterCodec;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public AgentResult execute(AgentContext context) throws AgentExecutionException {
    String report = context.requireInput("report.md").content();
    JsonNode reportMetadata;
    try {
      reportMetadata = frontmatterCodec.parse(report).metadata();
    } catch (ArtifactFormatException e) {
      throw new AgentExecutionException(
          "dev-factory-finalizer cannot parse report.md: " + e.getMessage(), e);
    }
    String taskId = requiredText(reportMetadata, "task_id");
    String repoUrl = requiredText(reportMetadata, "repo_url");
    String branch = requiredText(reportMetadata, "branch");
    String outcome = requiredText(reportMetadata, "outcome");
    String stopReason = requiredText(reportMetadata, "stop_reason");

    List<Artifact> rows = artifactStore.allForExecution(context.executionId());

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("flow_id", FLOW_ID);
    metadata.put("task_id", taskId);
    metadata.put("repo_url", repoUrl);
    metadata.put("branch", branch);
    metadata.put("outcome", outcome);
    metadata.put("stop_reason", stopReason);
    metadata.put("artifact_count", rows.size());

    StringBuilder body = new StringBuilder()
        .append("# Delivery summary — ").append(taskId).append("\n\n")
        .append("- Flow: ").append(FLOW_ID).append("\n")
        .append("- Task: ").append(taskId).append("\n")
        .append("- Repository: ").append(repoUrl).append("\n")
        .append("- Branch: ").append(branch).append("\n")
        .append("- Harness outcome: ").append(outcome)
        .append(" (stop reason: ").append(stopReason).append(")\n\n")
        .append("## Artifact inventory\n\n");
    for (Artifact row : rows) {
      body.append("- `").append(row.getName()).append("` v").append(row.getVersion())
          .append(" — sha256:").append(row.getSha256()).append("\n");
    }
    return AgentResult.of("delivery-summary.md", frontmatterCodec.render(metadata, body.toString()));
  }

  private static String requiredText(JsonNode metadata, String key) {
    String value = metadata.path(key).asString("");
    if (value.isBlank()) {
      throw new AgentExecutionException(
          "dev-factory-finalizer: report.md frontmatter is missing required key '" + key + "'");
    }
    return value;
  }
}
