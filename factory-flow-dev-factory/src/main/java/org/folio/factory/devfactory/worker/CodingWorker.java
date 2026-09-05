package org.folio.factory.devfactory.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.api.SandboxSpec;
import org.folio.factory.sandbox.harness.CodingHarness;
import org.folio.factory.sandbox.harness.HarnessReport;
import org.folio.factory.sandbox.harness.Trajectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Coding step of the dev-factory flow: owns exactly one sandbox for the
 * execution (create → harness run → diff → teardown-once), then freezes the
 * run into the three artifacts the flow descriptor declares — patch.diff,
 * report.md (frontmatter-only) and trajectory.jsonl. A FAILED harness report
 * (steps/format/timeout/model exhausted) is a normal result; only
 * infrastructure errors throw.
 */
public class CodingWorker implements AgentWorker {

  private static final Logger log = LoggerFactory.getLogger(CodingWorker.class);

  private static final String EMPTY_PATCH = "(no changes)\n";
  private static final long DIFF_TIMEOUT_SEC = 30L;
  private static final String DIFF_COMMAND = "cd repo && git diff";
  private static final String DIFF_MARKER = "[diff]\n";

  private final SandboxService sandboxService;
  private final CodingHarness harness;
  private final FrontmatterCodec frontmatterCodec;

  public CodingWorker(SandboxService sandboxService, CodingHarness harness,
      FrontmatterCodec frontmatterCodec) {
    this.sandboxService = sandboxService;
    this.harness = harness;
    this.frontmatterCodec = frontmatterCodec;
  }

  @Override
  public String id() {
    return "coding-worker";
  }

  @Override
  public AgentResult execute(AgentContext context) throws AgentExecutionException {
    String taskId = requireTriggerKey(context, "taskId");
    String repoUrl = requireTriggerKey(context, "repoUrl");
    String baseBranch = requireTriggerKey(context, "baseBranch");
    String branch = requireTriggerKey(context, "branch");
    String goal = requireTriggerKey(context, "goal");

    Path workDir = null;
    boolean tempWorkDir = false;
    SandboxHandle handle = null;
    AgentResult result = null;
    try {
      Object configuredWorkDir = context.config().get("workDir");
      if (configuredWorkDir != null && !configuredWorkDir.toString().isBlank()) {
        workDir = Path.of(configuredWorkDir.toString());
      } else {
        workDir = Files.createTempDirectory("devfactory-");
        tempWorkDir = true;
      }
      handle = sandboxService.create(new SandboxSpec(taskId, repoUrl, baseBranch, branch));
      HarnessReport report = harness.run(handle, goal, workDir);
      CommandResult diff = sandboxService.exec(handle, DIFF_COMMAND, DIFF_TIMEOUT_SEC);
      if (!diff.ok()) {
        String reason = diff.stderr() == null || diff.stderr().isBlank() ? diff.stdout() : diff.stderr();
        throw new AgentExecutionException("git diff failed in sandbox: " + reason);
      }
      String patch = extractPatch(diff.stdout());
      Path trajectoryFile = workDir.resolve(Trajectory.FILE_NAME);
      String trajectory = Files.exists(trajectoryFile) ? Files.readString(trajectoryFile) : "";

      Map<String, Object> metadata = new LinkedHashMap<String, Object>();
      metadata.put("task_id", taskId);
      metadata.put("repo_url", repoUrl);
      metadata.put("branch", branch);
      metadata.put("outcome", report.outcome().name());
      metadata.put("stop_reason", report.stopReason().name());
      metadata.put("steps", report.steps());
      metadata.put("files_changed", report.filesChanged());
      metadata.put("diff_size_bytes", report.diffSizeBytes());
      metadata.put("format_errors", report.formatErrors());
      metadata.put("tokens_in", report.tokensIn());
      metadata.put("tokens_out", report.tokensOut());
      String reportMd = frontmatterCodec.render(metadata, "");

      Map<String, Object> metrics = new LinkedHashMap<String, Object>();
      metrics.put("outcome", report.outcome().name());
      metrics.put("stop_reason", report.stopReason().name());
      metrics.put("steps", report.steps());
      metrics.put("diff_size_bytes", report.diffSizeBytes());

      result = new AgentResult(Map.of(
          "patch.diff", patch,
          "report.md", reportMd,
          "trajectory.jsonl", trajectory), metrics);
      return result;
    } catch (IOException | RuntimeException e) {
      throw new AgentExecutionException(
          "coding-worker infrastructure failure for task " + taskId + ": " + e.getMessage(), e);
    } finally {
      if (handle != null) {
        try {
          sandboxService.teardown(handle);
        } catch (RuntimeException teardownFailure) {
          if (result != null) {
            throw new AgentExecutionException("coding-worker teardown failed for task "
                + taskId + ": " + teardownFailure.getMessage(), teardownFailure);
          }
          log.warn("Teardown after failed run also failed for task {}: {}", taskId,
              teardownFailure.getMessage());
        }
      }
      if (tempWorkDir) {
        deleteBestEffort(workDir);
      }
    }
  }

  private static String requireTriggerKey(AgentContext context, String key) {
    JsonNode payload = context.triggerPayload();
    String value = payload == null ? "" : payload.path(key).asString("");
    if (value.isBlank()) {
      throw new AgentExecutionException("coding-worker trigger payload missing key '" + key + "'");
    }
    return value;
  }

  private static String extractPatch(String diffStdout) {
    String stdout = diffStdout == null ? "" : diffStdout;
    int marker = stdout.indexOf(DIFF_MARKER);
    String patch = marker >= 0 ? stdout.substring(marker + DIFF_MARKER.length()) : stdout;
    return patch.isBlank() ? EMPTY_PATCH : patch.stripTrailing() + "\n";
  }

  private static void deleteBestEffort(Path dir) {
    try (Stream<Path> paths = Files.walk(dir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.delete(path);
        } catch (IOException e) {
          log.debug("Could not delete temporary workdir entry {}: {}", path, e.getMessage());
        }
      });
    } catch (IOException e) {
      log.debug("Could not delete temporary workdir {}: {}", dir, e.getMessage());
    }
  }
}
