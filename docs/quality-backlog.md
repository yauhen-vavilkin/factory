# Factory quality backlog — owner-requested engineering work

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
