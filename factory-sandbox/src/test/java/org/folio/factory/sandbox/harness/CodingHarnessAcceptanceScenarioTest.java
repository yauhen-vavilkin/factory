package org.folio.factory.sandbox.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.tools.ApplyPatchTool;
import org.folio.factory.sandbox.tools.ExecTool;
import org.folio.factory.sandbox.tools.GitDiffTool;
import org.folio.factory.sandbox.tools.ListTool;
import org.folio.factory.sandbox.tools.OutputLimiter;
import org.folio.factory.sandbox.tools.ReadTool;
import org.folio.factory.sandbox.tools.TestTool;
import org.folio.factory.sandbox.tools.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T24 R5 acceptance-matrix scenario suite. Every scenario drives the REAL
 * decision path through {@link CodingHarness#run} (mocks only at the
 * model/adapter and sandbox-tool boundary) and asserts the terminal
 * {@link TaskOutcome}. Negative scenarios were observed RED against the
 * pre-fix decision, which accepted the false positive, and pass only because
 * the acceptance boundary now requires fresh, identity-bound check evidence.
 */
@ExtendWith(MockitoExtension.class)
class CodingHarnessAcceptanceScenarioTest {

  private static final SandboxHandle HANDLE = new SandboxHandle("sbx-t24", "c1");

  private static final String CHECK_CMD = "cd repo && mvn -pl factory-core test -B";

  /** Full surefire machine-readable summary: all four groups, nothing skipped. */
  private static final String GREEN_SUMMARY =
      "[summary]\nTests run: 4, Failures: 0, Errors: 0, Skipped: 0";

  /**
   * T24 B1: distinct delivered-content identities (git tree ids). Attempt 1
   * hashed the display diff instead, so the scenarios below collided.
   */
  private static final String ID_BASE =
      "1111111111111111111111111111111111111111111111111111111111111111";

  private static final String ID_EDIT_ONE =
      "2222222222222222222222222222222222222222222222222222222222222222";

  private static final String ID_EDIT_TWO =
      "3333333333333333333333333333333333333333333333333333333333333333";

  @Mock
  private ChatModelAdapter adapter;

  @Mock
  private ReadTool readTool;

  @Mock
  private ListTool listTool;

  @Mock
  private ApplyPatchTool applyPatchTool;

  @Mock
  private ExecTool execTool;

  @Mock
  private GitDiffTool gitDiffTool;

  @Mock
  private TestTool testTool;

  private MutableClock clock;

  /** The working tree the mocked git_diff tool currently reports. */
  private final String[] diffHolder = {noChanges()};

  /** The delivered-content identity worktreeIdentity currently reports. */
  private final String[] identityHolder = {ID_BASE};

  private static String noChanges() {
    return "[status]\n\n[diff]\n(no changes)";
  }

  private static String changed(String file, String body) {
    return "[status]\n M " + file + "\n\n[diff]\ndiff --git a/" + file + " b/" + file + "\n"
        + "--- a/" + file + "\n+++ b/" + file + "\n@@ -1 +1 @@\n" + body + "\n";
  }

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2026-09-07T00:00:00Z"));
    diffHolder[0] = noChanges();
    identityHolder[0] = ID_BASE;
    when(gitDiffTool.diff(HANDLE))
        .thenAnswer(invocation -> ToolResult.success(diffHolder[0]));
    // T24 B1: every scenario's finish() (and each declared-check receipt)
    // derives identity from worktreeIdentity; this default tracks the
    // holder so scenarios move identity only by editing content. Lenient so
    // a scenario that overrides it with a fixed capture failure stays valid.
    lenient().when(gitDiffTool.worktreeIdentity(HANDLE))
        .thenAnswer(invocation -> ToolResult.success(identityHolder[0]));
  }

  private CodingHarness harness() {
    return new CodingHarness(adapter, new ToolDispatcher(readTool, listTool, applyPatchTool,
        execTool, gitDiffTool, testTool), gitDiffTool, clock, HarnessConfig.defaults());
  }

  private TaskContract contract(boolean allowNoop, boolean withCheck) {
    JsonMapper mapper = JsonMapper.builder().build();
    ObjectNode constraints = mapper.createObjectNode();
    if (allowNoop) {
      constraints.put(TaskContract.ALLOW_NOOP_KEY, true);
    }
    if (withCheck) {
      ArrayNode checks = constraints.putArray(TaskContract.CHECKS_KEY);
      checks.addObject().put("id", "tests").put("command", CHECK_CMD);
    }
    ArrayNode acceptance = mapper.createArrayNode();
    acceptance.add("the required verification check passes after the work");
    return new TaskContract("make the required behavior hold", acceptance, constraints, null);
  }

  private void editApplies(final String diffBody) {
    when(applyPatchTool.apply(HANDLE, diffBody)).thenAnswer(invocation -> {
      diffHolder[0] = changed("factory-core/src/main/java/org/folio/Search.java", diffBody);
      identityHolder[0] = ID_EDIT_ONE;
      return ToolResult.success("patch applied");
    });
  }

  /**
   * T24 R2 / scenario 1: allow_noop=true, successful list/read activity,
   * no diff, non-empty final text — but the discriminating contract check
   * never ran. Old decision: NO_OP_VERIFIED (successful tool calls alone).
   */
  @Test
  void listOnlyNoOp(@TempDir Path workDir) {
    CodingHarness harness = harness();
    TaskContract contract = contract(true, true);
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(new ToolCall("t1", "list", "{\"path\":\"repo\"}")))
        .thenReturn(ModelReply.toolCall(
            new ToolCall("t2", "read", "{\"path\":\"repo/src/Search.java\"}")))
        .thenReturn(ModelReply.text("the search already works; no change needed"));
    when(listTool.list(HANDLE, "repo", null)).thenReturn(ToolResult.success("pom.xml\nsrc/"));
    when(readTool.read(HANDLE, "repo/src/Search.java", null, null))
        .thenReturn(ToolResult.success("class Search {}"));

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertNotEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertNotEquals(TaskOutcome.Reason.NO_OP_VERIFIED, report.taskOutcomeReason());
    assertEquals(TaskOutcome.Reason.REQUIRED_CHECK_MISSING, report.taskOutcomeReason());
    assertEquals(VerificationLedger.Status.MISSING,
        report.verification().checks().get(0).status());
  }

  /**
   * T24 R1/R3 / scenario 2: relevant source change plus final text, but the
   * mandatory check was omitted. Old decision: SUCCEEDED on filesChanged>0.
   */
  @Test
  void changedNoTest(@TempDir Path workDir) {
    CodingHarness harness = harness();
    TaskContract contract = contract(false, true);
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(
            new ToolCall("t1", "apply_patch", "{\"diff\":\"fix search\"}")))
        .thenReturn(ModelReply.text("fixed the search bug"));
    editApplies("fix search");

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(1, report.filesChanged());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertNotEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertEquals(TaskOutcome.Reason.REQUIRED_CHECK_MISSING, report.taskOutcomeReason());
    assertEquals(VerificationLedger.Status.MISSING,
        report.verification().checks().get(0).status());
  }

  /**
   * T24 R3 / scenario 3: the mandatory check ran after the change and exited
   * non-zero (red). Old decision: SUCCEEDED on filesChanged>0.
   */
  @Test
  void changedRedTest(@TempDir Path workDir) {
    CodingHarness harness = harness();
    TaskContract contract = contract(false, true);
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(
            new ToolCall("t1", "apply_patch", "{\"diff\":\"fix search\"}")))
        .thenReturn(ModelReply.toolCall(new ToolCall("t2", "exec",
            "{\"cmd\":\"" + CHECK_CMD + "\"}")))
        .thenReturn(ModelReply.text("fixed, tests were run"));
    editApplies("fix search");
    when(execTool.run(HANDLE, CHECK_CMD, null)).thenReturn(ToolResult.failure(
        "[summary]\nTests run: 2, Failures: 1, Errors: 0, Skipped: 0",
        "command exited with code 1"));

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(1, report.filesChanged());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertNotEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertEquals(TaskOutcome.Reason.REQUIRED_CHECK_FAILED, report.taskOutcomeReason());
    VerificationLedger.CheckState state = report.verification().checks().get(0);
    assertEquals(VerificationLedger.Status.FAILED, state.status());
    assertTrue(state.detail().contains("command exited with code 1"));
  }

  /**
   * T24 R3 / scenario 4: check green, then a source edit within its scope —
   * the receipt is stale, so the terminal outcome is FAILED until a fresh
   * post-edit check passes (scenario 6 covers that recovery). Old decision:
   * SUCCEEDED on filesChanged>0.
   */
  @Test
  void greenThenEditStale(@TempDir Path workDir) {
    CodingHarness harness = harness();
    TaskContract contract = contract(false, true);
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(
            new ToolCall("t1", "apply_patch", "{\"diff\":\"first fix\"}")))
        .thenReturn(ModelReply.toolCall(new ToolCall("t2", "exec",
            "{\"cmd\":\"" + CHECK_CMD + "\"}")))
        .thenReturn(ModelReply.toolCall(
            new ToolCall("t3", "apply_patch", "{\"diff\":\"second fix\"}")))
        .thenReturn(ModelReply.text("fixed and verified"));
    editApplies("first fix");
    when(execTool.run(HANDLE, CHECK_CMD, null))
        .thenReturn(ToolResult.success("[summary]\nTests run: 4, Failures: 0, Errors: 0"));
    when(applyPatchTool.apply(HANDLE, "second fix")).thenAnswer(invocation -> {
      diffHolder[0] = changed(
          "factory-core/src/main/java/org/folio/Search.java", "second fix");
      identityHolder[0] = ID_EDIT_TWO;
      return ToolResult.success("patch applied");
    });

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(1, report.filesChanged());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertNotEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertEquals(TaskOutcome.Reason.REQUIRED_CHECK_STALE, report.taskOutcomeReason());
    VerificationLedger.CheckState state = report.verification().checks().get(0);
    assertEquals(VerificationLedger.Status.STALE, state.status());
    assertNotEquals(state.boundIdentity(), report.verification().resultIdentity());
    assertTrue(state.boundIdentity().matches("[0-9a-f]{64}"));
  }

  /**
   * T24 R2 / scenario 5: allow_noop=true, no diff, and a fresh discriminating
   * check proves the requested behavior already holds — proof bound to the
   * final result identity.
   */
  @Test
  void verifiedNoOp(@TempDir Path workDir) {
    CodingHarness harness = harness();
    TaskContract contract = contract(true, true);
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(new ToolCall("t1", "exec",
            "{\"cmd\":\"" + CHECK_CMD + "\"}")))
        .thenReturn(ModelReply.text("reproduction is green on main; no change needed"));
    when(execTool.run(HANDLE, CHECK_CMD, null))
        .thenReturn(ToolResult.success("[summary]\nTests run: 4, Failures: 0, Errors: 0"));

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertEquals(TaskOutcome.Reason.NO_OP_VERIFIED, report.taskOutcomeReason());
    VerificationLedger.CheckState state = report.verification().checks().get(0);
    assertEquals(VerificationLedger.Status.PASS, state.status());
    assertEquals(state.boundIdentity(), report.verification().resultIdentity());
    assertTrue(state.boundIdentity().matches("[0-9a-f]{64}"));
  }

  /**
   * T24 R3 / scenario 6: requested change with every mandatory check executed
   * after the final relevant edit, passing and bound to the result.
   */
  @Test
  void changedFreshGreen(@TempDir Path workDir) {
    CodingHarness harness = harness();
    TaskContract contract = contract(false, true);
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(
            new ToolCall("t1", "apply_patch", "{\"diff\":\"fix search\"}")))
        .thenReturn(ModelReply.toolCall(new ToolCall("t2", "exec",
            "{\"cmd\":\"" + CHECK_CMD + "\"}")))
        .thenReturn(ModelReply.text("fixed the search bug; the required check is green"));
    editApplies("fix search");
    when(execTool.run(HANDLE, CHECK_CMD, null))
        .thenReturn(ToolResult.success("[summary]\nTests run: 4, Failures: 0, Errors: 0"));

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(1, report.filesChanged());
    assertEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertEquals(TaskOutcome.Reason.CHANGES_DELIVERED, report.taskOutcomeReason());
    VerificationLedger.CheckState state = report.verification().checks().get(0);
    assertEquals(VerificationLedger.Status.PASS, state.status());
    assertEquals(state.boundIdentity(), report.verification().resultIdentity());
  }

  /**
   * T24 R3 / scenario 7: the required check could not be executed (here: the
   * sandbox executor timed it out). There is no separate infrastructure
   * outcome in TaskOutcome, so the explicit result is FAILED — never either
   * success outcome, with the executor failure preserved in diagnostics.
   * Old decision: SUCCEEDED on filesChanged>0.
   */
  @Test
  void checkCannotExecute(@TempDir Path workDir) {
    CodingHarness harness = harness();
    TaskContract contract = contract(false, true);
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(
            new ToolCall("t1", "apply_patch", "{\"diff\":\"fix search\"}")))
        .thenReturn(ModelReply.toolCall(new ToolCall("t2", "exec",
            "{\"cmd\":\"" + CHECK_CMD + "\"}")))
        .thenReturn(ModelReply.text("fixed; the check could not be executed"));
    editApplies("fix search");
    when(execTool.run(HANDLE, CHECK_CMD, null)).thenReturn(ToolResult.failure(
        "[maven]\n[sandbox exec timed out after 900s]",
        "command exited with code -1"));

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(1, report.filesChanged());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertNotEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertNotEquals(TaskOutcome.Reason.NO_OP_VERIFIED, report.taskOutcomeReason());
    assertEquals(TaskOutcome.Reason.REQUIRED_CHECK_FAILED, report.taskOutcomeReason());
    VerificationLedger.CheckState state = report.verification().checks().get(0);
    assertEquals(VerificationLedger.Status.FAILED, state.status());
    assertTrue(state.detail().contains("command exited with code -1"));
  }

  /**
   * T24 B1 additive regression: a post-check edit whose DELIVERED CONTENT
   * moved (new tree id) while the display diff stays byte-identical — the
   * attempt-1 display-diff digest collided on exactly this shape and
   * certified the stale receipt fresh, so the terminal outcome must now be
   * REQUIRED_CHECK_STALE, never a success.
   */
  @Test
  void displayCollidingEditAfterGreenCheckIsStaleNotSuccess(@TempDir Path workDir) {
    CodingHarness harness = harness();
    TaskContract contract = contract(false, true);
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(
            new ToolCall("t1", "apply_patch", "{\"diff\":\"first fix\"}")))
        .thenReturn(ModelReply.toolCall(new ToolCall("t2", "exec",
            "{\"cmd\":\"" + CHECK_CMD + "\"}")))
        .thenReturn(ModelReply.toolCall(
            new ToolCall("t3", "apply_patch", "{\"diff\":\"rework, same display\"}")))
        .thenReturn(ModelReply.text("fixed and verified"));
    editApplies("first fix");
    when(execTool.run(HANDLE, CHECK_CMD, null))
        .thenReturn(ToolResult.success(GREEN_SUMMARY));
    // The rework changes the delivered content (new tree id) but leaves the
    // display diff untouched — the display-colliding edit B1 rejects.
    when(applyPatchTool.apply(HANDLE, "rework, same display"))
        .thenAnswer(invocation -> {
          identityHolder[0] = ID_EDIT_TWO;
          return ToolResult.success("patch applied");
        });

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(1, report.filesChanged());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertNotEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertEquals(TaskOutcome.Reason.REQUIRED_CHECK_STALE, report.taskOutcomeReason());
    VerificationLedger.CheckState state = report.verification().checks().get(0);
    assertEquals(VerificationLedger.Status.STALE, state.status());
    assertEquals(ID_EDIT_ONE, state.boundIdentity());
    assertEquals(ID_EDIT_TWO, report.verification().resultIdentity());
  }

  /**
   * T24 B2 additive regression: the check script itself mutates a relevant
   * file AFTER its green assertion and before exit (green summary, exit 0).
   * The B1 receipt captured the identity only after the exec, so the
   * post-assertion mutation was baked into the certified state and the run
   * surfaced SUCCEEDED. The conservative before/after identity boundary must
   * instead record an indeterminate receipt that can never evaluate to PASS.
   */
  @Test
  void checkMutatingAfterGreenAssertionCannotSucceed(@TempDir Path workDir) {
    CodingHarness harness = harness();
    TaskContract contract = contract(false, true);
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(
            new ToolCall("t1", "apply_patch", "{\"diff\":\"fix search\"}")))
        .thenReturn(ModelReply.toolCall(new ToolCall("t2", "exec",
            "{\"cmd\":\"" + CHECK_CMD + "\"}")))
        .thenReturn(ModelReply.text("fixed the search bug; the required check is green"));
    editApplies("fix search");
    // The script shape 'assert green; mutate relevant file; exit 0': the
    // exec result is green, but DURING the check exec the delivered content
    // moves (id-A captured before the exec, id-B after it), exactly as a
    // post-assertion mutation of a relevant file would move it.
    when(execTool.run(HANDLE, CHECK_CMD, null)).thenAnswer(invocation -> {
      identityHolder[0] = ID_EDIT_TWO;
      return ToolResult.success(GREEN_SUMMARY);
    });

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(1, report.filesChanged());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertNotEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertEquals(TaskOutcome.Reason.REQUIRED_CHECK_STALE, report.taskOutcomeReason());
    VerificationLedger.CheckState state = report.verification().checks().get(0);
    assertEquals(VerificationLedger.Status.STALE, state.status());
    assertEquals(null, state.boundIdentity());
    assertEquals(ID_EDIT_TWO, report.verification().resultIdentity());
    assertTrue(state.detail().contains("changed while the check ran"));
  }

  /**
   * T24 B1 additive regression (fail-closed, changed path): when the
   * delivered-content identity cannot be captured, even a green post-edit
   * check can never be certified fresh — no success on the changed path.
   */
  @Test
  void identityCaptureFailureOnChangedPathYieldsNoSuccess(@TempDir Path workDir) {
    CodingHarness harness = harness();
    TaskContract contract = contract(false, true);
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(
            new ToolCall("t1", "apply_patch", "{\"diff\":\"fix search\"}")))
        .thenReturn(ModelReply.toolCall(new ToolCall("t2", "exec",
            "{\"cmd\":\"" + CHECK_CMD + "\"}")))
        .thenReturn(ModelReply.text("fixed the search bug; the required check is green"));
    editApplies("fix search");
    when(execTool.run(HANDLE, CHECK_CMD, null))
        .thenReturn(ToolResult.success(GREEN_SUMMARY));
    lenient().when(gitDiffTool.worktreeIdentity(HANDLE)).thenReturn(ToolResult.failure(
        "git write-tree failed: fatal: not a git repository"));

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(1, report.filesChanged());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertNotEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertNotEquals(TaskOutcome.Reason.CHANGES_DELIVERED, report.taskOutcomeReason());
    assertEquals(VerificationLedger.Status.STALE,
        report.verification().checks().get(0).status());
    assertEquals(null, report.verification().checks().get(0).boundIdentity());
    assertEquals(null, report.verification().resultIdentity());
  }

  /**
   * T24 B1 additive regression (fail-closed, no-op path): a permitted no-op
   * with a green discriminating check still cannot surface as NO_OP_VERIFIED
   * when the identity that would prove freshness cannot be captured.
   */
  @Test
  void identityCaptureFailureOnNoOpPathYieldsNoSuccess(@TempDir Path workDir) {
    CodingHarness harness = harness();
    TaskContract contract = contract(true, true);
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(new ToolCall("t1", "exec",
            "{\"cmd\":\"" + CHECK_CMD + "\"}")))
        .thenReturn(ModelReply.text("reproduction is green on main; no change needed"));
    when(execTool.run(HANDLE, CHECK_CMD, null))
        .thenReturn(ToolResult.success(GREEN_SUMMARY));
    lenient().when(gitDiffTool.worktreeIdentity(HANDLE)).thenReturn(ToolResult.failure(
        "git write-tree failed: fatal: not a git repository"));

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertNotEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertNotEquals(TaskOutcome.Reason.NO_OP_VERIFIED, report.taskOutcomeReason());
    assertEquals(VerificationLedger.Status.STALE,
        report.verification().checks().get(0).status());
    assertEquals(null, report.verification().resultIdentity());
  }

  /**
   * T24 B3 additive regression (changed path): the declared check exits 0 but
   * its output carries a 4-group surefire summary with a skipped case
   * ('Tests run: 4, Failures: 0, Errors: 0, Skipped: 1'). Exit code alone is
   * not green evidence: the receipt must be FAILED with the offending summary
   * quoted in its detail, so the run is REQUIRED_CHECK_FAILED, never
   * CHANGES_DELIVERED. Old decision: SUCCEEDED (exit-code pass-through).
   */
  @Test
  void skippedCaseOnChangedPathFailsRequiredCheck(@TempDir Path workDir) {
    CodingHarness harness = harness();
    TaskContract contract = contract(false, true);
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(
            new ToolCall("t1", "apply_patch", "{\"diff\":\"fix search\"}")))
        .thenReturn(ModelReply.toolCall(new ToolCall("t2", "exec",
            "{\"cmd\":\"" + CHECK_CMD + "\"}")))
        .thenReturn(ModelReply.text("fixed the search bug; the required check is green"));
    editApplies("fix search");
    when(execTool.run(HANDLE, CHECK_CMD, null)).thenReturn(ToolResult.success(
        "[summary]\nTests run: 4, Failures: 0, Errors: 0, Skipped: 1"));

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(1, report.filesChanged());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertNotEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertNotEquals(TaskOutcome.Reason.CHANGES_DELIVERED, report.taskOutcomeReason());
    assertEquals(TaskOutcome.Reason.REQUIRED_CHECK_FAILED, report.taskOutcomeReason());
    VerificationLedger.CheckState state = report.verification().checks().get(0);
    assertEquals(VerificationLedger.Status.FAILED, state.status());
    assertTrue(state.detail().contains("Skipped: 1"));
  }

  /**
   * T24 B3 additive regression (allow_noop path): a permitted no-op whose
   * discriminating check exits 0 with 'Skipped: 1' in its 4-group summary
   * must surface FAILED — never NO_OP_VERIFIED — with the skipped case named
   * in the receipt detail. Old decision: NO_OP_VERIFIED (exit-code
   * pass-through).
   */
  @Test
  void skippedCaseOnNoOpPathIsNeverNoOpVerified(@TempDir Path workDir) {
    CodingHarness harness = harness();
    TaskContract contract = contract(true, true);
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(new ToolCall("t1", "exec",
            "{\"cmd\":\"" + CHECK_CMD + "\"}")))
        .thenReturn(ModelReply.text("reproduction is green on main; no change needed"));
    when(execTool.run(HANDLE, CHECK_CMD, null)).thenReturn(ToolResult.success(
        "[summary]\nTests run: 4, Failures: 0, Errors: 0, Skipped: 1"));

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertNotEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertNotEquals(TaskOutcome.Reason.NO_OP_VERIFIED, report.taskOutcomeReason());
    assertEquals(TaskOutcome.Reason.REQUIRED_CHECK_FAILED, report.taskOutcomeReason());
    VerificationLedger.CheckState state = report.verification().checks().get(0);
    assertEquals(VerificationLedger.Status.FAILED, state.status());
    assertTrue(state.detail().contains("Skipped: 1"));
  }

  /**
   * T24 B3a additive regression (changed path): the declared check exits 0
   * but its output carries no surefire summary of the prefix family at all —
   * maven ran no tests ('[INFO] No tests to run.'), skipped them ('[INFO]
   * Tests are skipped.'), or produced empty output. Exit 0 alone is not
   * green evidence: the receipt must be FAILED with the missing summary
   * named in its detail, so the run is REQUIRED_CHECK_FAILED, never
   * CHANGES_DELIVERED. Old decision: SUCCEEDED (exit-code pass-through).
   */
  @Test
  void missingTestSummaryOnChangedPathFails(@TempDir Path workDir) {
    runChangedPathAssertingRequiredCheckFailed(workDir,
        "[INFO] No tests to run.\n[INFO] BUILD SUCCESS\n", "no surefire test summary");
    runChangedPathAssertingRequiredCheckFailed(workDir,
        "[INFO] Tests are skipped.\n[INFO] BUILD SUCCESS\n", "no surefire test summary");
    runChangedPathAssertingRequiredCheckFailed(workDir, "", "no surefire test summary");
  }

  /**
   * T24 B3a additive regression (allow_noop path): a permitted no-op whose
   * discriminating check exits 0 with no summary line of the prefix family
   * at all must surface FAILED — never NO_OP_VERIFIED. Old decision:
   * NO_OP_VERIFIED (exit-code pass-through).
   */
  @Test
  void missingTestSummaryOnNoOpPathIsNeverNoOpVerified(@TempDir Path workDir) {
    runNoOpPathAssertingRequiredCheckFailed(workDir,
        "[INFO] No tests to run.\n[INFO] BUILD SUCCESS\n", "no surefire test summary");
    runNoOpPathAssertingRequiredCheckFailed(workDir,
        "[INFO] Tests are skipped.\n[INFO] BUILD SUCCESS\n", "no surefire test summary");
    runNoOpPathAssertingRequiredCheckFailed(workDir, "", "no surefire test summary");
  }

  /**
   * T24 B3 additive regression (changed path): the whole run skipped its
   * only case ('Tests run: 1, Failures: 0, Errors: 0, Skipped: 1') — a
   * visible dirty summary, so the receipt is FAILED with the skipped case
   * quoted. Pins the skipped-case rejection against the extended
   * prefix-family pattern.
   */
  @Test
  void allSkippedRunOnChangedPathFails(@TempDir Path workDir) {
    runChangedPathAssertingRequiredCheckFailed(workDir,
        "[summary]\nTests run: 1, Failures: 0, Errors: 0, Skipped: 1\n[INFO] BUILD SUCCESS",
        "Skipped: 1");
  }

  /**
   * T24 B3 additive regression (allow_noop path): the all-skipped probe
   * shape must surface FAILED — never NO_OP_VERIFIED — with 'Skipped: 1'
   * in the receipt detail.
   */
  @Test
  void allSkippedRunOnNoOpPathIsNeverNoOpVerified(@TempDir Path workDir) {
    runNoOpPathAssertingRequiredCheckFailed(workDir,
        "[summary]\nTests run: 1, Failures: 0, Errors: 0, Skipped: 1\n[INFO] BUILD SUCCESS",
        "Skipped: 1");
  }

  /**
   * T24 B3b additive regression (changed path): a complete green summary at
   * the head survives the real {@link OutputLimiter} cut while a later
   * 'Skipped: 1' summary is hidden behind the truncation marker. The
   * retained green prefix must not certify the run: truncation alone fails
   * closed on a maven-shaped check, with the marker quoted in the receipt
   * detail. Old decision: SUCCEEDED (any surviving summary disabled the
   * truncation rejection).
   */
  @Test
  void hiddenSkippedBehindTruncationOnChangedPathFails(@TempDir Path workDir) {
    String truncated = truncatedOutputHidingSkippedTail();
    // Guard: this is the B3b shape — an early complete green summary
    // survived the cut AND the truncation marker is present, exactly what
    // the permissive rule accepted.
    assertTrue(truncated.contains("Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"));
    assertTrue(truncated.contains("[output truncated: "));
    runChangedPathAssertingRequiredCheckFailed(workDir, truncated, "[output truncated: ");
  }

  /**
   * T24 B3b additive regression (allow_noop path): the same
   * truncation-hidden skipped case on the no-op path must surface FAILED —
   * never NO_OP_VERIFIED — with the truncation marker quoted in the
   * receipt detail.
   */
  @Test
  void hiddenSkippedBehindTruncationOnNoOpPathIsNeverNoOpVerified(@TempDir Path workDir) {
    String truncated = truncatedOutputHidingSkippedTail();
    assertTrue(truncated.contains("Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"));
    assertTrue(truncated.contains("[output truncated: "));
    runNoOpPathAssertingRequiredCheckFailed(workDir, truncated, "[output truncated: ");
  }

  /**
   * T24 B3c additive regression (changed path): the declared check exits 0
   * with a clean four-group summary followed by '[INFO] No tests to run.' —
   * maven's explicit incomplete-evidence marker. The surviving clean prefix
   * must not certify the run: the receipt is FAILED with the marker quoted,
   * so the run is REQUIRED_CHECK_FAILED, never CHANGES_DELIVERED. Old
   * decision: SUCCEEDED (one clean summary disabled the absence check).
   */
  @Test
  void mixedGreenSummaryWithNoTestsMarkerOnChangedPathFails(@TempDir Path workDir) {
    runChangedPathAssertingRequiredCheckFailed(workDir,
        GREEN_SUMMARY + "\n[INFO] No tests to run.\n[INFO] BUILD SUCCESS",
        "No tests to run.");
  }

  /**
   * T24 B3c additive regression (allow_noop path): the same mixed
   * clean-summary-plus-'No tests to run.' shape must surface FAILED — never
   * NO_OP_VERIFIED. Old decision: NO_OP_VERIFIED.
   */
  @Test
  void mixedGreenSummaryWithNoTestsMarkerOnNoOpPathIsNeverNoOpVerified(@TempDir Path workDir) {
    runNoOpPathAssertingRequiredCheckFailed(workDir,
        GREEN_SUMMARY + "\n[INFO] No tests to run.\n[INFO] BUILD SUCCESS",
        "No tests to run.");
  }

  /**
   * T24 B3c additive regression (changed path): the same mixed shape with
   * maven's skip-tests marker ('[INFO] Tests are skipped.') instead — FAILED,
   * never CHANGES_DELIVERED, with the marker quoted in the detail.
   */
  @Test
  void mixedGreenSummaryWithSkipMarkerOnChangedPathFails(@TempDir Path workDir) {
    runChangedPathAssertingRequiredCheckFailed(workDir,
        GREEN_SUMMARY + "\n[INFO] Tests are skipped.\n[INFO] BUILD SUCCESS",
        "Tests are skipped.");
  }

  /**
   * T24 B3c additive regression (allow_noop path): the mixed
   * clean-summary-plus-'Tests are skipped.' shape must surface FAILED —
   * never NO_OP_VERIFIED.
   */
  @Test
  void mixedGreenSummaryWithSkipMarkerOnNoOpPathIsNeverNoOpVerified(@TempDir Path workDir) {
    runNoOpPathAssertingRequiredCheckFailed(workDir,
        GREEN_SUMMARY + "\n[INFO] Tests are skipped.\n[INFO] BUILD SUCCESS",
        "Tests are skipped.");
  }

  /** Drives the changed path (edit, check exec, final text) and asserts the
   * run can only be REQUIRED_CHECK_FAILED with a FAILED receipt whose
   * detail quotes {@code expectedDetailFragment}. Re-stubbable so one
   * scenario method can exercise several exec-output variants. */
  private void runChangedPathAssertingRequiredCheckFailed(Path workDir, String execOutput,
      String expectedDetailFragment) {
    CodingHarness harness = harness();
    TaskContract contract = contract(false, true);
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(
            new ToolCall("t1", "apply_patch", "{\"diff\":\"fix search\"}")))
        .thenReturn(ModelReply.toolCall(new ToolCall("t2", "exec",
            "{\"cmd\":\"" + CHECK_CMD + "\"}")))
        .thenReturn(ModelReply.text("fixed the search bug; the required check is green"));
    editApplies("fix search");
    when(execTool.run(HANDLE, CHECK_CMD, null))
        .thenReturn(ToolResult.success(execOutput));

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(1, report.filesChanged());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertNotEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertNotEquals(TaskOutcome.Reason.CHANGES_DELIVERED, report.taskOutcomeReason());
    assertEquals(TaskOutcome.Reason.REQUIRED_CHECK_FAILED, report.taskOutcomeReason());
    VerificationLedger.CheckState state = report.verification().checks().get(0);
    assertEquals(VerificationLedger.Status.FAILED, state.status());
    assertTrue(state.detail().contains(expectedDetailFragment));
  }

  /** Drives the allow_noop path (check exec, final text) and asserts the run
   * can only be REQUIRED_CHECK_FAILED — never NO_OP_VERIFIED — with a
   * FAILED receipt whose detail quotes {@code expectedDetailFragment}. */
  private void runNoOpPathAssertingRequiredCheckFailed(Path workDir, String execOutput,
      String expectedDetailFragment) {
    CodingHarness harness = harness();
    TaskContract contract = contract(true, true);
    when(adapter.reply(anyString(), anyString(), anyList()))
        .thenReturn(ModelReply.toolCall(new ToolCall("t1", "exec",
            "{\"cmd\":\"" + CHECK_CMD + "\"}")))
        .thenReturn(ModelReply.text("reproduction is green on main; no change needed"));
    when(execTool.run(HANDLE, CHECK_CMD, null))
        .thenReturn(ToolResult.success(execOutput));

    HarnessReport report = harness.run(HANDLE, contract, workDir);

    assertEquals(HarnessReport.Outcome.COMPLETED, report.outcome());
    assertEquals(TaskOutcome.FAILED, report.taskOutcome());
    assertNotEquals(TaskOutcome.SUCCEEDED, report.taskOutcome());
    assertNotEquals(TaskOutcome.Reason.NO_OP_VERIFIED, report.taskOutcomeReason());
    assertEquals(TaskOutcome.Reason.REQUIRED_CHECK_FAILED, report.taskOutcomeReason());
    VerificationLedger.CheckState state = report.verification().checks().get(0);
    assertEquals(VerificationLedger.Status.FAILED, state.status());
    assertTrue(state.detail().contains(expectedDetailFragment));
  }

  /**
   * T24 B3b shape: one complete green per-class summary at the head, more
   * than 50 KiB of maven progress filler, then a 'Skipped: 1' summary and
   * BUILD SUCCESS at the tail — passed through the REAL
   * {@link OutputLimiter#truncate} so the harness sees exactly what a real
   * oversized exec produces: the green prefix plus the truncation marker,
   * with the dirty tail removed.
   */
  private static String truncatedOutputHidingSkippedTail() {
    StringBuilder output = new StringBuilder();
    output.append("Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\n");
    for (int i = 0; i < 3400; i++) {
      output.append("[INFO] progress\n");
    }
    output.append("Tests run: 1, Failures: 0, Errors: 0, Skipped: 1\n");
    output.append("[INFO] BUILD SUCCESS");
    return OutputLimiter.truncate(output.toString());
  }

  private static final class MutableClock extends Clock {

    private Instant instant;

    MutableClock(Instant start) {
      this.instant = start;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
