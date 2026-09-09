package org.folio.factory.devfactory.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.agent.AgentWorkerRegistry;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.engine.ExecutionEngine;
import org.folio.factory.core.engine.HitlGateOpener;
import org.folio.factory.core.engine.SubFlowInvoker;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.RetryPolicy;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.worker.recovery.RecoveryBundleStore;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.core.LocalSandboxService;
import org.folio.factory.sandbox.core.SandboxProperties;
import org.folio.factory.sandbox.harness.CodingHarness;
import org.folio.factory.sandbox.harness.HarnessConfig;
import org.folio.factory.sandbox.harness.HarnessReport;
import org.folio.factory.sandbox.harness.ModelReply;
import org.folio.factory.sandbox.harness.TaskContract;
import org.folio.factory.sandbox.harness.TaskOutcome;
import org.folio.factory.sandbox.harness.TokenUsage;
import org.folio.factory.sandbox.harness.ToolCall;
import org.folio.factory.sandbox.harness.ToolDispatcher;
import org.folio.factory.sandbox.harness.Trajectory;
import org.folio.factory.sandbox.tools.ApplyPatchTool;
import org.folio.factory.sandbox.tools.ExecTool;
import org.folio.factory.sandbox.tools.GitDiffTool;
import org.folio.factory.sandbox.tools.ListTool;
import org.folio.factory.sandbox.tools.ReadTool;
import org.folio.factory.sandbox.tools.TestTool;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * T25 durability/attempt-ordinal counterexamples, hermetic: the frozen T25
 * shapes (see {@link CodingWorkerDurabilityScenarioTest}'s Javadoc for the
 * honest seam disclosure this class mirrors) ported into the real pack
 * machinery as data. Every member drives the REAL worker, the REAL
 * {@link LocalSandboxService} over a real git fixture and the REAL
 * {@link RecoveryBundleStore}; the escalation member additionally drives a
 * real {@link ExecutionEngine} and a real {@link HitlDecisionService}
 * approval route. Honest seam disclosure: T25-C4 runs a real scripted model
 * (the escaped {@code rm -rf .git} export failure arrives as one exec turn),
 * while T25-C5 and T25-C6 use the scripted-harness seam — a mocked
 * {@link CodingHarness} performing the unique write and then throwing, or
 * creating the host-side persist obstruction — because a real model turn
 * cannot reach those failure points; the worker, sandbox, recovery store,
 * engine and HITL route stay real. Each member proves preservation with
 * bytes: bundle manifests/digests, the decoded workspace snapshot and a
 * fresh consumer checkout — never narration. The summary rows for the new
 * members are rendered by one shared method from the persisted
 * recovery-bundle manifest and trajectory data under the unchanged honesty
 * rules.
 */
@Tag("eval-pack")
class EvalPackHermeticDurabilityCounterexampleTest {

  private static final String FLOW_ID = "durability-eval-flow";
  private static final String STEP_ID = "coding";
  private static final long EXEC_TIMEOUT_SEC = 60L;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> DECLARED_OUTPUTS =
      List.of("patch.diff", "report.md", "trajectory.jsonl");
  private static final String WORKSPACE_SNAPSHOT_ARTIFACT = "workspace-snapshot.tar.gz.b64";

  private static final Path DURABILITY_SUMMARY =
      Path.of("target", "eval-pack-durability-summary.md");

  private static final String SUMMARY_HEADER = """
      # Dev Factory eval pack — T25 durability counterexamples (hermetic)

      provider: scripted ChatModel / scripted harness seam (hermetic); live-provider runs in this pack: none
      outcome source: persisted recovery-bundle manifest failure_reason (never test counts)
      tokens: numbers come from the persisted trajectory's final report record; `unknown` means no persisted usage record
      durations: measured wall time of the coding-worker execution(s)
      bundle: completeness and failure_reason as persisted in the run's recovery-bundle manifest.json

      | scenario | outcome | stop_reason | tokens_in | tokens_out | wall_ms | bundle |
      |---|---|---|---|---|---|---|
      """;

  private final FrontmatterCodec codec = new FrontmatterCodec();

  @TempDir
  Path root;

  /**
   * (T25-C4) Export failure as DATA: a real scripted model fixes the
   * greeting, makes one unique coding write, then destroys {@code .git} with
   * a plain exec turn — no diff-based capture can exist. The run must fail
   * naming the git diff failure and the bundle locator, publish an INCOMPLETE
   * EXPORT_FAILED bundle carrying the trajectory plus the worktree snapshot,
   * tear the workspace down only after that preservation, and a consumer
   * reconstructs the unique bytes AND the fixed greet.sh from the snapshot.
   */
  @Test
  void exportFailurePreservesUniqueBytesRecoverableFromBundleSnapshot() throws Exception {
    String taskId = "T25C4-EXPORT";
    String uniqueBytes = "c4-unique-recoverable-bytes\n";
    EvalPackScenarios pack = new EvalPackScenarios(root);
    Path fixture = pack.createGreetingFixture(taskId, "Goodbye, $1.");
    SandboxService sandbox = new LocalSandboxService(new SandboxProperties("local", null, null,
        root.resolve("sbx-root-" + taskId), Duration.ZERO, null), Clock.systemUTC());
    RecoveryBundleStore recovery = new RecoveryBundleStore(root.resolve("recovery"));
    GitDiffTool gitDiffTool = new GitDiffTool(sandbox);
    CodingHarness harness = new CodingHarness(
        new EvalPackScenarios.ScriptedModel(taskId, List.of(
            ModelReply.toolCalls(List.of(new ToolCall("t1", "apply_patch",
                EvalPackScenarios.patchArgs(EvalPackScenarios.GREETING_FIX))),
                new TokenUsage(1200, 48)),
            ModelReply.toolCalls(List.of(new ToolCall("t2", "exec",
                EvalPackScenarios.execArgs("cd repo && printf '" + uniqueBytes + "' > unique.txt"))),
                new TokenUsage(2100, 96)),
            ModelReply.toolCalls(List.of(new ToolCall("t3", "exec",
                EvalPackScenarios.execArgs("cd repo && rm -rf .git"))), new TokenUsage(1900, 64)),
            ModelReply.text("Fixed the greeting and recorded the change.",
                new TokenUsage(300, 175)))),
        new ToolDispatcher(new ReadTool(sandbox), new ListTool(sandbox),
            new ApplyPatchTool(sandbox), new ExecTool(sandbox), gitDiffTool,
            new TestTool(sandbox)),
        gitDiffTool, Clock.systemUTC(), HarnessConfig.defaults());
    UUID executionId = UUID.randomUUID();
    CodingWorker worker = new CodingWorker(sandbox, harness, codec, recovery);
    ObjectNode payload = JSON.createObjectNode();
    payload.put("taskId", taskId)
        .put("repoUrl", fixture.toAbsolutePath().toString())
        .put("baseBranch", "main")
        .put("branch", "dev/" + taskId)
        .put("goal", EvalPackScenarios.REGRESSION_GOAL);
    AgentContext context = new AgentContext(executionId, STEP_ID, Map.of(), payload, Map.of(),
        DECLARED_OUTPUTS, 1);

    long started = System.nanoTime();
    AgentExecutionException failure = null;
    try {
      worker.execute(context);
    } catch (AgentExecutionException e) {
      failure = e;
    }
    long wallMs = (System.nanoTime() - started) / 1_000_000L;

    RecoveryBundleStore.RecoveryBundle bundle =
        recovery.find(executionId, STEP_ID, 1).orElseThrow();
    assertThat(failure)
        .as("the destroyed .git export failure must surface as the worker's terminal exception")
        .isNotNull();
    assertThat(failure.getMessage())
        .contains("git diff failed")
        .as("the surfaced failure must name the bundle holding the preserved bytes")
        .contains(bundle.directory().toAbsolutePath().toString());
    assertThat(bundle.completeness())
        .as("nothing was accepted as complete on the export-failure path")
        .isEqualTo("INCOMPLETE");
    assertThat(bundle.failureReason()).isEqualTo("EXPORT_FAILED");
    assertThat(bundle.artifactNames())
        .as("no patch.diff or report.md can exist when the export itself failed")
        .doesNotContain("patch.diff", "report.md");
    assertThat(bundle.artifactNames())
        .contains(Trajectory.FILE_NAME, WORKSPACE_SNAPSHOT_ARTIFACT);
    assertBundleDigestsMatch(bundle);
    assertThat(Files.readString(bundle.directory().resolve(Trajectory.FILE_NAME),
        StandardCharsets.UTF_8))
        .as("the persisted trajectory carries the FAILED terminal outcome")
        .contains("\"task_outcome\":\"FAILED\"");

    // Consumer reconstruction (R3): decode and extract the bundled snapshot.
    Path extractDir = extractSnapshot(bundle.directory());
    assertThat(Files.readString(extractDir.resolve("unique.txt"), StandardCharsets.UTF_8))
        .as("the unique coding bytes must round-trip through the bundle snapshot")
        .isEqualTo(uniqueBytes);
    assertThat(Files.readString(extractDir.resolve("greet.sh"), StandardCharsets.UTF_8))
        .as("the model's greeting fix must survive in the snapshot")
        .isEqualTo("#!/bin/sh\necho \"Hello, $1.\"\n");
    assertThat(root.resolve("sbx-root-" + taskId)
        .resolve("sbx-" + executionId + "-attempt-1"))
        .as("teardown proceeded — only after the bundle became the preserved copy")
        .doesNotExist();

    String summary = renderDurabilitySummaryRows(new DurabilityRow(taskId, bundle, wallMs));
    assertThat(summary)
        .contains("| " + taskId + " | FAILED (EXPORT_FAILED) | COMPLETED | ");
    assertHonestSummaryRows(summary);
  }

  /**
   * (T25-C5) Terminal exception after coding work exists (scripted-harness
   * seam: a real model turn cannot reach the failure point): the harness
   * writes the trajectory and one unique coding write, then dies with a plain
   * RuntimeException. The execution-free worker must surface the
   * infrastructure AgentExecutionException with the original failure
   * preserved, publish an INCOMPLETE TERMINAL_EXCEPTION bundle whose snapshot
   * decodes back to the unique bytes, and tear the workspace down only after
   * that preservation.
   */
  @Test
  void terminalExceptionAfterCodingWorkPublishesPreservedBytesBeforeTeardown() throws Exception {
    String taskId = "T25C5-TERMINAL";
    String uniqueBytes = "c5-unique-terminal-exception-bytes\n";
    new EvalPackScenarios(root).createGreetingFixture(taskId, "Goodbye, $1.");
    SandboxService sandbox = new LocalSandboxService(new SandboxProperties("local", null, null,
        root.resolve("sbx-root-" + taskId), Duration.ZERO, null), Clock.systemUTC());
    RecoveryBundleStore recovery = new RecoveryBundleStore(root.resolve("recovery"));
    UUID executionId = UUID.randomUUID();
    CodingHarness harness = mock(CodingHarness.class);
    AtomicReference<String> workspaceAtWork = new AtomicReference<>();
    when(harness.run(any(), any(TaskContract.class), any())).thenAnswer(invocation -> {
      SandboxHandle handle = invocation.getArgument(0);
      Path workDir = invocation.getArgument(2);
      workspaceAtWork.set(handle.containerId());
      Files.createDirectories(workDir);
      Files.writeString(workDir.resolve(Trajectory.FILE_NAME),
          "{\"ts\":\"2026-09-09T10:00:00Z\",\"step\":1,\"tool\":\"exec\",\"ok\":true}\n");
      exec(sandbox, handle, "cd repo && printf '" + uniqueBytes + "' > unique.txt");
      throw new RuntimeException("terminal exception after coding work exists");
    });
    CodingWorker worker = new CodingWorker(sandbox, harness, codec, recovery);
    ObjectNode payload = JSON.createObjectNode();
    payload.put("taskId", taskId)
        .put("repoUrl", root.resolve(taskId).toAbsolutePath().toString())
        .put("baseBranch", "main")
        .put("branch", "dev/" + taskId)
        .put("goal", EvalPackScenarios.REGRESSION_GOAL);
    AgentContext context = new AgentContext(executionId, STEP_ID, Map.of(), payload, Map.of(),
        DECLARED_OUTPUTS, 1);

    long started = System.nanoTime();
    AgentExecutionException failure = null;
    try {
      worker.execute(context);
    } catch (AgentExecutionException e) {
      failure = e;
    }
    long wallMs = (System.nanoTime() - started) / 1_000_000L;

    assertThat(failure)
        .as("the execution-free worker surfaces the infrastructure failure")
        .isNotNull();
    assertThat(failure.getMessage())
        .contains("infrastructure failure")
        .contains("terminal exception after coding work exists");
    assertThat(failure.getCause())
        .as("the original terminal failure is preserved as the cause")
        .isNotNull()
        .hasMessage("terminal exception after coding work exists");
    assertThat(workspaceAtWork.get())
        .as("the run's sandbox workspace was keyed attempt-scoped from the start")
        .endsWith(executionId + "-attempt-1");

    RecoveryBundleStore.RecoveryBundle bundle =
        recovery.find(executionId, STEP_ID, 1).orElseThrow();
    assertThat(bundle.completeness()).isEqualTo("INCOMPLETE");
    assertThat(bundle.failureReason()).isEqualTo("TERMINAL_EXCEPTION");
    assertThat(bundle.artifactNames())
        .as("no patch.diff can exist when the harness died mid-run")
        .doesNotContain("patch.diff");
    assertThat(bundle.artifactNames())
        .contains(Trajectory.FILE_NAME, WORKSPACE_SNAPSHOT_ARTIFACT);
    assertBundleDigestsMatch(bundle);
    Path extractDir = extractSnapshot(bundle.directory());
    assertThat(Files.readString(extractDir.resolve("unique.txt"), StandardCharsets.UTF_8))
        .as("the bundled snapshot must decode back to the unique terminal-state bytes")
        .isEqualTo(uniqueBytes);
    assertThat(root.resolve("sbx-root-" + taskId)
        .resolve("sbx-" + executionId + "-attempt-1"))
        .as("teardown proceeded — the TERMINAL_EXCEPTION bundle is the preserved copy")
        .doesNotExist();

    String summary = renderDurabilitySummaryRows(new DurabilityRow(taskId, bundle, wallMs));
    assertThat(summary)
        .contains("| " + taskId + " | FAILED (TERMINAL_EXCEPTION) | unknown | ");
    assertHonestSummaryRows(summary);
  }

  /**
   * (T25-C6) Escalation-approved requeue under a NEW durable attempt ordinal
   * (map-r3 escalation identity): attempt 1 claims CHANGES_DELIVERED but its
   * declared-output write fails midway (host-side persist obstruction), so it
   * ends PERSIST_FAILED with the workspace retained under
   * {@code sbx-<exec>-attempt-1}; a maxAttempts-1 policy escalates; a REAL
   * HitlDecisionService approves the escalation review; the requeued run must
   * arrive as attempt 2 — its create must not sweep attempt 1's retained
   * workspace (unique bytes stay readable), attempt-1's published bundle must
   * stay byte-identical, attempt-2's own acknowledgement discards exactly
   * bundle-2, and attempt-1's patch applies in a fresh consumer checkout.
   */
  @Test
  void escalationApprovedRequeueUsesOrdinalAndPriorAttemptBytesSurvive() throws Exception {
    EngineRig rig = new EngineRig("T25C6-REQUEUE");
    String uniqueBytes = "c6-unique-retained-workspace-bytes\n";
    AtomicReference<String> attempt1Workspace = new AtomicReference<>();
    rig.stubAttempt(attempt1Workspace::set, (handle, workDir) -> {
      exec(rig.sandbox, handle,
          "cd repo && printf '" + uniqueBytes + "' > unique-b3.txt");
      // The map-r3 S10 obstruction: a directory sits where report.md should
      // land, so attempt 1 ends PERSIST_FAILED after patch.diff was written.
      try {
        Files.createDirectory(workDir.resolve("report.md"));
      } catch (java.io.IOException e) {
        throw new IllegalStateException("could not create the persist obstruction", e);
      }
    });

    long started = System.nanoTime();
    rig.engine().advance(rig.executionId());

    assertThat(rig.worker.attempts)
        .as("attempt 1 ran and failed on its output write")
        .containsExactly(1);
    assertThat(rig.execution().getStatus())
        .as("a maxAttempts-1 policy escalates the single PERSIST_FAILED attempt")
        .isEqualTo(ExecutionStatus.FAILED_ESCALATED);
    assertThat(rig.storedArtifacts())
        .as("the claimed-successful attempt 1 was never acknowledged into the store")
        .isEmpty();
    RecoveryBundleStore.RecoveryBundle first = rig.bundle(1).orElseThrow();
    assertThat(first.completeness()).isEqualTo("INCOMPLETE");
    assertThat(first.failureReason()).isEqualTo("PERSIST_FAILED");
    assertThat(first.artifactNames())
        .containsExactly("patch.diff", Trajectory.FILE_NAME);
    byte[] firstManifest = Files.readAllBytes(
        first.directory().resolve(RecoveryBundleStore.MANIFEST_FILE));
    byte[] firstPatch = Files.readAllBytes(first.directory().resolve("patch.diff"));
    assertThat(attempt1Workspace.get())
        .as("attempt 1's workspace was keyed attempt-scoped")
        .endsWith(rig.executionId() + "-attempt-1");
    assertThat(rig.workspaceDir(1))
        .as("attempt 1's workspace is retained under the uniform attempt-scoped key")
        .exists();

    // The real escalation-approval route resets only the retry budget.
    rig.approveEscalation();
    assertThat(rig.execution().getStatus())
        .as("an approved escalation requeues the execution as PENDING")
        .isEqualTo(ExecutionStatus.PENDING);
    rig.execution().setStatus(ExecutionStatus.RUNNING);
    AtomicReference<String> attempt2Workspace = new AtomicReference<>();
    rig.stubAttempt(attempt2Workspace::set, (handle, workDir) ->
        exec(rig.sandbox, handle, "cd repo && printf 'public class A2 {}\\n' > A2.java"));
    rig.engine().advance(rig.executionId());

    assertThat(rig.worker.attempts)
        .as("the escalation-approved requeue must run as attempt 2, never reuse attempt 1")
        .containsExactly(1, 2);
    assertThat(attempt2Workspace.get())
        .as("the requeued attempt created its own attempt-2 workspace")
        .endsWith(rig.executionId() + "-attempt-2");
    assertThat(rig.execution().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    assertThat(rig.workspaceDir(2))
        .as("the acknowledged requeued run tore down only its own workspace")
        .doesNotExist();
    assertThat(rig.workspaceDir(1))
        .as("the requeued create must never sweep attempt 1's retained workspace")
        .exists();
    assertThat(rig.workspaceDir(1).resolve("repo").resolve("unique-b3.txt"))
        .as("B3 probe: attempt 1's retained unique bytes survive the requeue verbatim")
        .hasContent(uniqueBytes);
    assertThat(rig.bundle(2))
        .as("the acknowledged requeued attempt discarded exactly its own bundle")
        .isEmpty();
    assertThat(rig.bundle(1))
        .as("attempt-1's bundle survives the requeued attempt")
        .isPresent();
    assertThat(Files.readAllBytes(
        first.directory().resolve(RecoveryBundleStore.MANIFEST_FILE)))
        .as("attempt-1's manifest must stay byte-identical across the requeue")
        .isEqualTo(firstManifest);
    assertThat(Files.readAllBytes(first.directory().resolve("patch.diff")))
        .as("attempt-1's patch must stay byte-identical across the requeue")
        .isEqualTo(firstPatch);

    // Consumer reconstruction: attempt-1's bundled patch applied to a fresh
    // checkout of the recorded base delivers the retained change.
    String bundlePatch = Files.readString(first.directory().resolve("patch.diff"),
        StandardCharsets.UTF_8);
    JsonNode manifest = JSON.readTree(Files.readString(
        first.directory().resolve(RecoveryBundleStore.MANIFEST_FILE), StandardCharsets.UTF_8));
    String synthesizedReport = codec.render(
        Map.of("base_revision", manifest.path("base_revision").asString()), "");
    EvalPackScenarios.PackRun run = new EvalPackScenarios.PackRun("T25C6-REQUEUE", null,
        new AgentResult(Map.of("patch.diff", bundlePatch), Map.of()), 0L, synthesizedReport, null);
    Path consumer = rig.pack.consumerCheckout(run, "T25C6-REQUEUE-bundle1", true);
    assertThat(Files.readString(consumer.resolve("unique-b3.txt"), StandardCharsets.UTF_8))
        .as("attempt-1's bundled patch delivers its unique change to a fresh consumer")
        .isEqualTo(uniqueBytes);

    long wallMs = (System.nanoTime() - started) / 1_000_000L;
    String summary = renderDurabilitySummaryRows(new DurabilityRow("T25C6-REQUEUE", first, wallMs));
    assertThat(summary)
        .contains("| T25C6-REQUEUE | FAILED (PERSIST_FAILED) | unknown | ");
    assertHonestSummaryRows(summary);
  }

  // ------------------------------------------------------------------
  // Shared assertions, snapshot reconstruction and the summary method.
  // ------------------------------------------------------------------

  private static void assertBundleDigestsMatch(RecoveryBundleStore.RecoveryBundle bundle)
      throws Exception {
    for (String name : bundle.artifactNames()) {
      assertThat(bundle.artifactSha256(name))
          .as("manifest digest for %s must match the bundled bytes", name)
          .isEqualTo(ArtifactStore.sha256(
              Files.readString(bundle.directory().resolve(name), StandardCharsets.UTF_8)));
    }
  }

  /**
   * Byte-level recovery check: base64-decode the bundled workspace snapshot,
   * untar it and return the extraction dir so members can read members back —
   * the snapshot must round-trip the sandbox worktree's unique bytes.
   */
  private static Path extractSnapshot(Path bundleDir) throws Exception {
    byte[] tarGz = Base64.getMimeDecoder().decode(
        Files.readString(bundleDir.resolve(WORKSPACE_SNAPSHOT_ARTIFACT), StandardCharsets.UTF_8));
    Path tarFile = Files.createTempFile("t25c-snapshot", ".tar.gz");
    Files.write(tarFile, tarGz);
    Path extractDir = Files.createTempDirectory("t25c-snapshot-extract");
    Process extract = new ProcessBuilder("tar", "xzf", tarFile.toString(),
        "-C", extractDir.toString()).redirectErrorStream(true).start();
    extract.getInputStream().readAllBytes();
    assertThat(extract.waitFor())
        .as("bundled workspace snapshot must extract as a tar.gz")
        .isZero();
    return extractDir;
  }

  private static void exec(SandboxService sandbox, SandboxHandle handle, String command) {
    var result = sandbox.exec(handle, command, EXEC_TIMEOUT_SEC);
    assertThat(result.ok())
        .as("scenario arrange command failed: %s%nstdout=%s%nstderr=%s",
            command, result.stdout(), result.stderr())
        .isTrue();
  }

  /** One summary row's persisted sources: the bundle and the measured wall time. */
  private record DurabilityRow(String id, RecoveryBundleStore.RecoveryBundle bundle, long wallMs) {
  }

  /**
   * The single summary method for the new members: renders their rows from
   * the PERSISTED recovery-bundle manifest (outcome, completeness, artifact
   * count) and the persisted trajectory's final report record (stop reason,
   * tokens when a usage record exists) — never from in-memory assertions —
   * under the unchanged honesty rules. Idempotent per scenario id, so the
   * members' test methods merge into one file across re-runs.
   */
  private String renderDurabilitySummaryRows(DurabilityRow... rows) throws IOException {
    StringBuilder freshRows = new StringBuilder();
    for (DurabilityRow row : rows) {
      JsonNode reportRecord = persistedReportRecord(row.bundle().directory());
      String stopReason =
          reportRecord == null ? "unknown" : reportRecord.path("stop_reason").asString("unknown");
      long tokensIn = reportRecord == null ? 0L : reportRecord.path("tokens_in").asLong();
      long tokensOut = reportRecord == null ? 0L : reportRecord.path("tokens_out").asLong();
      freshRows.append("| ").append(row.id())
          .append(" | FAILED (").append(row.bundle().failureReason()).append(")")
          .append(" | ").append(stopReason)
          .append(" | ").append(tokenCell(tokensIn, tokensOut, tokensIn))
          .append(" | ").append(tokenCell(tokensIn, tokensOut, tokensOut))
          .append(" | ").append(row.wallMs())
          .append(" | ").append(row.bundle().completeness()).append('/')
          .append(row.bundle().failureReason())
          .append(" (").append(row.bundle().artifactNames().size()).append(" artifacts)")
          .append(" |\n");
    }
    List<String> surviving = new ArrayList<>();
    if (Files.isRegularFile(DURABILITY_SUMMARY)) {
      Set<String> currentIds = new HashSet<>();
      for (DurabilityRow row : rows) {
        currentIds.add("| " + row.id() + " |");
      }
      for (String line : Files.readAllLines(DURABILITY_SUMMARY)) {
        if (line.startsWith("| ") && !line.startsWith("| scenario") && !line.startsWith("|---")
            && currentIds.stream().noneMatch(line::startsWith)) {
          surviving.add(line);
        }
      }
    }
    StringBuilder summary = new StringBuilder(SUMMARY_HEADER);
    for (String line : surviving) {
      summary.append(line).append('\n');
    }
    summary.append(freshRows);
    Files.createDirectories(DURABILITY_SUMMARY.getParent());
    Files.writeString(DURABILITY_SUMMARY, summary.toString());
    return summary.toString();
  }

  /**
   * The persisted trajectory's final report record (the harness's own
   * terminal accounting), or {@code null} when the run died before any
   * report record was written — the honesty boundary for stop_reason/tokens.
   */
  private static JsonNode persistedReportRecord(Path bundleDir) throws IOException {
    JsonNode last = null;
    for (String line : Files.readAllLines(bundleDir.resolve(Trajectory.FILE_NAME))) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode node = JSON.readTree(line);
      if (node.has("stop_reason")) {
        last = node;
      }
    }
    return last;
  }

  /** A run with no persisted usage record is rendered {@code unknown}. */
  private static String tokenCell(long tokensIn, long tokensOut, long cell) {
    return tokensIn == 0 && tokensOut == 0 ? "unknown" : Long.toString(cell);
  }

  /** Pins the unchanged honesty rules on every rendered data row. */
  private static void assertHonestSummaryRows(String summary) {
    List<String> rows = summary.lines()
        .filter(line -> line.startsWith("| ") && !line.startsWith("| scenario")
            && !line.startsWith("|---"))
        .toList();
    assertThat(rows).as("the durability summary must have data rows").isNotEmpty();
    for (String row : rows) {
      assertThat(row).as("every row must carry the persisted failure outcome, a persisted "
          + "or-unknown stop reason, tokens-or-unknown and a measured duration: %s", row)
          .matches("\\| T25C[0-9]-[A-Z0-9-]+ \\| FAILED \\([A-Z_]+\\) \\| ([A-Z_]+|unknown) \\| "
              + "(unknown|\\d+) \\| (unknown|\\d+) \\| \\d+ \\| "
              + "INCOMPLETE/[A-Z_]+ \\(\\d+ artifacts\\) \\|");
    }
    assertThat(summary)
        .as("a run with no persisted usage record must never show a fabricated token number")
        .doesNotContain("| unknown | 0 |")
        .doesNotContain("| 0 | unknown |");
  }

  /** Delegates to the real worker while recording the attempts it saw. */
  private static final class RecordingWorker implements AgentWorker {

    private final AgentWorker delegate;
    private final List<Integer> attempts = new ArrayList<>();

    RecordingWorker(AgentWorker delegate) {
      this.delegate = delegate;
    }

    @Override
    public String id() {
      return delegate.id();
    }

    @Override
    public AgentResult execute(AgentContext context) {
      attempts.add(context.attempt());
      return delegate.execute(context);
    }
  }

  /**
   * Compact in-class port of the frozen durability Harness wiring
   * (CodingWorkerDurabilityScenarioTest): a real ExecutionEngine over a real
   * AgentWorkerRegistry holding a real CodingWorker with a real
   * LocalSandboxService and a real file RecoveryBundleStore, a fake in-memory
   * StateManager whose attempt allocator never resets, mocked audit and
   * artifact store, and the real HitlDecisionService approval route over a
   * mocked review repository.
   */
  private final class EngineRig {

    final String taskId;
    final EvalPackScenarios pack = new EvalPackScenarios(root);
    final RecoveryBundleStore recovery = new RecoveryBundleStore(root.resolve("recovery"));
    final SandboxService sandbox;
    final FakeStateManager state;
    final AuditLog audit = mock(AuditLog.class);
    final ArtifactStore artifactStore = mock(ArtifactStore.class);
    final Map<String, Artifact> storedArtifacts = new LinkedHashMap<>();
    final CodingHarness harness = mock(CodingHarness.class);
    final RecordingWorker worker;

    EngineRig(String taskId) throws Exception {
      this.taskId = taskId;
      this.pack.createGreetingFixture(taskId, "Goodbye, $1.");
      this.sandbox = new LocalSandboxService(new SandboxProperties("local", null, null,
          root.resolve("sbx-root-" + taskId), Duration.ZERO, null), Clock.systemUTC());
      this.state = new FakeStateManager(triggerPayload());
      this.worker = new RecordingWorker(
          new CodingWorker(sandbox, harness, codec, recovery));
      acknowledgePutMarkdown();
    }

    void stubAttempt(Consumer<String> workspaceCapture,
        BiConsumer<SandboxHandle, Path> turns) {
      when(harness.run(any(), any(TaskContract.class), any())).thenAnswer(invocation -> {
        SandboxHandle handle = invocation.getArgument(0);
        Path workDir = invocation.getArgument(2);
        workspaceCapture.accept(handle.containerId());
        Files.createDirectories(workDir);
        Files.writeString(workDir.resolve(Trajectory.FILE_NAME),
            "{\"ts\":\"2026-09-09T10:00:00Z\",\"step\":1,\"tool\":\"exec\",\"ok\":true}\n");
        turns.accept(handle, workDir);
        return new HarnessReport(3, HarnessReport.Outcome.COMPLETED,
            HarnessReport.StopReason.COMPLETED, 2, 128L, 0, 0L, 0L,
            TaskOutcome.SUCCEEDED, TaskOutcome.Reason.CHANGES_DELIVERED, "");
      });
    }

    ExecutionEngine engine() {
      StepDescriptor step = new StepDescriptor(STEP_ID, StepType.AGENT, worker.id(),
          null, null, List.of(StepDescriptor.TRIGGER_INPUT), DECLARED_OUTPUTS, Map.of());
      FlowDescriptor flow = new FlowDescriptor(FLOW_ID, "Durability Eval Flow", "1.0.0",
          List.of(), null, null, List.of(step), new RetryPolicy(1, List.of(1L)));
      FlowRegistry flowRegistry = mock(FlowRegistry.class);
      when(flowRegistry.require(FLOW_ID)).thenReturn(flow);
      return new ExecutionEngine(flowRegistry,
          new AgentWorkerRegistry(List.of(worker), flowRegistry),
          state, artifactStore, audit, mock(HitlGateOpener.class), mock(SubFlowInvoker.class),
          List.of(), JSON, List.of(recovery));
    }

    /**
     * The real escalation-approval route: a real HitlDecisionService wired
     * over this rig's fake state and a mocked HitlReviewRepository holding
     * one PENDING escalation review at step index 0, deciding APPROVE exactly
     * as the review inbox would.
     */
    void approveEscalation() {
      StepDescriptor step = new StepDescriptor(STEP_ID, StepType.AGENT, worker.id(),
          null, null, List.of(StepDescriptor.TRIGGER_INPUT), DECLARED_OUTPUTS, Map.of());
      FlowDescriptor flow = new FlowDescriptor(FLOW_ID, "Durability Eval Flow", "1.0.0",
          List.of(), null, null, List.of(step), RetryPolicy.DEFAULT);
      FlowRegistry flowRegistry = mock(FlowRegistry.class);
      when(flowRegistry.require(FLOW_ID)).thenReturn(flow);
      HitlReviewRepository reviews = mock(HitlReviewRepository.class);
      HitlReview review = new HitlReview(executionId(), HitlGateOpener.ESCALATION_GATE_ID,
          0, "{}");
      when(reviews.findById(review.getId())).thenReturn(Optional.of(review));
      new HitlDecisionService(reviews, state, artifactStore, audit, flowRegistry,
          List.of(), JSON).decide(review.getId(), HitlDecision.APPROVE,
          "durability-reviewer", "approved: requeue the failed step", null);
    }

    void acknowledgePutMarkdown() {
      when(artifactStore.putMarkdown(any(UUID.class), anyString(), anyString(), anyString()))
          .thenAnswer(invocation -> {
            UUID executionId = invocation.getArgument(0);
            String name = invocation.getArgument(1);
            String content = invocation.getArgument(2);
            storedArtifacts.put(name, new Artifact(executionId, name, 1, "text/markdown",
                content, ArtifactStore.sha256(content), invocation.getArgument(3)));
            return null;
          });
    }

    UUID executionId() {
      return state.execution.getId();
    }

    PipelineExecution execution() {
      return state.execution;
    }

    Map<String, Artifact> storedArtifacts() {
      return storedArtifacts;
    }

    Path workspaceDir(int attempt) {
      return root.resolve("sbx-root-" + taskId)
          .resolve("sbx-" + executionId() + "-attempt-" + attempt);
    }

    Optional<RecoveryBundleStore.RecoveryBundle> bundle(int attempt) {
      return recovery.find(executionId(), STEP_ID, attempt);
    }

    private String triggerPayload() {
      ObjectNode payload = JSON.createObjectNode();
      payload.put("taskId", taskId)
          .put("repoUrl", root.resolve(taskId).toAbsolutePath().toString())
          .put("baseBranch", "main")
          .put("branch", "dev/" + taskId)
          .put("goal", EvalPackScenarios.REGRESSION_GOAL);
      return payload.toString();
    }
  }

  /** In-memory StateManager: one shared execution plus retry bookkeeping. */
  private static final class FakeStateManager extends StateManager {

    private final PipelineExecution execution;
    private final Map<String, Integer> retries = new LinkedHashMap<>();
    private final Map<String, Integer> attemptOrdinals = new LinkedHashMap<>();

    FakeStateManager(String triggerPayloadJson) {
      super(null, null, null);
      this.execution = new PipelineExecution(FLOW_ID, "1.0.0", triggerPayloadJson);
      this.execution.setStatus(ExecutionStatus.RUNNING);
    }

    @Override
    public PipelineExecution get(UUID executionId) {
      return execution;
    }

    @Override
    public void heartbeat(UUID executionId) {
    }

    @Override
    public int retryCount(UUID executionId, String stepId) {
      return retries.getOrDefault(stepId, 0);
    }

    @Override
    public int incrementRetry(UUID executionId, String stepId) {
      return retries.merge(stepId, 1, Integer::sum);
    }

    @Override
    public void resetRetry(UUID executionId, String stepId) {
      // Approving an escalation resets ONLY the retry budget; the ascending
      // attempt ordinal below is deliberately untouched by the reset.
      retries.remove(stepId);
    }

    /**
     * Mirrors the real allocator — read→merge+1→return — that never resets,
     * so an escalation-approved requeue can never reuse a prior attempt's
     * ordinal (the engine consumes this as the sole attempt identity source).
     */
    @Override
    public int nextAttempt(UUID executionId, String stepId) {
      return attemptOrdinals.merge(stepId, 1, Integer::sum);
    }

    @Override
    public void scheduleRetry(UUID executionId, long backoffSeconds, String errorMessage) {
      execution.setStatus(ExecutionStatus.PENDING);
      execution.setErrorMessage(errorMessage);
    }

    @Override
    public PipelineExecution transition(UUID executionId, ExecutionStatus newStatus,
        Map<String, ?> auditDetail) {
      execution.setStatus(newStatus);
      return execution;
    }

    @Override
    public boolean advanceStep(UUID executionId, int expectedStepIndex,
        ExecutionStatus expectedStatus) {
      if (execution.getStatus() != expectedStatus
          || execution.getCurrentStepIndex() != expectedStepIndex) {
        return false;
      }
      execution.setCurrentStepIndex(expectedStepIndex + 1);
      return true;
    }
  }
}
