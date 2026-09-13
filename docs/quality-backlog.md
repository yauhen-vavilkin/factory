# Factory quality backlog — owner-requested engineering work

> **Status: historical record (created 2026-09-05).** This backlog was
> written for an external OpenClaw harness whose control directory
> (`WORKFLOW.md`) is not part of this repository. Retained as a record of
> candidate findings only. It is not an active backlog: do not infer work
> from it without an explicit operator task, and re-verify every finding
> against current code first.

Created 2026-09-05 for OpenClaw and its managed harnesses. The server adviser has
not implemented these product fixes. This file defines work, not completion status;
the control directory WORKFLOW.md owns ordering and status. On intake, recheck the
current code and turn one item into a focused task contract with R-ID coverage.
Do not implement this entire backlog as one umbrella task.

## Product outcome and acceptance principle

The owner is product owner/product engineer; OpenClaw owns engineering execution
and quality. Prefer simple maintainable code that demonstrably solves the user's
problem. A green reactor, process exit, pipeline COMPLETED, or empty model report
does not prove a useful software change. An intentionally negative experiment is
valid only under an explicitly experimental contract; keep that separate from
product success. Fix relevant defects rather than repeatedly documenting them.

Installation baseline: local main `fa92575` (T9, following T17 at `1024024`).
T9 fixed the reproduced shutdown warning; do not repeat that completed work.
Its five review risks and deferred daemon-hygiene finding remain in control-dir
`tasks/T9.md` Deferred; reassess only when relevant, without speculative cleanup.
T17's real-model wiring
experiment completed with an empty patch; its own contract permits an honest
negative result. This is evidence that wiring ran, not evidence that Factory can
yet reliably deliver a useful fix. The earlier audit used `49db806`; revalidate
all findings because the bot has subsequently changed the product.

## FTQ-01 — Preserve the task contract and define useful success (P1)

Evidence: FileInboxTrigger.payload persists acceptance/constraints/notes;
CodingWorker.execute currently passes only goal to CodingHarness.run.
CodingHarness treats a non-tool reply as completed; engine completion can also
contain a FAILED harness report. Sources: factory-flow-dev-factory/.../inbox/
FileInboxTrigger.java, .../worker/CodingWorker.java; factory-sandbox/.../harness/
CodingHarness.java; factory-core/.../engine/ExecutionEngine.java.

Requirements for the intake task:
- R1: Carry the relevant acceptance criteria, constraints and notes into the
  actual executor context without truncating or silently dropping them.
- R2: Define separately engine termination, model outcome and validated task
  outcome. Existing consumers must not mistake failed/no-useful-work for success.
- R3: An unchanged result is successful only when the task permits a no-op and
  evidence proves its acceptance conditions; early model completion alone fails.
- R4: Add discriminating tests: constraint affects execution, model ends before
  requested reproduction, FAILED report reaches finalizer, and legitimate no-op.
- R5: Preserve necessary compatibility or present 2–3 migration options to the
  owner before changing public status/API semantics.

DoD: requirement-to-check evidence plus an independently inspected artifact/user
outcome. No general-purpose new orchestration framework just to fix these fields.

## FTQ-02 — Deliver the entire change and preserve it before cleanup (P1)

Evidence: CodingWorker exports `cd repo && git diff`, then tears down sandbox;
ApplyPatchTool uses git apply. New untracked files, staged changes and commits can
be absent from the exported diff. The existing E2E modifies an existing README.

Requirements:
- R1: Define and pin the base revision for the output artifact.
- R2: Export the complete intended change: added/modified/deleted files and
  staged/committed changes; explicitly define binary/rename behavior.
- R3: Apply the exported artifact in a fresh checkout of that base and verify
  required behavior; do not accept the producer's working tree as sole evidence.
- R4: Persist the result durably before destructive cleanup, or preserve a
  recoverable artifact on persistence failure using the smallest suitable design.
- R5: Regression scenarios must include creation of a new source file, mixed
  tracked/untracked changes, no-op and failed export/persistence.

DoD: the consumer checkout reproduces the entire accepted change and its tests.
Discuss material storage/transaction changes with the owner before implementing.

## FTQ-03 — Explain and correct early model completion (P1)

Evidence: T17 Deferred mandatory carry-forwards report two similar short live
runs with empty patches and missing exported final assistant text. The root cause
is unknown; do not claim it is a model limitation or a prompt defect without data.

Requirements:
- R1: Preserve the final model response and relevant stop reason in bounded,
  inspectable artifacts; do not log credentials or unnecessary private context.
- R2: Reconstruct one failing task from allowed evidence and identify the actual
  point where intended work stopped. Separate observations from hypotheses.
- R3: Correct the smallest demonstrated cause (prompt/context/tool schema/model
  adapter/acceptance); do not add retries before diagnosing it.
- R4: Compare the correction on the failing case and a representative successful
  case. Record useful output, remaining defects and actual available token usage.
- R5: If GLM-only diagnosis stalls, ask a bounded Codex/GPT second opinion through
  the managed launcher and verify its conclusion; no automatic quota bypass.

DoD: useful artifact or a precise remaining blocker; another empty run alone is
not evidence the application has improved. Live paid tests stay within existing
owner authorization and quota limits.

## FTQ-04 — Make inbox admission idempotent (P1)

Evidence: FileInboxTrigger routes before moving the file; move errors are logged
and swallowed. PipelineRouter creates fresh executions. LocalSandboxService uses
a task-derived directory and deletes the directory when creating another sandbox.

Requirements:
- R1: Define task identity and what constitutes an intentional new revision.
- R2: Replay after DB commit/before file move returns the existing execution;
  a unique database constraint must back admission where appropriate.
- R3: Failed move, duplicate events and two simultaneous pollers cannot create
  multiple executions for the same admitted task revision.
- R4: Concurrent executions cannot delete each other's workspaces; use execution
  identity and ownership rather than a collision-prone shared task name.
- R5: Keep actual failed ingestion observable and provide an explicit operator
  decision for malformed/revised input. Do not silently discard the task.

DoD: isolated crash-boundary and competing-admission tests demonstrate one
execution and intact results. Any data migration requires owner agreement.

## FTQ-05 — Strengthen behavioral evals and the final review (P1)

Goal: measure whether Factory creates useful software, rather than only whether
its components can execute a scripted successful conversation.

Requirements:
- R1: Keep a small set of representative coding tasks with observable criteria:
  a real regression fix, a new file, a constraint-sensitive task and a no-op case.
- R2: Include counterexamples where tests pass but the requested outcome is not
  achieved. Demonstrate that acceptance rejects them.
- R3: Validate artifacts from a fresh consumer checkout; separate hermetic
  integration evidence from live-provider evidence.
- R4: Report accepted outcome, escaped defects and available cost/latency data;
  never fabricate token counts or infer success from the number of tests.
- R5: Have a fresh reviewer assess test discrimination and unnecessary complexity,
  not just conformity to an implementation plan.

DoD: a small reproducible eval pack that catches observed failures. Avoid a new
benchmark platform or routine paid live runs unless the evidence justifies them.

## FTQ-06 — Close concrete adapter/tool telemetry gaps (P2)

Evidence: six T17 REVIEW carry-forwards in the control directory tasks/T17.md.
Split into smaller tasks if independently deliverable:
- Guard empty/zero-generation model responses and preserve a diagnostic outcome.
- Clarify and test malformed multi-tool batches against the intended error limit.
- Make per-tool duration honest rather than cumulative since the batch began.
- Remove or safely deprecate the single-call accessor that silently returns the
  first call of a batch; check actual callers before changing the interface.
- Resolve shared `/tmp/factory-patch.diff` collisions before permitting parallel
  local execution; do not imply local mode is an isolation boundary.

DoD: focused behavioral regressions for each accepted defect; no unrelated API
rewrite. Final-text persistence belongs to FTQ-03, not a duplicate task.

## FTQ-07 — Review execution ownership before increasing concurrency (P1, gated)

Evidence: engine heartbeats before a blocking worker; reaper may reclaim it while
still alive; artifact writes precede guarded step advancement. Queued claimed
tasks can consume lease time before they start.

Desired proof: at most one current owner may publish artifacts, capacity and
claiming agree, and an expired owner cannot overwrite the successor's result.
Investigate the smallest design and present alternatives, compatibility and cost
to the owner before implementation. New deadlines, fencing and recovery mechanisms
are NOT part of the currently approved Workflow installation. Save this risk;
do not silently implement that excluded work or enable concurrency around it.

## FTQ-08 — Confirm isolation and integration access before broader use (P1, gated)

Check actual sandbox identity, inherited environment, network policy and trigger
exposure. Local mode is not containment. No public exposure or credential leak
was established by the audit. Inventory facts without printing secret values.
Propose minimal restrictions and a usable operational path; obtain owner approval
before new permissions, external integrations, infrastructure/security changes.
DoD is a verified access boundary for the intended deployment, not a policy slogan.

## Already changed since the original audit

T17 added create-failure cleanup, a container keep-alive command and a shared Maven
cache. Current DockerSandboxService contains those changes. Do not reimplement
the old D6 finding blindly: inspect relevant tests/observations, and open a smaller
task only for a reproduced remaining defect. Keep existing evidence honest.

## Intake order and engineering method

Prioritize FTQ-01/02/03, using FTQ-05 acceptance principles in every task. Then
FTQ-04/06. FTQ-07/08 require the stated decisions before their risky changes.
Do not start all tasks together. For each, read the real current code, choose the
simplest useful fix, freeze measurable acceptance, verify and fix in-scope defects.
Use the global AutoDev ENGINEERING.md for context, token discipline, harness
selection, iterative skill/tool improvement and owner consultation.

This document deliberately contains no task status or claim that the fixes were
implemented. The bot allocates normal Txx task IDs in WORKFLOW at intake.


## Post-migration correction intake — 2026-09-06

Read this correction list before the historical FTQ-01..06 installation order above.
The current accepted baseline is main 409e2fc after T23; T21 delivered the FTQ-05
eval pack, T22 admission ownership, T23 telemetry. These do not close the independently
confirmed T18/T19 escaped defects. Next correction contracts are T24/FTQ-09 then
T25/FTQ-10; WORKFLOW controls execution. No paid model run is started by these docs.

## FTQ-09 — Reject validated success without requirement evidence (P1)

Evidence: the post-migration audit found that `CodingHarness` treats any successful
tool, including list/read, as useful activity and can return `NO_OP_VERIFIED` for a
list-only run. It can also return success for a changed tree when a required test
was never run or failed. Existing tests encode apply-patch-without-tests as success.
This is an escaped T18 acceptance defect, not evidence that the external T19
permission-rejected run was accepted.

Requirements:

- **R1:** Derive the required verification obligations from the frozen task
  contract and make the terminal `TaskOutcome` depend on their evidence, not on a
  generic successful tool call, a non-empty final response, or `filesChanged > 0`.
- **R2:** Return `NO_OP_VERIFIED` only when no-op is explicitly allowed and fresh,
  discriminating evidence proves the requested behavior already holds. Successful
  list/read activity alone must return `FAILED`, never a success outcome.
- **R3:** A changed result may return `SUCCEEDED` only when every mandatory check
  ran after the final relevant source/config/test/fixture edit, passed, and is bound
  to that exact output/tree. Missing, skipped, red, executor-failed, or stale checks
  must return `FAILED` (or the existing explicit infrastructure-failure outcome),
  never success.
- **R4:** Preserve bounded final text and useful diagnostics for all outcomes, but
  never use self-report, final text, tool count, or change count as a substitute for
  requirement evidence. Keep compatible T18 fields/outcomes where their semantics
  remain valid.
- **R5:** Add independently executable negative and positive scenarios for
  list-only no-op, changed/no-test, changed/red-test, green-test-then-edit,
  verified no-op, and changed/fresh-green. A fresh Codex review must assess every
  acceptance boundary and whether the tests can fail under a plausible regression.

Definition of done: the focused scenario suite demonstrates every failure boundary
above and normal valid successes, project gates pass on the final tree, and a fresh
Codex acceptance review accepts R1-R5. Review unavailability or quota exhaustion
pauses the task; it does not authorize an automatic reviewer fallback.

Allocated correction contract: `tasks/T24.md` in the project control directory.

## FTQ-10 — Preserve recoverable output until durable persistence (P1)

Evidence: with the shipped flow's default configuration, `CodingWorker` creates a
temporary work directory. Its `finally` teardown can remove that last filesystem
copy before `ExecutionEngine` attempts downstream `ArtifactStore` persistence.
Export failure follows the same destructive path, and current scenarios use an
explicit `workDir`, so they do not cover the default boundary. This is an escaped
T19 data-integrity defect; the audit did not establish an actual historical user
data loss.

Requirements:

- **R1:** Define and enforce the durability/ownership boundary for the default
  configuration with no `workDir`: destructive teardown must not remove the last
  recoverable copy before downstream persistence is acknowledged.
- **R2:** Publish a recovery bundle with integrity metadata before destructive
  cleanup whenever successful worker output has not yet been acknowledged by the
  `ArtifactStore`. A downstream persistence failure must return failure plus a
  usable recovery locator; an incomplete bundle must never be advertised as a
  complete accepted artifact.
- **R3:** On export failure or partial output, retain the smallest recoverable
  attempt state and an explicit incomplete/failure manifest. Preserve already
  produced unique data while preventing partial output from being accepted as a
  complete change.
- **R4:** Scope recovery data by stable execution/attempt identity. A retry must not
  overwrite or delete a prior recoverable attempt, and cleanup is allowed only
  after acknowledged durable persistence or an explicit, tested retention action.
- **R5:** Add independently executable scenarios covering default no-`workDir`,
  downstream `ArtifactStore` failure, export failure, partial output, retry
  preservation, and reconstruction in a fresh consumer checkout. A fresh Codex
  data-integrity review must verify byte/content integrity and failure ordering.

Definition of done: every failure case retains an accurately labelled recovery
artifact, a consumer reconstructs and verifies the successful change from the
recovered artifact, normal persistence still permits safe cleanup, project gates
pass, and a fresh Codex review accepts R1-R5. Review unavailability or quota
exhaustion pauses the task; it does not authorize an automatic reviewer fallback.

Allocated correction contract: `tasks/T25.md` in the project control directory.

## FTQ-05 supplement — extend the accepted T21 eval pack

T21 delivered the original FTQ-05 eval pack on main 71deff3. Do not create a second
umbrella eval task or rewrite T21 evidence. T24 and T25 must reuse and extend that
pack with their actual escaped-defect counterexamples, including consumer checks.
The existence of the pack does not close F1/F2: revalidation at main 409e2fc still
finds the generic-tool success and temporary-output cleanup branches.

## FTQ-03 intake supplement — live early-completion cause remains unknown

T20 preserved bounded final response/stop-reason evidence, but the audit explicitly
classifies elimination of the live GLM early-completion cause as unproven
(`2026-09-06-autodev-post-migration-audit.md:47,132-137`). Preserve the original
FTQ-03 statement that the cause is unknown. Do not relabel the observation as a
model, prompt, adapter, or acceptance defect without discriminating evidence, and do
not add retries as diagnosis.

At a future FTQ-03 intake, first compare the allowed failing evidence with a
representative success and identify the exact stop boundary. Allocate further
product work only for the smallest demonstrated remaining cause. A new paid live run
requires the existing owner authorization and quota limits; another empty run alone
does not prove improvement.

## ADQ-02 — Decide deadline and process-group cleanup semantics (gated)

This is a separate AutoDev control-plane decision task. It is not a product FTQ,
does not authorize a timer implementation, and must not send signals to any live
managed run while being researched or reviewed.

Gate: obtain an explicit owner decision on semantics, compatibility, risk, and
rollback before creating any implementation task. The current cron interval is a
wake cadence, not a child-process deadline.

Requirements:

- **R1:** Map the launcher/process tree and ownership boundaries, including parent,
  child, grandchildren, process groups/sessions, receipt/PID identity, and the
  points at which a run is admitted, settled, or known stale. Separate observed
  behavior from unproven hang/orphan hypotheses.
- **R2:** Present deadline choices with a recommendation: when a deadline starts,
  which stages it covers, configuration/default/disable behavior, maximum/grace
  periods, quota and restart interaction, and backward compatibility. Do not treat
  reconciler or cron cadence as the deadline.
- **R3:** Specify a race-safe cleanup protocol for a future implementation:
  positively identify the owned process group, send TERM, wait a bounded grace
  period, escalate to KILL only for surviving owned members, reap children, and
  refuse to signal on stale/missing/mismatched identity. Define exact results for
  normal exit, timeout, launcher crash, and partial cleanup.
- **R4:** Specify evidence and recovery semantics before cleanup: preserve logs,
  final output, receipts, task revision, process identity, and recoverable artifacts;
  make settle/retry behavior idempotent and prevent a late predecessor from
  publishing after a successor. State how an operator disables or rolls back the
  mechanism.
- **R5:** Define a hermetic test plan using fake disposable process trees and
  controlled clocks for normal completion, TERM-responsive child, TERM-ignoring
  grandchild, stale PID/process-group reuse, launcher crash, repeated reconcile,
  and restart recovery. Tests must prove unrelated processes receive no signal.

Decision deliverable: alternatives, recommended semantics, risk analysis, migration
and rollback plan, exact acceptance scenarios, and owner decision. Research may use
only synthetic disposable processes; no live kill, restart, schedule change, timer,
or production implementation is authorized by ADQ-02.
