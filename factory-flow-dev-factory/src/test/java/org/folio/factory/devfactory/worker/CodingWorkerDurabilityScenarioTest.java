package org.folio.factory.devfactory.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.agent.AgentWorkerRegistry;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEventType;
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
import org.folio.factory.sandbox.harness.ChatModelAdapter;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T25 S03/S04/S08/S09 frozen durability suite (R1–R5): ten self-contained
 * scenarios drive the REAL worker→engine ordering — a real {@link ExecutionEngine}
 * advancing a real {@link CodingWorker} over a real local sandbox and a real
 * git greeting fixture — with workDir ABSENT from the step config (the worker
 * must take {@code Files.createTempDirectory}). Honest seam disclosure:
 * scenarios 1, 2 and 6 run a real scripted model, but scenarios 3–5 and 7–10
 * use the scripted-harness seam — a mocked {@link CodingHarness} standing in
 * for the model loop — and mocked/fake orchestration services throughout:
 * {@link AuditLog} and {@link ArtifactStore} are mocks, state runs on a fake
 * in-memory {@link StateManager}, and FlowRegistry/HitlGateOpener/
 * SubFlowInvoker are mocks. {@link ArtifactStore} is the only injected
 * behavior boundary: an in-memory fake for acknowledged scenarios, a
 * {@code putMarkdown} outage for the downstream-failure scenario, which the
 * engine ordering guarantees fires strictly AFTER {@code worker.execute}
 * returned. Every scenario asserts real bytes: bundle manifests/digests, the
 * exported diff applied in a fresh consumer checkout, and check.sh flipping
 * red to green — never narration.
 */
class CodingWorkerDurabilityScenarioTest {

  private static final String FLOW_ID = "durability-flow";
  private static final String STEP_ID = "coding";
  private static final long EXEC_TIMEOUT_SEC = 60L;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> DECLARED_OUTPUTS =
      List.of("patch.diff", "report.md", "trajectory.jsonl");
  private static final String WORKSPACE_SNAPSHOT_ARTIFACT = "workspace-snapshot.tar.gz.b64";

  private final FrontmatterCodec codec = new FrontmatterCodec();

  @TempDir
  Path root;

  /** (1) Default success: store acknowledges; consumer reconstructs from the STORE. */
  @Test
  void defaultSuccessIsAcknowledgedDiscardedAndReconstructableFromTheStore() throws Exception {
    Harness harness = new Harness("S1-DEFAULT-SUCCESS", greetingFixScript());

    harness.engine().advance(harness.executionId());

    assertThat(harness.execution().getStatus())
        .as("a fully persisted single-step flow reaches COMPLETED")
        .isEqualTo(ExecutionStatus.COMPLETED);
    assertThat(harness.workspaceDir())
        .as("the acknowledged run tore the sandbox workspace down")
        .doesNotExist();
    assertThat(harness.bundle(1))
        .as("the acknowledged attempt's bundle was discardAcknowledged'd")
        .isEmpty();
    assertThat(harness.persistOrder())
        .as("every declared output reached the artifact store exactly once")
        .containsExactlyInAnyOrderElementsOf(DECLARED_OUTPUTS);

    String report = harness.storedContent("report.md");
    String patch = harness.storedContent("patch.diff");
    assertRedAtBase(harness, report, patch);
    EvalPackScenarios.ShellResult green =
        harness.shell(harness.consumerFrom("S1-DEFAULT-SUCCESS", report, patch, "s1-fixed", true));
    assertThat(green.exitCode())
        .as("stored patch.diff applied to a checkout of the stored base must pass check.sh")
        .isZero();
    assertThat(green.stdout()).contains("OK: greet.sh prints 'Hello, World.'");
  }

  /**
   * (2) Downstream persistence failure: putMarkdown throws strictly after the
   * worker returned; the COMPLETE bundle survives the already-run teardown and
   * a fresh consumer reconstructs the exact change from the BUNDLE.
   */
  @Test
  void downstreamPersistenceFailurePreservesCompleteBundleAndPropagatesLocator() throws Exception {
    Harness harness = new Harness("S2-STORE-OUTAGE", greetingFixScript());
    harness.failPutMarkdown();
    harness.engine(harness.worker(), new RetryPolicy(1, List.of(1L)))
        .advance(harness.executionId());

    RecoveryBundleStore.RecoveryBundle bundle = harness.bundle(1).orElseThrow();
    String locator = bundle.directory().toAbsolutePath().toString();
    assertThat(bundle.completeness()).isEqualTo("COMPLETE");
    assertThat(bundle.artifactNames())
        .containsExactlyInAnyOrderElementsOf(DECLARED_OUTPUTS);
    assertBundleDigestsMatch(bundle);
    assertThat(harness.workspaceDir())
        .as("worker teardown already ran before the persistence failure, yet the "
            + "bundle — not the workspace — is the preserved copy")
        .doesNotExist();
    assertThat(harness.storedArtifacts())
        .as("nothing was acknowledged into the artifact store")
        .isEmpty();
    verify(harness.audit(), never()).record(eq(harness.executionId()),
        eq(AuditEventType.STEP_COMPLETED), eq(STEP_ID), any());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> failed = ArgumentCaptor.forClass((Class) Map.class);
    verify(harness.audit()).record(eq(harness.executionId()), eq(AuditEventType.STEP_FAILED),
        eq(STEP_ID), failed.capture());
    assertThat(String.valueOf(failed.getValue().get("error")))
        .as("STEP_FAILED must carry the worker-published recovery_locator")
        .contains(locator);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> escalated = ArgumentCaptor.forClass((Class) Map.class);
    verify(harness.audit()).record(eq(harness.executionId()), eq(AuditEventType.ESCALATED),
        eq(STEP_ID), escalated.capture());
    assertThat(String.valueOf(escalated.getValue().get("error")))
        .as("the same locator-bearing message rides the escalation audit")
        .contains(locator);
    assertThat(harness.execution().getStatus())
        .as("a maxAttempts-1 retry policy escalates after the single failure")
        .isEqualTo(ExecutionStatus.FAILED_ESCALATED);

    String bundleReport = Files.readString(bundle.directory().resolve("report.md"),
        StandardCharsets.UTF_8);
    String bundlePatch = Files.readString(bundle.directory().resolve("patch.diff"),
        StandardCharsets.UTF_8);
    EvalPackScenarios.ShellResult green = harness.shell(harness.consumerFrom(
        "S2-STORE-OUTAGE", bundleReport, bundlePatch, "s2-bundle-fixed", true));
    assertThat(green.exitCode())
        .as("the BUNDLE's patch.diff (not the store) must reconstruct the exact change")
        .isZero();
    assertThat(green.stdout()).contains("OK: greet.sh prints 'Hello, World.'");
  }

  /** (3) Export failure: INCOMPLETE EXPORT_FAILED bundle published before teardown. */
  @Test
  void exportFailurePublishesIncompleteBundleBeforeWorkspaceTeardown() throws Exception {
    Harness harness = new Harness("S3-EXPORT-FAILED");
    harness.stubMutationTurns((handle, workDir) -> {
      // S04 blind-spot fix: a unique coding write exists before .git is
      // destroyed — the run has unique bytes that no diff can recover.
      exec(harness.sandbox(), handle,
          "cd repo && printf 's3-unique-coding-write\\n' > unique.txt");
      exec(harness.sandbox(), handle, "cd repo && rm -rf .git");
    });
    ExecutionEngine engine =
        harness.engine(harness.worker(), new RetryPolicy(1, List.of(1L)));

    engine.advance(harness.executionId());

    assertThat(harness.workspaceDir())
        .as("teardown proceeded — only after the bundle became the preserved copy")
        .doesNotExist();
    verify(harness.audit(), never()).record(eq(harness.executionId()),
        eq(AuditEventType.STEP_COMPLETED), eq(STEP_ID), any());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> failed = ArgumentCaptor.forClass((Class) Map.class);
    verify(harness.audit()).record(eq(harness.executionId()), eq(AuditEventType.STEP_FAILED),
        eq(STEP_ID), failed.capture());
    assertThat(String.valueOf(failed.getValue().get("error")))
        .contains("git diff failed");
    assertThat(harness.execution().getStatus()).isEqualTo(ExecutionStatus.FAILED_ESCALATED);
    assertThat(harness.storedArtifacts()).isEmpty();

    RecoveryBundleStore.RecoveryBundle bundle = harness.bundle(1).orElseThrow();
    assertThat(bundle.completeness()).isEqualTo("INCOMPLETE");
    assertThat(bundle.failureReason()).isEqualTo("EXPORT_FAILED");
    assertThat(bundle.artifactNames())
        .as("no patch.diff can exist when the export itself failed")
        .doesNotContain("patch.diff");
    assertThat(bundle.artifactNames())
        .contains(Trajectory.FILE_NAME);
    assertThat(bundle.artifactNames())
        .as("the unique worktree bytes ride the bundle as one snapshot artifact")
        .contains(WORKSPACE_SNAPSHOT_ARTIFACT);
    assertThat(decodeSnapshotMember(bundle.directory(), "unique.txt"))
        .as("the bundled snapshot must decode back to the unique coding bytes")
        .isEqualTo("s3-unique-coding-write\n");
    assertBundleDigestsMatch(bundle);
  }

  /**
   * (4) Partial output: persistOutputs obstructed midway; the bundle is the
   * strict written subset and nothing downstream can treat it as accepted.
   */
  @Test
  void partialPersistBundleCarriesWrittenSubsetOnlyAndIsNeverAccepted() throws Exception {
    Harness harness = new Harness("S4-PARTIAL-PERSIST");
    harness.stubMutationTurns((handle, workDir) -> {
      exec(harness.sandbox(), handle,
          "cd repo && printf 'public class NewUtil {}\\n' > NewUtil.java");
      // A non-writable obstruction where report.md should land: patch.diff is
      // already on disk, report.md never will be.
      try {
        Files.createDirectory(workDir.resolve("report.md"));
      } catch (java.io.IOException e) {
        throw new IllegalStateException("could not create the persist obstruction", e);
      }
    });

    harness.engine(harness.worker(), new RetryPolicy(1, List.of(1L)))
        .advance(harness.executionId());

    verify(harness.audit(), never()).record(eq(harness.executionId()),
        eq(AuditEventType.STEP_COMPLETED), eq(STEP_ID), any());
    assertThat(harness.storedArtifacts())
        .as("no stored artifact set — no patch acceptance occurred")
        .isEmpty();
    assertThat(harness.execution().getStatus()).isEqualTo(ExecutionStatus.FAILED_ESCALATED);
    assertThat(harness.workspaceDir())
        .as("the sandbox holding the model's unique change must be retained")
        .exists();

    RecoveryBundleStore.RecoveryBundle bundle = harness.bundle(1).orElseThrow();
    assertThat(bundle.completeness()).isEqualTo("INCOMPLETE");
    assertThat(bundle.failureReason()).isEqualTo("PERSIST_FAILED");
    assertThat(bundle.artifactNames())
        .as("exactly the written subset: patch.diff and the trajectory")
        .containsExactly("patch.diff", Trajectory.FILE_NAME);
    assertThat(bundle.artifactNames())
        .as("the missing artifact must be absent from the artifacts array")
        .doesNotContain("report.md");
    assertBundleDigestsMatch(bundle);
  }

  /**
   * (5) Retry preservation: attempt 1 fails downstream (bundle survives);
   * attempt 2 is acknowledged and discards ONLY attempt-2's bundle; the
   * worker received attempt 1 then 2.
   */
  @Test
  void retryPreservesAttemptOneBundleAndDiscardsOnlyTheAcknowledgedAttempt() throws Exception {
    Harness harness = new Harness("S5-RETRY");
    harness.stubMutationTurns((handle, workDir) ->
        exec(harness.sandbox(), handle, "cd repo && printf 'public class A1 {}\\n' > A1.java"));
    harness.failPutMarkdown();
    RecordingWorker worker = new RecordingWorker(harness.worker());
    ExecutionEngine engine = harness.engine(worker, new RetryPolicy(3, List.of(1L, 1L, 1L)));

    engine.advance(harness.executionId());
    RecoveryBundleStore.RecoveryBundle first = harness.bundle(1).orElseThrow();
    byte[] firstManifest = Files.readAllBytes(
        first.directory().resolve(RecoveryBundleStore.MANIFEST_FILE));
    byte[] firstPatch = Files.readAllBytes(first.directory().resolve("patch.diff"));
    String firstLocator = String.valueOf(worker.results.get(0).metrics().get("recovery_locator"));
    assertThat(harness.execution().getErrorMessage())
        .as("the retry path persists the locator-bearing failure as errorMessage")
        .contains(firstLocator);

    harness.execution().setStatus(ExecutionStatus.RUNNING);
    harness.acknowledgePutMarkdown();
    harness.stubMutationTurns((handle, workDir) ->
        exec(harness.sandbox(), handle, "cd repo && printf 'public class A2 {}\\n' > A2.java"));
    engine.advance(harness.executionId());

    assertThat(worker.attempts)
        .as("the worker received attempt 1 then attempt 2")
        .containsExactly(1, 2);
    assertThat(harness.execution().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    String secondLocator = String.valueOf(worker.results.get(1).metrics().get("recovery_locator"));
    assertThat(secondLocator)
        .as("attempt 2's bundle lives in a distinct directory")
        .isNotEqualTo(firstLocator)
        .endsWith("attempt-2");
    assertThat(firstLocator).endsWith("attempt-1");
    assertThat(harness.bundle(2))
        .as("attempt-2's acknowledged cleanup discarded exactly attempt-2")
        .isEmpty();
    assertThat(harness.bundle(1))
        .as("attempt-1's bundle survives attempt-2's acknowledgment")
        .isPresent();
    assertThat(Files.readAllBytes(
        first.directory().resolve(RecoveryBundleStore.MANIFEST_FILE)))
        .as("attempt-1's manifest must stay byte-identical")
        .isEqualTo(firstManifest);
    assertThat(Files.readAllBytes(first.directory().resolve("patch.diff")))
        .as("attempt-1's patch must stay byte-identical")
        .isEqualTo(firstPatch);
  }

  /**
   * (6) Acknowledged cleanup: after success every producer location is gone
   * (workspace, temp workDir state, bundle) yet the consumer side still
   * reconstructs and passes check.sh from the acknowledged store.
   */
  @Test
  void acknowledgedCleanupRemovesEveryProducerCopyYetConsumerStillReconstructs() throws Exception {
    Harness harness = new Harness("S6-ACK-CLEANUP", greetingFixScript());

    harness.engine().advance(harness.executionId());

    assertThat(harness.execution().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    assertThat(harness.workspaceDir())
        .as("workspace gone")
        .doesNotExist();
    assertThat(harness.bundle(1))
        .as("temp producer state gone — the bundle dir was removed by discardAcknowledged")
        .isEmpty();

    Optional<Artifact> report = harness.latestArtifact("report.md");
    Optional<Artifact> patch = harness.latestArtifact("patch.diff");
    assertThat(report).as("stored report.md still retrievable").isPresent();
    assertThat(patch).as("stored patch.diff still retrievable").isPresent();
    String reportMd = report.orElseThrow().getContent();
    String patchDiff = patch.orElseThrow().getContent();
    assertThat(reportMd).contains("base_revision");

    assertRedAtBase(harness, reportMd, patchDiff);
    EvalPackScenarios.ShellResult green = harness.shell(harness.consumerFrom(
        "S6-ACK-CLEANUP", reportMd, patchDiff, "s6-fixed", true));
    assertThat(green.exitCode()).isZero();
    assertThat(green.stdout()).contains("OK: greet.sh prints 'Hello, World.'");
  }

  /**
   * (7) Export failure must bundle the unique repository bytes: a
   * git-independent worktree-snapshot artifact whose bytes survive the retry.
   * Attempt 1 destroys .git after a unique coding write (exactly the probe
   * shape where a diff-based capture is impossible); attempt 2 completes and
   * discards only its own bundle; attempt 1's manifest and snapshot stay
   * byte-identical afterward.
   */
  @Test
  void exportFailureBundlesUniqueRepositoryBytesThatSurviveRetry() throws Exception {
    Harness harness = new Harness("S7-EXPORT-SNAPSHOT");
    String uniqueBytes = "s7-unique-recoverable-bytes\n";
    harness.stubMutationTurns((handle, workDir) -> {
      exec(harness.sandbox(), handle,
          "cd repo && printf '" + uniqueBytes + "' > unique.txt");
      exec(harness.sandbox(), handle, "cd repo && rm -rf .git");
    });
    ExecutionEngine engine =
        harness.engine(harness.worker(), new RetryPolicy(2, List.of(1L, 1L)));

    engine.advance(harness.executionId());

    RecoveryBundleStore.RecoveryBundle first = harness.bundle(1).orElseThrow();
    byte[] firstManifest = Files.readAllBytes(
        first.directory().resolve(RecoveryBundleStore.MANIFEST_FILE));
    byte[] firstSnapshot = Files.readAllBytes(
        first.directory().resolve(WORKSPACE_SNAPSHOT_ARTIFACT));
    assertThat(first.completeness()).isEqualTo("INCOMPLETE");
    assertThat(first.failureReason()).isEqualTo("EXPORT_FAILED");
    assertThat(first.artifactNames())
        .as("no patch.diff can exist when the export itself failed")
        .doesNotContain("patch.diff");
    assertThat(first.artifactNames())
        .contains(Trajectory.FILE_NAME, WORKSPACE_SNAPSHOT_ARTIFACT);
    assertBundleDigestsMatch(first);
    assertThat(decodeSnapshotMember(first.directory(), "unique.txt"))
        .as("the bundled snapshot must decode back to the unique bytes")
        .isEqualTo(uniqueBytes);
    assertThat(harness.workspaceDir(1))
        .as("teardown proceeded — the bundle with the snapshot is the preserved copy")
        .doesNotExist();
    assertThat(String.valueOf(harness.execution().getErrorMessage()))
        .as("the failure message carries the frozen reason and the bundle locator")
        .contains("git diff failed")
        .contains(first.directory().toAbsolutePath().toString());

    harness.execution().setStatus(ExecutionStatus.RUNNING);
    harness.stubMutationTurns((handle, workDir) ->
        exec(harness.sandbox(), handle, "cd repo && printf 'public class A2 {}\\n' > A2.java"));
    engine.advance(harness.executionId());

    assertThat(harness.execution().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    assertThat(harness.bundle(2))
        .as("the acknowledged attempt-2 bundle was discarded")
        .isEmpty();
    assertThat(harness.bundle(1))
        .as("attempt-1's bundle survives attempt-2's acknowledgment")
        .isPresent();
    assertThat(Files.readAllBytes(
        first.directory().resolve(RecoveryBundleStore.MANIFEST_FILE)))
        .as("attempt-1's manifest must stay byte-identical after the retry")
        .isEqualTo(firstManifest);
    assertThat(Files.readAllBytes(
        first.directory().resolve(WORKSPACE_SNAPSHOT_ARTIFACT)))
        .as("attempt-1's snapshot bytes must stay byte-identical after the retry")
        .isEqualTo(firstSnapshot);
  }

  /**
   * (8) Terminal exception after coding work exists: the harness itself dies
   * (RuntimeException, the Trajectory close/append shape) after the worktree
   * holds unique bytes; the run must still publish an INCOMPLETE
   * TERMINAL_EXCEPTION bundle carrying the workspace snapshot — and only
   * that established preservation lets the sandbox teardown run.
   */
  @Test
  void terminalExceptionAfterCodingWorkPublishesIncompleteBundleWithUniqueBytes() throws Exception {
    Harness harness = new Harness("S8-TERMINAL-EXCEPTION");
    String uniqueBytes = "s8-unique-terminal-exception-bytes\n";
    harness.stubMutationTurnsThenThrow((handle, workDir) ->
        exec(harness.sandbox(), handle,
            "cd repo && printf '" + uniqueBytes + "' > unique.txt"));
    ExecutionEngine engine =
        harness.engine(harness.worker(), new RetryPolicy(1, List.of(1L)));

    engine.advance(harness.executionId());

    verify(harness.audit(), never()).record(eq(harness.executionId()),
        eq(AuditEventType.STEP_COMPLETED), eq(STEP_ID), any());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> failed = ArgumentCaptor.forClass((Class) Map.class);
    verify(harness.audit()).record(eq(harness.executionId()), eq(AuditEventType.STEP_FAILED),
        eq(STEP_ID), failed.capture());
    assertThat(String.valueOf(failed.getValue().get("error")))
        .as("a successful preserve surfaces the original terminal failure")
        .contains("infrastructure failure");
    assertThat(harness.execution().getStatus()).isEqualTo(ExecutionStatus.FAILED_ESCALATED);
    assertThat(harness.storedArtifacts()).isEmpty();

    RecoveryBundleStore.RecoveryBundle bundle = harness.bundle(1).orElseThrow();
    assertThat(bundle.completeness()).isEqualTo("INCOMPLETE");
    assertThat(bundle.failureReason()).isEqualTo("TERMINAL_EXCEPTION");
    assertThat(bundle.artifactNames())
        .as("no patch.diff can exist when the harness died mid-run")
        .doesNotContain("patch.diff");
    assertThat(bundle.artifactNames())
        .contains(Trajectory.FILE_NAME, WORKSPACE_SNAPSHOT_ARTIFACT);
    assertBundleDigestsMatch(bundle);
    assertThat(decodeSnapshotMember(bundle.directory(), "unique.txt"))
        .as("the bundled snapshot must decode back to the unique terminal-state bytes")
        .isEqualTo(uniqueBytes);
    assertThat(harness.workspaceDir())
        .as("teardown proceeded — the TERMINAL_EXCEPTION bundle is the preserved copy")
        .doesNotExist();
  }

  /**
   * (9) Terminal exception whose preserve cannot publish: the recovery root
   * is non-writable, so no bundle can exist; the run fails closed — both the
   * sandbox workspace and the temp workDir are retained and the surfaced
   * failure is the fail-closed TERMINAL_EXCEPTION preserve error, never a
   * raw preserve-path exception.
   */
  @Test
  void terminalExceptionFailClosedRetainsWorkspaceWhenPublicationFails() throws Exception {
    Harness harness = new Harness("S9-FAIL-CLOSED");
    harness.stubMutationTurnsThenThrow((handle, workDir) ->
        exec(harness.sandbox(), handle,
            "cd repo && printf 's9-unique-fail-closed-bytes\\n' > unique.txt"));
    ExecutionEngine engine =
        harness.engine(harness.worker(), new RetryPolicy(1, List.of(1L)));
    Path lockedRoot = Files.createDirectories(harness.recoveryRoot());
    Files.setPosixFilePermissions(lockedRoot, PosixFilePermissions.fromString("r-xr-x---"));
    try {
      engine.advance(harness.executionId());
    } finally {
      Files.setPosixFilePermissions(lockedRoot, PosixFilePermissions.fromString("rwxr-x---"));
    }

    verify(harness.audit(), never()).record(eq(harness.executionId()),
        eq(AuditEventType.STEP_COMPLETED), eq(STEP_ID), any());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> failed = ArgumentCaptor.forClass((Class) Map.class);
    verify(harness.audit()).record(eq(harness.executionId()), eq(AuditEventType.STEP_FAILED),
        eq(STEP_ID), failed.capture());
    assertThat(String.valueOf(failed.getValue().get("error")))
        .as("the surfaced failure must identify the TERMINAL_EXCEPTION preserve failing closed")
        .contains("TERMINAL_EXCEPTION")
        .contains("retaining");
    assertThat(harness.execution().getStatus()).isEqualTo(ExecutionStatus.FAILED_ESCALATED);
    assertThat(harness.storedArtifacts()).isEmpty();
    assertThat(harness.workspaceDir())
        .as("without a published bundle no destructive cleanup may run")
        .exists();
    assertThat(harness.capturedWorkDir())
        .as("the temp workDir must also be retained when publication failed")
        .exists();
    assertThat(harness.bundle(1))
        .as("nothing may be published as 'preserved' when publication failed")
        .isEmpty();
  }

  /**
   * (10) Retry after attempt 1's first output-write failure keeps attempt 1's
   * retained unique bytes (map-r3 identity — R3's "preserve already produced
   * unique data"). Attempt 1's declared-output write fails midway
   * (PERSIST_FAILED), so its sandbox workspace — holding the model's unique
   * coding bytes — is RETAINED under the uniform attempt-scoped key
   * {@code sbx-<exec>-attempt-1}; attempt 2's create sweeps only its own
   * {@code sbx-<exec>-attempt-2} workspace, and the B3 probe — reading
   * attempt 1's unique bytes back from the retained workspace after attempt 2
   * ran — stays byte-identical. Nothing ever reclaims attempt 1's copy: there
   * is no retention TTL, only explicit tested retention (R4).
   */
  @Test
  void retryAfterFirstOutputWriteFailureKeepsAttemptOneUniqueBytes() throws Exception {
    Harness harness = new Harness("S10-RETRY-RETAINED-WORKSPACE");
    String uniqueBytes = "b3-unique-retained-workspace-bytes\n";
    harness.stubMutationTurns((handle, workDir) -> {
      exec(harness.sandbox(), handle,
          "cd repo && printf '" + uniqueBytes + "' > unique-b3.txt");
      // The FIRST output-write failure: a directory sits where report.md
      // should land, so attempt 1 ends PERSIST_FAILED with the workspace
      // retained for recovery.
      try {
        Files.createDirectory(workDir.resolve("report.md"));
      } catch (java.io.IOException e) {
        throw new IllegalStateException("could not create the persist obstruction", e);
      }
    });
    RecordingWorker worker = new RecordingWorker(harness.worker());
    ExecutionEngine engine = harness.engine(worker, new RetryPolicy(2, List.of(1L, 1L)));

    engine.advance(harness.executionId());

    assertThat(worker.attempts)
        .as("attempt 1 ran and failed on its output write")
        .containsExactly(1);
    assertThat(harness.bundle(1).orElseThrow().failureReason())
        .isEqualTo("PERSIST_FAILED");
    assertThat(harness.workspaceDir(1))
        .as("attempt 1's workspace is retained under the uniform attempt-scoped key")
        .exists();

    harness.execution().setStatus(ExecutionStatus.RUNNING);
    harness.stubMutationTurns((handle, workDir) ->
        exec(harness.sandbox(), handle, "cd repo && printf 'public class A2 {}\\n' > A2.java"));
    engine.advance(harness.executionId());

    assertThat(worker.attempts)
        .as("the retry ran as attempt 2")
        .containsExactly(1, 2);
    assertThat(harness.execution().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    assertThat(harness.workspaceDir(2))
        .as("the acknowledged attempt 2 tore down only its own workspace")
        .doesNotExist();
    assertThat(harness.workspaceDir(1))
        .as("attempt 2's create must never sweep attempt 1's retained workspace")
        .exists();
    assertThat(harness.workspaceDir(1).resolve("repo").resolve("unique-b3.txt"))
        .as("B3 probe: attempt 1's retained unique bytes survive attempt 2 verbatim")
        .hasContent(uniqueBytes);
  }

  /**
   * (11) Escalation-approved requeue takes the NEXT attempt ordinal (map-r3
   * escalation identity — R3/R5). Attempt 1 fails PERSIST_FAILED after
   * patch.diff was written (the map-r3 S10 obstruction shape) under a
   * maxAttempts-1 policy, so the run escalates; a REAL
   * {@link HitlDecisionService} then approves the escalation review — only
   * the retry budget resets — and the requeued run must arrive as attempt 2,
   * never a reused attempt 1: attempt 2's create must not sweep the retained
   * {@code sbx-<exec>-attempt-1} workspace (its unique repo bytes stay
   * verifiable), must not overwrite attempt-1's published bundle, and
   * attempt-2's own acknowledgement discards exactly bundle-2.
   */
  @Test
  void escalationApprovedRequeueUsesNewAttemptOrdinalAndPreservesRetainedWorkspace()
      throws Exception {
    Harness harness = new Harness("S11-ESCALATION-REQUEUE");
    String uniqueBytes = "b4-escalation-retained-workspace-bytes\n";
    harness.stubMutationTurns((handle, workDir) -> {
      exec(harness.sandbox(), handle,
          "cd repo && printf '" + uniqueBytes + "' > unique-b4.txt");
      // The map-r3 S10 shape: a directory sits where report.md should land,
      // so attempt 1 ends PERSIST_FAILED after patch.diff was written.
      try {
        Files.createDirectory(workDir.resolve("report.md"));
      } catch (java.io.IOException e) {
        throw new IllegalStateException("could not create the persist obstruction", e);
      }
    });
    RecordingWorker worker = new RecordingWorker(harness.worker());
    ExecutionEngine engine = harness.engine(worker, new RetryPolicy(1, List.of(1L)));

    engine.advance(harness.executionId());

    assertThat(worker.attempts)
        .as("attempt 1 ran and failed on its output write")
        .containsExactly(1);
    assertThat(harness.execution().getStatus())
        .as("a maxAttempts-1 policy escalates the single PERSIST_FAILED attempt")
        .isEqualTo(ExecutionStatus.FAILED_ESCALATED);
    RecoveryBundleStore.RecoveryBundle first = harness.bundle(1).orElseThrow();
    assertThat(first.failureReason()).isEqualTo("PERSIST_FAILED");
    byte[] firstManifest = Files.readAllBytes(
        first.directory().resolve(RecoveryBundleStore.MANIFEST_FILE));
    byte[] firstPatch = Files.readAllBytes(first.directory().resolve("patch.diff"));
    assertThat(harness.workspaceDir(1))
        .as("attempt 1's workspace is retained under the uniform attempt-scoped key")
        .exists();

    // The real escalation-approval route: a real HitlDecisionService decides
    // APPROVE on the PENDING escalation review, resetting only the budget.
    harness.approveEscalation();
    assertThat(harness.execution().getStatus())
        .as("an approved escalation requeues the execution as PENDING")
        .isEqualTo(ExecutionStatus.PENDING);
    harness.execution().setStatus(ExecutionStatus.RUNNING);
    harness.stubMutationTurns((handle, workDir) ->
        exec(harness.sandbox(), handle, "cd repo && printf 'public class A2 {}\\n' > A2.java"));
    engine.advance(harness.executionId());

    assertThat(worker.attempts)
        .as("the escalation-approved requeue must run as attempt 2, never reuse attempt 1")
        .containsExactly(1, 2);
    assertThat(harness.execution().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    assertThat(harness.workspaceDir(2))
        .as("the acknowledged requeued run tore down only its own workspace")
        .doesNotExist();
    assertThat(harness.workspaceDir(1))
        .as("the requeued create must never sweep attempt 1's retained workspace")
        .exists();
    assertThat(harness.workspaceDir(1).resolve("repo").resolve("unique-b4.txt"))
        .as("B4 probe: attempt 1's retained unique bytes survive the requeue verbatim")
        .hasContent(uniqueBytes);
    assertThat(harness.bundle(2))
        .as("the acknowledged requeued attempt discarded exactly its own bundle")
        .isEmpty();
    assertThat(harness.bundle(1))
        .as("attempt-1's bundle survives the requeued attempt")
        .isPresent();
    assertThat(Files.readAllBytes(
        first.directory().resolve(RecoveryBundleStore.MANIFEST_FILE)))
        .as("attempt-1's manifest must stay byte-identical across the requeue")
        .isEqualTo(firstManifest);
    assertThat(Files.readAllBytes(first.directory().resolve("patch.diff")))
        .as("attempt-1's patch must stay byte-identical across the requeue")
        .isEqualTo(firstPatch);
  }

  /**
   * (12) Pre-output failure retains a TRAJECTORY-ONLY workspace across the
   * escalation requeue (R3/R5): the obstruction sits where the FIRST
   * declared output (patch.diff) lands, so attempt 1's retained bundle is
   * exactly [trajectory.jsonl] — a true trajectory-only retained workspace —
   * and its sandbox-unique bytes must still survive the escalation-approved
   * requeued attempt.
   */
  @Test
  void preOutputFailureRetainsTrajectoryOnlyWorkspaceAcrossEscalationRequeue()
      throws Exception {
    Harness harness = new Harness("S12-PREOUTPUT-TRAJECTORY-ONLY");
    String uniqueBytes = "b5-trajectory-only-retained-bytes\n";
    harness.stubMutationTurns((handle, workDir) -> {
      exec(harness.sandbox(), handle,
          "cd repo && printf '" + uniqueBytes + "' > unique-b5.txt");
      // The obstruction sits where the FIRST declared output (patch.diff)
      // lands: nothing but the trajectory ever reaches the workDir, so the
      // retained attempt-1 bundle is exactly [trajectory.jsonl].
      try {
        Files.createDirectory(workDir.resolve("patch.diff"));
      } catch (java.io.IOException e) {
        throw new IllegalStateException("could not create the persist obstruction", e);
      }
    });
    RecordingWorker worker = new RecordingWorker(harness.worker());
    ExecutionEngine engine = harness.engine(worker, new RetryPolicy(1, List.of(1L)));

    engine.advance(harness.executionId());

    assertThat(worker.attempts)
        .as("attempt 1 ran and failed before its first output landed")
        .containsExactly(1);
    assertThat(harness.execution().getStatus())
        .as("a maxAttempts-1 policy escalates the single PERSIST_FAILED attempt")
        .isEqualTo(ExecutionStatus.FAILED_ESCALATED);
    RecoveryBundleStore.RecoveryBundle first = harness.bundle(1).orElseThrow();
    assertThat(first.failureReason()).isEqualTo("PERSIST_FAILED");
    assertThat(first.artifactNames())
        .as("a true trajectory-only retained bundle: the obstruction blocked patch.diff itself")
        .containsExactly(Trajectory.FILE_NAME);
    byte[] firstManifest = Files.readAllBytes(
        first.directory().resolve(RecoveryBundleStore.MANIFEST_FILE));
    byte[] firstTrajectory = Files.readAllBytes(
        first.directory().resolve(Trajectory.FILE_NAME));
    assertThat(harness.workspaceDir(1))
        .as("attempt 1's trajectory-only workspace is retained")
        .exists();

    harness.approveEscalation();
    assertThat(harness.execution().getStatus())
        .as("an approved escalation requeues the execution as PENDING")
        .isEqualTo(ExecutionStatus.PENDING);
    harness.execution().setStatus(ExecutionStatus.RUNNING);
    harness.stubMutationTurns((handle, workDir) ->
        exec(harness.sandbox(), handle, "cd repo && printf 'public class A2 {}\\n' > A2.java"));
    engine.advance(harness.executionId());

    assertThat(worker.attempts)
        .as("the escalation-approved requeue must run as attempt 2, never reuse attempt 1")
        .containsExactly(1, 2);
    assertThat(harness.execution().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    assertThat(harness.workspaceDir(2))
        .as("the acknowledged requeued run tore down only its own workspace")
        .doesNotExist();
    assertThat(harness.workspaceDir(1))
        .as("the requeued create must never sweep attempt 1's retained workspace")
        .exists();
    assertThat(harness.workspaceDir(1).resolve("repo").resolve("unique-b5.txt"))
        .as("B5 probe: attempt 1's retained unique bytes survive the requeue verbatim")
        .hasContent(uniqueBytes);
    assertThat(harness.bundle(2))
        .as("the acknowledged requeued attempt discarded exactly its own bundle")
        .isEmpty();
    assertThat(harness.bundle(1))
        .as("attempt-1's trajectory-only bundle survives the requeued attempt")
        .isPresent();
    assertThat(Files.readAllBytes(
        first.directory().resolve(RecoveryBundleStore.MANIFEST_FILE)))
        .as("attempt-1's manifest must stay byte-identical across the requeue")
        .isEqualTo(firstManifest);
    assertThat(Files.readAllBytes(first.directory().resolve(Trajectory.FILE_NAME)))
        .as("attempt-1's trajectory must stay byte-identical across the requeue")
        .isEqualTo(firstTrajectory);
  }

  private void assertRedAtBase(Harness harness, String reportMd, String patch) throws Exception {
    assertThat(patch)
        .as("the run must carry a real change, not the no-op marker")
        .contains("diff --git a/greet.sh b/greet.sh");
    Path base = harness.consumerFrom(harness.taskId(), reportMd, patch, "base-red", false);
    EvalPackScenarios.ShellResult red = harness.shell(base);
    assertThat(red.exitCode())
        .as("the observable check is red on the recorded base before the change")
        .isNotZero();
  }

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
   * S04 byte-level recovery check: base64-decode the bundled workspace
   * snapshot, untar it and read one member back — the snapshot must round-trip
   * the sandbox worktree's unique bytes.
   */
  private static String decodeSnapshotMember(Path bundleDir, String memberName) throws Exception {
    byte[] tarGz = Base64.getMimeDecoder().decode(
        Files.readString(bundleDir.resolve(WORKSPACE_SNAPSHOT_ARTIFACT), StandardCharsets.UTF_8));
    Path tarFile = Files.createTempFile("t25-snapshot", ".tar.gz");
    Files.write(tarFile, tarGz);
    Path extractDir = Files.createTempDirectory("t25-snapshot-extract");
    Process extract = new ProcessBuilder("tar", "xzf", tarFile.toString(),
        "-C", extractDir.toString()).redirectErrorStream(true).start();
    extract.getInputStream().readAllBytes();
    assertThat(extract.waitFor())
        .as("bundled workspace snapshot must extract as a tar.gz")
        .isZero();
    return Files.readString(extractDir.resolve(memberName), StandardCharsets.UTF_8);
  }

  private static void exec(SandboxService sandbox, SandboxHandle handle, String command) {
    var result = sandbox.exec(handle, command, EXEC_TIMEOUT_SEC);
    assertThat(result.ok())
        .as("scenario arrange command failed: %s%nstdout=%s%nstderr=%s",
            command, result.stdout(), result.stderr())
        .isTrue();
  }

  private static String execArgs(String cmd) {
    ObjectNode args = JSON.createObjectNode();
    args.put("cmd", cmd);
    return args.toString();
  }

  private static String patchArgs(String diff) {
    ObjectNode args = JSON.createObjectNode();
    args.put("diff", diff);
    return args.toString();
  }

  private static List<ModelReply> greetingFixScript() {
    return List.of(
        ModelReply.toolCalls(List.of(new ToolCall("t1", "exec",
            execArgs("cd repo && sh check.sh"))), new TokenUsage(1200, 48)),
        ModelReply.toolCalls(List.of(new ToolCall("t2", "apply_patch",
            patchArgs(EvalPackScenarios.GREETING_FIX))), new TokenUsage(2100, 96)),
        ModelReply.toolCalls(List.of(new ToolCall("t3", "exec",
            execArgs("cd repo && sh check.sh"))), new TokenUsage(1900, 64)),
        ModelReply.text("Root cause: greet.sh printed Goodbye instead of the specified "
            + "Hello. Fixed the greeting and verified sh check.sh passes.",
            new TokenUsage(300, 175)));
  }

  /** Delegates to the real worker while recording the attempts and results it saw. */
  private static final class RecordingWorker implements AgentWorker {

    private final AgentWorker delegate;
    private final List<Integer> attempts = new ArrayList<>();
    private final List<AgentResult> results = new ArrayList<>();

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
      AgentResult result = delegate.execute(context);
      results.add(result);
      return result;
    }
  }

  /**
   * One scenario's real wiring: real ExecutionEngine (S02's
   * ExecutionEngineRecoveryOrderingTest shape) over a real AgentWorkerRegistry
   * holding a real CodingWorker with a real LocalSandboxService and a real
   * file RecoveryBundleStore under the test temp dir. The step config carries
   * NO workDir key, so the worker must take Files.createTempDirectory.
   */
  private final class Harness {

    private final String taskId;
    private final EvalPackScenarios pack = new EvalPackScenarios(root);
    private final RecoveryBundleStore recovery = new RecoveryBundleStore(root.resolve("recovery"));
    private final Path workspaceRoot = root.resolve("sbx-root");
    private final SandboxService sandbox = new LocalSandboxService(new SandboxProperties(
        "local", null, null, workspaceRoot, Duration.ZERO, null), Clock.systemUTC());
    private final Path fixture;
    private final FakeStateManager state;
    private final AuditLog audit = mock(AuditLog.class);
    private final ArtifactStore artifactStore = mock(ArtifactStore.class);
    private final Map<String, Artifact> storedArtifacts = new LinkedHashMap<>();
    private final List<String> persistOrder = new ArrayList<>();
    private final CodingHarness harness;
    private Path capturedWorkDir;

    Harness(String taskId, List<ModelReply> modelScript) throws Exception {
      this.taskId = taskId;
      this.fixture = pack.createGreetingFixture(taskId, "Goodbye, $1.");
      this.state = new FakeStateManager(triggerPayload());
      if (modelScript != null) {
        this.harness = realHarness(
            new EvalPackScenarios.ScriptedModel(taskId, modelScript));
      } else {
        this.harness = mock(CodingHarness.class);
      }
      acknowledgePutMarkdown();
    }

    Harness(String taskId) throws Exception {
      this(taskId, null);
    }

    private CodingHarness realHarness(ChatModelAdapter model) {
      GitDiffTool gitDiffTool = new GitDiffTool(sandbox);
      return new CodingHarness(model,
          new ToolDispatcher(new ReadTool(sandbox), new ListTool(sandbox),
              new ApplyPatchTool(sandbox), new ExecTool(sandbox), gitDiffTool,
              new TestTool(sandbox)),
          gitDiffTool, Clock.systemUTC(), HarnessConfig.defaults());
    }

    CodingWorker worker() {
      return new CodingWorker(sandbox, harness, codec, recovery);
    }

    ExecutionEngine engine() {
      return engine(worker(), RetryPolicy.DEFAULT);
    }

    ExecutionEngine engine(AgentWorker worker, RetryPolicy retryPolicy) {
      StepDescriptor step = new StepDescriptor(STEP_ID, StepType.AGENT, worker.id(),
          null, null, List.of(StepDescriptor.TRIGGER_INPUT), DECLARED_OUTPUTS,
          Map.of());
      FlowDescriptor flow = new FlowDescriptor(FLOW_ID, "Durability Flow", "1.0.0",
          List.of(), null, null, List.of(step), retryPolicy);
      FlowRegistry flowRegistry = mock(FlowRegistry.class);
      when(flowRegistry.require(FLOW_ID)).thenReturn(flow);
      return new ExecutionEngine(flowRegistry,
          new AgentWorkerRegistry(List.of(worker), flowRegistry),
          state, artifactStore, audit, mock(HitlGateOpener.class), mock(SubFlowInvoker.class),
          List.of(), JSON, List.of(recovery));
    }

    /**
     * T25 S11 plumbing: drives the REAL escalation-approval route — a real
     * HitlDecisionService wired over this suite's fake state, a mocked
     * HitlReviewRepository holding one PENDING escalation review at step
     * index 0 (gateId {@link HitlGateOpener#ESCALATION_GATE_ID}), the same
     * flowRegistry mock shape as the engine wiring, an empty amendment
     * validator list, and the shared audit/jsonMapper mocks — deciding
     * APPROVE exactly as the review inbox would.
     */
    void approveEscalation() {
      StepDescriptor step = new StepDescriptor(STEP_ID, StepType.AGENT, worker().id(),
          null, null, List.of(StepDescriptor.TRIGGER_INPUT), DECLARED_OUTPUTS, Map.of());
      FlowDescriptor flow = new FlowDescriptor(FLOW_ID, "Durability Flow", "1.0.0",
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
            persistOrder.add(name);
            storedArtifacts.put(name, new Artifact(executionId, name, 1, "text/markdown",
                content, ArtifactStore.sha256(content), invocation.getArgument(3)));
            return null;
          });
      when(artifactStore.getLatest(any(UUID.class), anyString()))
          .thenAnswer(invocation ->
              Optional.ofNullable(storedArtifacts.get(invocation.getArgument(1))));
    }

    void failPutMarkdown() {
      doThrow(new RuntimeException("simulated artifact-store outage"))
          .when(artifactStore).putMarkdown(any(UUID.class), anyString(), anyString(), anyString());
    }

    void stubMutationTurns(BiConsumer<SandboxHandle, Path> turns) {
      when(harness.run(any(), any(TaskContract.class), any())).thenAnswer(invocation -> {
        SandboxHandle handle = invocation.getArgument(0);
        Path workDir = invocation.getArgument(2);
        Files.createDirectories(workDir);
        Files.writeString(workDir.resolve(Trajectory.FILE_NAME),
            "{\"ts\":\"2026-09-08T10:00:00Z\",\"step\":1,\"tool\":\"exec\",\"ok\":true}\n");
        turns.accept(handle, workDir);
        return new HarnessReport(3, HarnessReport.Outcome.COMPLETED,
            HarnessReport.StopReason.COMPLETED, 2, 128L, 0, 0L, 0L,
            TaskOutcome.SUCCEEDED, TaskOutcome.Reason.CHANGES_DELIVERED, "");
      });
    }

    /**
     * S08 terminal-exception shape: the scripted harness does its unique
     * coding write and writes the trajectory, then dies with a plain
     * RuntimeException (the Trajectory close/append failure shape) instead of
     * returning a report.
     */
    void stubMutationTurnsThenThrow(BiConsumer<SandboxHandle, Path> turns) {
      when(harness.run(any(), any(TaskContract.class), any())).thenAnswer(invocation -> {
        SandboxHandle handle = invocation.getArgument(0);
        Path workDir = invocation.getArgument(2);
        capturedWorkDir = workDir;
        Files.createDirectories(workDir);
        Files.writeString(workDir.resolve(Trajectory.FILE_NAME),
            "{\"ts\":\"2026-09-08T10:00:00Z\",\"step\":1,\"tool\":\"exec\",\"ok\":true}\n");
        turns.accept(handle, workDir);
        throw new RuntimeException("terminal exception after coding work exists");
      });
    }

    String taskId() {
      return taskId;
    }

    UUID executionId() {
      // The ONE execution the fake state manager shares across attempts; the
      // engine, worker, bundle store and sandbox workspace all key off it.
      return state.execution.getId();
    }

    PipelineExecution execution() {
      return state.execution;
    }

    AuditLog audit() {
      return audit;
    }

    SandboxService sandbox() {
      return sandbox;
    }

    Path workspaceDir() {
      return workspaceDir(1);
    }

    Path recoveryRoot() {
      return root.resolve("recovery");
    }

    Path capturedWorkDir() {
      return capturedWorkDir;
    }

    Path workspaceDir(int attempt) {
      // T25 S09: the worker keys workspaces attempt-scoped UNIFORMLY — every
      // attempt, including the first, owns sbx-<exec>-attempt-<n> — so a
      // later attempt's create can never sweep a prior attempt's retained
      // workspace.
      return workspaceRoot.resolve(
          "sbx-" + executionId() + "-attempt-" + attempt);
    }

    Map<String, Artifact> storedArtifacts() {
      return storedArtifacts;
    }

    List<String> persistOrder() {
      return persistOrder;
    }

    Optional<Artifact> latestArtifact(String name) {
      return artifactStore.getLatest(executionId(), name);
    }

    String storedContent(String name) {
      Artifact artifact = storedArtifacts.get(name);
      assertThat(artifact).as("store must hold %s", name).isNotNull();
      return artifact.getContent();
    }

    Optional<RecoveryBundleStore.RecoveryBundle> bundle(int attempt) {
      return recovery.find(executionId(), STEP_ID, attempt);
    }

    EvalPackScenarios.ShellResult shell(Path dir) throws Exception {
      return pack.shell(dir, "sh", "check.sh");
    }

    Path consumerFrom(String packRunId, String reportMd, String patch, String tag,
        boolean withPatch) throws Exception {
      EvalPackScenarios.PackRun run = new EvalPackScenarios.PackRun(packRunId, null,
          new AgentResult(Map.of("patch.diff", patch), Map.of()), 0L, reportMd, null);
      return pack.consumerCheckout(run, tag, withPatch);
    }

    private String triggerPayload() {
      ObjectNode payload = JSON.createObjectNode();
      payload.put("taskId", taskId)
          .put("repoUrl", fixture.toAbsolutePath().toString())
          .put("baseBranch", "main")
          .put("branch", "dev/" + taskId)
          .put("goal", EvalPackScenarios.REGRESSION_GOAL);
      return payload.toString();
    }
  }

  /** In-memory StateManager: one shared execution plus retry bookkeeping (S02 shape). */
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
      // T25 S11 plumbing: approving an escalation (HitlDecisionService
      // resume) resets ONLY the retry budget; the ascending attempt ordinal
      // below is deliberately untouched by the reset.
      retries.remove(stepId);
    }

    /**
     * T25 S12: mirrors the real allocator — read→merge+1→return — that never
     * resets, so an escalation-approved requeue can never reuse a prior
     * attempt's ordinal (the engine consumes this as the sole attempt
     * identity source).
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
