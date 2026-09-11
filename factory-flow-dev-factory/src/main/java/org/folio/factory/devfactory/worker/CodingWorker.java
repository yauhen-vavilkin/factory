package org.folio.factory.devfactory.worker;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import org.folio.factory.devfactory.worker.recovery.RecoveryBundleStore;
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
 * report.md (frontmatter plus the bounded final model response as body)
 * and trajectory.jsonl. A FAILED harness report
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
 *
 * <p>Recovery contract (T25): on every terminal path — complete success,
 * export failure, partial persist — the run publishes a digest-manifested,
 * attempt-keyed recovery bundle through a {@link RecoveryBundleStore} rooted
 * outside the temp workDir and the sandbox workspace root, <em>before</em>
 * any destructive cleanup. The bundle is the cross-attempt preserved copy
 * (both other locations are destroyed); if publication itself fails, the
 * sandbox and workDir are retained and no teardown runs. On terminal paths
 * where the exported patch cannot exist (export failure, or a terminal
 * exception after coding work exists) the bundle is INCOMPLETE under
 * failure_reason {@code EXPORT_FAILED} or {@code TERMINAL_EXCEPTION} and
 * additionally carries one git-independent, base64-text snapshot of the
 * worktree's unique bytes, and the sandbox workspace is attempt-scoped so a
 * retry can never sweep a prior attempt's retained workspace. Destructive
 * cleanup runs only once preservation is established (or no coding work ever
 * existed): any capture or publish failure on a terminal path retains both
 * locations and is surfaced as one fail-closed
 * {@link AgentExecutionException} with the original terminal failure
 * attached as suppressed.
 */
public class CodingWorker implements AgentWorker {

  private static final Logger log = LoggerFactory.getLogger(CodingWorker.class);

  private static final String EMPTY_PATCH = "(no changes)\n";
  private static final long DIFF_TIMEOUT_SEC = 30L;
  private static final long EXPORT_TIMEOUT_SEC = 120L;
  private static final long SNAPSHOT_TIMEOUT_SEC = 120L;
  private static final String REV_PARSE_COMMAND = "cd repo && git rev-parse HEAD";
  private static final String DIFF_MARKER = "[diff]\n";
  private static final String WORKSPACE_SNAPSHOT_ARTIFACT = "workspace-snapshot.tar.gz.b64";
  private static final String TERMINAL_EXCEPTION_REASON = "TERMINAL_EXCEPTION";

  private final SandboxService sandboxService;
  private final CodingHarness harness;
  private final FrontmatterCodec frontmatterCodec;
  private final RecoveryBundleStore recoveryStore;

  public CodingWorker(SandboxService sandboxService, CodingHarness harness,
      FrontmatterCodec frontmatterCodec, RecoveryBundleStore recoveryStore) {
    this.sandboxService = sandboxService;
    this.harness = harness;
    this.frontmatterCodec = frontmatterCodec;
    this.recoveryStore = recoveryStore;
  }

  /**
   * Recovery root defaults to {@link RecoveryBundleStore#DEFAULT_ROOT} when
   * no {@code factory.dev.recovery-root} is configured.
   */
  public CodingWorker(SandboxService sandboxService, CodingHarness harness,
      FrontmatterCodec frontmatterCodec) {
    this(sandboxService, harness, frontmatterCodec,
        new RecoveryBundleStore(RecoveryBundleStore.DEFAULT_ROOT));
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
    if (isResolvedButNotExecutionReady(context.triggerPayload())) {
      return blockedBeforeExecution(taskId, repoUrl, branch);
    }
    TaskContract contract = contractFrom(context.triggerPayload());

    Path workDir = null;
    boolean tempWorkDir = false;
    SandboxHandle handle = null;
    AgentResult result = null;
    String baseRevision = null;
    boolean retainForRecovery = false;
    // T25 S04 boundary flag, set immediately before harness.run: failures
    // before this point cannot have produced unique bytes, so cleanup is
    // provably unnecessary for them; after it, destructive cleanup requires
    // an established preserved copy (or explicit retention).
    boolean codingWorkExists = false;
    boolean preservationEstablished = false;
    try {
      Object configuredWorkDir = context.config().get("workDir");
      if (configuredWorkDir != null && !configuredWorkDir.toString().isBlank()) {
        workDir = Path.of(configuredWorkDir.toString());
      } else {
        workDir = Files.createTempDirectory("devfactory-");
        tempWorkDir = true;
      }
      handle = sandboxService.create(new SandboxSpec(taskId, repoUrl, baseBranch, branch,
          // T22 R4: execution-owned workspace — concurrent executions of the
          // same task get distinct workspaces and cannot delete each other's;
          // T25 S09 scopes the key UNIFORMLY per attempt (attempt 1 included)
          // so a later attempt's create can never sweep a prior attempt's
          // retained workspace.
          workspaceOwner(context)));
      baseRevision = readBaseRevision(handle);
      codingWorkExists = true;
      HarnessReport report = harness.run(handle, contract, workDir);
      CommandResult diff = sandboxService.exec(handle, exportCommand(baseRevision),
          EXPORT_TIMEOUT_SEC);
      if (!diff.ok()) {
        String reason = diff.stderr() == null || diff.stderr().isBlank() ? diff.stdout() : diff.stderr();
        // T25 S04: before the throw reaches the finally-block teardown,
        // preserve whatever the run already produced (typically the
        // trajectory) PLUS a git-independent snapshot of the worktree's
        // unique bytes as one additional bundle artifact; the bundle then is
        // the preserved copy.
        Path preserveDir;
        try {
          preserveDir = publishTerminalPreserveBundle(context, taskId, baseRevision,
              workDir, handle, "EXPORT_FAILED");
        } catch (AgentExecutionException publishFailure) {
          retainForRecovery = true;
          throw publishFailure;
        }
        preservationEstablished = true;
        throw new AgentExecutionException("git diff failed in sandbox: " + reason
            + " (unique worktree bytes preserved in the recovery bundle: "
            + preserveDir.toAbsolutePath() + ")");
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
      String reportMd = frontmatterCodec.render(metadata, report.finalText());

      Map<String, String> outputs = new LinkedHashMap<String, String>();
      outputs.put("patch.diff", patch);
      outputs.put("report.md", reportMd);
      outputs.put("trajectory.jsonl", trajectory);

      Map<String, Object> metrics = new LinkedHashMap<String, Object>();
      metrics.put("outcome", report.outcome().name());
      metrics.put("stop_reason", report.stopReason().name());
      metrics.put("task_outcome", report.taskOutcome().name());
      metrics.put("steps", report.steps());
      metrics.put("diff_size_bytes", report.diffSizeBytes());

      try {
        persistOutputs(workDir, outputs);
      } catch (IOException e) {
        retainForRecovery = true;
        // T25: digest the strict subset of declared outputs that did land in
        // the workDir; keep the existing retention semantics on top.
        publishBundle(context, taskId, baseRevision,
            presentDeclaredOutputs(workDir, context), false, "PERSIST_FAILED");
        throw new AgentExecutionException("coding-worker could not durably persist artifacts "
            + "for task " + taskId + " in " + workDir + "; sandbox retained for recovery: "
            + e.getMessage(), e);
      }
      // T25: publish the complete bundle before the finally block can tear
      // anything down; only a published bundle lets teardown proceed.
      Path bundleDir;
      try {
        bundleDir = publishBundle(context, taskId, baseRevision, outputs, true, null);
      } catch (AgentExecutionException publishFailure) {
        retainForRecovery = true;
        throw publishFailure;
      }
      preservationEstablished = true;
      metrics.put("recovery_locator", bundleDir.toAbsolutePath().toString());
      result = new AgentResult(outputs, metrics);
      return result;
    } catch (AgentExecutionException e) {
      // T25 S04/S08: a terminal path that reached coding work without an
      // established preserved copy (e.g. the harness itself threw after
      // mutating the worktree) must preserve the unique bytes before the
      // finally-block cleanup is allowed to destroy them; a failed preserve
      // (capture, partially-persisted-output read, or publication) retains
      // both locations and is surfaced as one fail-closed
      // AgentExecutionException with the original terminal failure
      // suppressed (never a raw preserve-path exception).
      if (codingWorkExists && handle != null && !preservationEstablished
          && !retainForRecovery) {
        try {
          publishTerminalPreserveBundle(context, taskId, baseRevision,
              workDir, handle, TERMINAL_EXCEPTION_REASON);
          preservationEstablished = true;
        } catch (RuntimeException preserveFailure) {
          retainForRecovery = true;
          throw failClosedTerminalPreserve(taskId, preserveFailure, e);
        }
      }
      throw e;
    } catch (IOException | RuntimeException e) {
      AgentExecutionException infrastructure = new AgentExecutionException(
          "coding-worker infrastructure failure for task " + taskId + ": " + e.getMessage(), e);
      if (codingWorkExists && handle != null && !preservationEstablished
          && !retainForRecovery) {
        try {
          publishTerminalPreserveBundle(context, taskId, baseRevision,
              workDir, handle, TERMINAL_EXCEPTION_REASON);
          preservationEstablished = true;
        } catch (RuntimeException preserveFailure) {
          retainForRecovery = true;
          throw failClosedTerminalPreserve(taskId, preserveFailure, infrastructure);
        }
      }
      throw infrastructure;
    } finally {
      // T25 S04 fail-closed retention: destructive cleanup runs only when
      // preservation is established (a COMPLETE bundle on success, or a
      // terminal-preserve bundle carrying the workspace snapshot) or provably
      // unnecessary (no coding work existed yet); every other terminal state
      // retains both the sandbox workspace and the temp workDir.
      boolean destructiveCleanupAllowed =
          !retainForRecovery && (preservationEstablished || !codingWorkExists);
      if (handle != null && destructiveCleanupAllowed) {
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
      if (tempWorkDir && destructiveCleanupAllowed) {
        deleteBestEffort(workDir);
      }
    }
  }

  private static boolean isResolvedButNotExecutionReady(JsonNode payload) {
    JsonNode intent = payload == null ? null : payload.get("resolvedIntent");
    if (intent == null || !intent.isObject() || intent.path("executionReady").asBoolean(false)) {
      return false;
    }
    // The Pi flow owns the preparation/freeze gate. The legacy harness keeps
    // its established java-maven-21 execution path and must not be blocked by
    // the Pi-only readiness marker while both flows coexist.
    String profile = intent.path("profile").path("id").asString("");
    return profile.isBlank() || "java21-pi-unit".equals(profile);
  }

  private AgentResult blockedBeforeExecution(String taskId, String repoUrl, String branch) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("task_id", taskId);
    metadata.put("repo_url", repoUrl);
    metadata.put("branch", branch);
    metadata.put("outcome", "BLOCKED");
    metadata.put("stop_reason", "EXECUTION_CONTRACT_NOT_FROZEN");
    metadata.put("task_outcome", "BLOCKED");
    metadata.put("task_outcome_reason", "PREPARATION_REFERENCES_REQUIRED");
    String report = frontmatterCodec.render(metadata,
        "# Execution blocked\n\nThe task intent is resolved, but source snapshot, image, dependency seed "
            + "and baseline references have not been prepared. No sandbox, target build, or model was invoked.\n");
    return new AgentResult(Map.of("patch.diff", EMPTY_PATCH, "report.md", report,
        "trajectory.jsonl", ""), Map.of("blocked_before_execution", true));
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
  private static void persistOutputs(Path workDir, Map<String, String> outputs)
      throws IOException {
    Files.createDirectories(workDir);
    for (Map.Entry<String, String> output : outputs.entrySet()) {
      Files.writeString(workDir.resolve(output.getKey()), output.getValue());
    }
  }

  /**
   * T25: digests what exists — the strict subset of the step's declared
   * outputs that is present in the workDir as a regular file. Used on the
   * incomplete terminal paths (export failure, partial persist) where only
   * part of the run's outputs ever landed on disk.
   */
  private static Map<String, String> presentDeclaredOutputs(Path workDir,
      AgentContext context) {
    Map<String, String> present = new LinkedHashMap<String, String>();
    for (String name : context.expectedOutputs()) {
      Path file = workDir.resolve(name);
      if (Files.isRegularFile(file)) {
        try {
          present.put(name, Files.readString(file));
        } catch (IOException e) {
          throw new UncheckedIOException("could not read partially persisted output "
              + file + " for the recovery bundle", e);
        }
      }
    }
    return present;
  }

  /**
   * T25 S09: attempt-scoped workspace ownership, uniform per attempt.
   * LocalSandboxService.create sweeps the workspace named for the spec owner
   * before cloning, so the owner key must never collide across attempts of
   * one execution: EVERY attempt — including the first — keys
   * {@code executionId + "-attempt-" + attempt} (sanitize-safe), so a later
   * attempt's create can only ever clear its own leftover, never a prior
   * attempt's retained workspace.
   */
  private static String workspaceOwner(AgentContext context) {
    if (context.executionId() == null) {
      return null;
    }
    return context.executionId() + "-attempt-" + context.attempt();
  }

  /**
   * T25 S04: publishes the terminal-preserve bundle — every declared output
   * present in the workDir plus ONE additional base64-text artifact holding
   * a git-independent snapshot of the sandbox worktree's unique bytes. Any
   * capture or publish failure is an infrastructure error whose caller must
   * retain the sandbox and workDir; no bundle means no preservation.
   */
  private Path publishTerminalPreserveBundle(AgentContext context, String taskId,
      String baseRevision, Path workDir, SandboxHandle handle, String failureReason) {
    Map<String, String> artifacts = presentDeclaredOutputs(workDir, context);
    artifacts.put(WORKSPACE_SNAPSHOT_ARTIFACT, captureWorkspaceSnapshot(handle));
    return publishBundle(context, taskId, baseRevision, artifacts, false, failureReason);
  }

  /**
   * T25 S08: one fail-closed surface for a failed terminal-preserve attempt.
   * Any preserve-path failure — snapshot capture, the
   * {@link UncheckedIOException} a partially persisted output read can throw,
   * or bundle publication — is surfaced as a single
   * {@link AgentExecutionException} that names the {@code TERMINAL_EXCEPTION}
   * state it could not preserve, chains the preserve failure as its cause and
   * attaches the run's original terminal failure as suppressed. Retention of
   * both producer locations is already guaranteed by the finally gate.
   */
  private static AgentExecutionException failClosedTerminalPreserve(String taskId,
      RuntimeException preserveFailure, AgentExecutionException original) {
    AgentExecutionException failClosed = new AgentExecutionException(
        "coding-worker could not preserve the " + TERMINAL_EXCEPTION_REASON
            + " terminal state for task " + taskId + "; failing closed — retaining both "
            + "the sandbox workspace and the temp workDir for recovery: "
            + preserveFailure.getMessage(), preserveFailure);
    failClosed.addSuppressed(original);
    return failClosed;
  }

  /**
   * T25 S04: captures the sandbox worktree's unique bytes (modified and
   * untracked content — everything under repo/ except .git) as one
   * base64-text payload through the exec seam, so the capture is
   * sandbox-mode-agnostic and works in exactly the probe shape where .git is
   * destroyed and no diff-based capture is possible. The tar runs against a
   * scratch file so its own failure propagates through the exit code; a
   * failed or empty capture is itself a capture failure — the caller retains
   * both producer locations instead of trusting an unverified snapshot. The
   * snapshot size is uncapped by design (documented bound, consistent with
   * the plain-local-writes non-goal).
   */
  private String captureWorkspaceSnapshot(SandboxHandle handle) {
    CommandResult snapshot = sandboxService.exec(handle,
        "cd repo && f=$(mktemp) && { tar --exclude=.git -czf \"$f\" ."
            + " && base64 < \"$f\"; }; rc=$?; rm -f \"$f\"; exit \"$rc\"",
        SNAPSHOT_TIMEOUT_SEC);
    if (!snapshot.ok() || snapshot.stdout() == null || snapshot.stdout().isBlank()) {
      String reason = snapshot.stderr() == null || snapshot.stderr().isBlank()
          ? snapshot.stdout() : snapshot.stderr();
      throw new AgentExecutionException("could not capture workspace snapshot in sandbox: "
          + reason);
    }
    return snapshot.stdout();
  }

  /**
   * T25: publishes one attempt's recovery bundle; any failure is an
   * infrastructure error whose caller must retain the sandbox and workDir —
   * no destructive cleanup may run without a published bundle.
   */
  private Path publishBundle(AgentContext context, String taskId, String baseRevision,
      Map<String, String> artifacts, boolean complete, String failureReason) {
    try {
      return recoveryStore.publish(context.executionId(), context.stepId(), context.attempt(),
          taskId, baseRevision, artifacts, complete, failureReason);
    } catch (RuntimeException e) {
      throw new AgentExecutionException("coding-worker could not publish recovery bundle "
          + "for task " + taskId + " (execution " + context.executionId() + ", step "
          + context.stepId() + ", attempt " + context.attempt()
          + "); retaining sandbox and workDir for recovery: " + e.getMessage(), e);
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
    JsonNode rawTaskText = payload.get("rawTaskText");
    return new TaskContract(goal,
        acceptance == null || acceptance.isNull() ? null : acceptance,
        constraints == null || constraints.isNull() ? null : constraints,
        notes != null && notes.isTextual() ? notes.asString() : null,
        rawTaskText != null && rawTaskText.isTextual() ? rawTaskText.asString() : null);
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
