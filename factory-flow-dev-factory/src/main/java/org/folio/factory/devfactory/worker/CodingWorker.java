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
import org.folio.factory.sandbox.harness.TaskContract;
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
 *
 * <p>Export contract (FTQ-02): the base revision is pinned right after the
 * clone ({@code git rev-parse HEAD}, before the harness can move HEAD), the
 * patch is the complete change against that recorded base — added (including
 * untracked), modified and deleted files, staged and committed-after-base
 * work, renames and binary content — and the artifacts are durably written to
 * the workDir <em>before</em> the sandbox is torn down. If that durable write
 * fails, the sandbox (the model's unique copy of the change) is retained for
 * recovery instead of destroyed.
 */
public class CodingWorker implements AgentWorker {

  private static final Logger log = LoggerFactory.getLogger(CodingWorker.class);

  private static final String EMPTY_PATCH = "(no changes)\n";
  private static final long DIFF_TIMEOUT_SEC = 30L;
  private static final long EXPORT_TIMEOUT_SEC = 120L;
  private static final String REV_PARSE_COMMAND = "cd repo && git rev-parse HEAD";
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
    TaskContract contract = contractFrom(context.triggerPayload());

    Path workDir = null;
    boolean tempWorkDir = false;
    SandboxHandle handle = null;
    AgentResult result = null;
    boolean retainForRecovery = false;
    try {
      Object configuredWorkDir = context.config().get("workDir");
      if (configuredWorkDir != null && !configuredWorkDir.toString().isBlank()) {
        workDir = Path.of(configuredWorkDir.toString());
      } else {
        workDir = Files.createTempDirectory("devfactory-");
        tempWorkDir = true;
      }
      handle = sandboxService.create(new SandboxSpec(taskId, repoUrl, baseBranch, branch));
      String baseRevision = readBaseRevision(handle);
      HarnessReport report = harness.run(handle, contract, workDir);
      CommandResult diff = sandboxService.exec(handle, exportCommand(baseRevision),
          EXPORT_TIMEOUT_SEC);
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
      metadata.put("base_revision", baseRevision);
      metadata.put("outcome", report.outcome().name());
      metadata.put("stop_reason", report.stopReason().name());
      metadata.put("task_outcome", report.taskOutcome().name());
      metadata.put("task_outcome_reason", report.taskOutcomeReason().name());
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
      metrics.put("task_outcome", report.taskOutcome().name());
      metrics.put("steps", report.steps());
      metrics.put("diff_size_bytes", report.diffSizeBytes());

      result = new AgentResult(Map.of(
          "patch.diff", patch,
          "report.md", reportMd,
          "trajectory.jsonl", trajectory), metrics);
      try {
        persistOutputs(workDir, result);
      } catch (IOException e) {
        retainForRecovery = true;
        throw new AgentExecutionException("coding-worker could not durably persist artifacts "
            + "for task " + taskId + " in " + workDir + "; sandbox retained for recovery: "
            + e.getMessage(), e);
      }
      return result;
    } catch (AgentExecutionException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new AgentExecutionException(
          "coding-worker infrastructure failure for task " + taskId + ": " + e.getMessage(), e);
    } finally {
      if (handle != null && !retainForRecovery) {
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
      if (tempWorkDir && !retainForRecovery) {
        deleteBestEffort(workDir);
      }
    }
  }

  /**
   * Pins the base revision for the exported patch: HEAD immediately after the
   * clone, before the harness runs and can commit onto the task branch. A
   * consumer applies the exported patch to a fresh checkout of exactly this
   * revision (it is recorded as {@code base_revision} in report.md).
   */
  private String readBaseRevision(SandboxHandle handle) {
    CommandResult revParse = sandboxService.exec(handle, REV_PARSE_COMMAND, DIFF_TIMEOUT_SEC);
    if (!revParse.ok()) {
      String reason = revParse.stderr() == null || revParse.stderr().isBlank()
          ? revParse.stdout() : revParse.stderr();
      throw new AgentExecutionException("could not read base revision in sandbox: " + reason);
    }
    String baseRevision = revParse.stdout() == null ? "" : revParse.stdout().trim();
    if (!baseRevision.matches("[0-9a-f]{40,64}")) {
      throw new AgentExecutionException("base revision is not a raw commit id: '"
          + baseRevision + "'");
    }
    return baseRevision;
  }

  /**
   * One shell invocation so the temp index lives and dies inside it: seed a
   * scratch index from the recorded base, stage the entire worktree onto it
   * ({@code git add -A} — untracked, modified, deleted and staged content
   * alike; worktree state wins over a stale staging area), then diff that
   * index against the same base. {@code --binary} carries binary content,
   * {@code -M} pins rename detection on regardless of repo config; diffing
   * against the recorded base (not HEAD) also captures anything the model
   * committed during the run. The real index is never touched
   * (GIT_INDEX_FILE) and the scratch index is removed before the shell exits.
   */
  private static String exportCommand(String baseRevision) {
    return "cd repo && base=" + baseRevision
        + " && idx=$(mktemp) && { GIT_INDEX_FILE=\"$idx\" git read-tree \"$base\""
        + " && GIT_INDEX_FILE=\"$idx\" git add -A"
        + " && GIT_INDEX_FILE=\"$idx\" git diff --binary -M --cached \"$base\";"
        + " rc=$?; rm -f \"$idx\"; exit \"$rc\"; }";
  }

  /**
   * Durably freezes the run's artifacts next to the trajectory in the workDir
   * before any destructive cleanup, so the exported change survives a sandbox
   * teardown even if the engine's artifact-store write later fails.
   */
  private static void persistOutputs(Path workDir, AgentResult result) throws IOException {
    Files.createDirectories(workDir);
    for (Map.Entry<String, String> output : result.outputs().entrySet()) {
      Files.writeString(workDir.resolve(output.getKey()), output.getValue());
    }
  }

  private static String requireTriggerKey(AgentContext context, String key) {
    return requirePayloadKey(context.triggerPayload(), key);
  }

  /**
   * Builds the executor's task contract from the trigger payload: goal (still
   * required) plus the acceptance criteria, constraints and notes filed with
   * the task, passed through as JSON subtrees so nothing is re-parsed,
   * truncated or silently dropped. A malformed section fails the step loudly
   * rather than being dropped.
   */
  private static TaskContract contractFrom(JsonNode payload) {
    String goal = requirePayloadKey(payload, "goal");
    JsonNode acceptance = payload.get("acceptance");
    JsonNode constraints = payload.get("constraints");
    JsonNode notes = payload.get("notes");
    return new TaskContract(goal,
        acceptance == null || acceptance.isNull() ? null : acceptance,
        constraints == null || constraints.isNull() ? null : constraints,
        notes != null && notes.isTextual() ? notes.asString() : null);
  }

  private static String requirePayloadKey(JsonNode payload, String key) {
    String value = payload == null ? "" : payload.path(key).asString("");
    if (value.isBlank()) {
      throw new AgentExecutionException("coding-worker trigger payload missing key '" + key + "'");
    }
    return value;
  }

  /**
   * Keeps the git patch bytes exactly as git emitted them: a {@code GIT
   * binary patch} base85 block is structurally terminated by an empty line,
   * and {@code git apply} rejects (exit 128, "corrupt binary patch") any
   * trailing-whitespace normalization that removes it. A blank diff (no
   * change at all) maps to the explicit no-changes marker instead.
   */
  private static String extractPatch(String diffStdout) {
    String stdout = diffStdout == null ? "" : diffStdout;
    int marker = stdout.indexOf(DIFF_MARKER);
    String patch = marker >= 0 ? stdout.substring(marker + DIFF_MARKER.length()) : stdout;
    return patch.isBlank() ? EMPTY_PATCH : patch;
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
