# Dev Factory eval pack

A small, reproducible selection of representative coding-task scenarios that
measures whether Factory creates useful software — not just whether its
components can execute a scripted successful conversation. The pack is a
JUnit tag over existing discriminating scenario tests plus a few pack-level
scenarios; it is not a new benchmark platform.

## Run the pack (one command, from the repository root)

```sh
mvn test -B -Dgroups=eval-pack -Dsurefire.failIfNoSpecifiedTests=false
```

Everything the pack runs is **hermetic**: the "model" is a scripted
ChatModel (turn-queued replies in unit scenarios, a scripted Spring AI
ChatModel in the end-to-end scenario). No API key is used and no live
provider is contacted. Live-provider runs (the two T17 diagnostics) are
outside the pack by design; any future live evidence must be reported
separately and never mixed into pack artifacts.

## Pack members and observable criteria

| Requirement | Scenario (module) | Shape | Observable criterion (asserted, not narrated) |
|---|---|---|---|
| R1 | `EvalPackHermeticRegressionFixTest` (flow-dev-factory) | regression fix | planted failing check is red on a fresh consumer checkout of the recorded base, green after `git apply` of the exported patch; `task_outcome=SUCCEEDED` persisted |
| R1 | `CodingWorkerFullExportScenarioTest.untrackedNewFileOnly...` (flow-dev-factory) | new file | patch contains the new file; consumer checkout reproduces it byte-for-byte |
| R1 | `CodingWorkerFullExportScenarioTest.noOpRunStillProducesExplicitNoChangesPatch` (flow-dev-factory) | no-op export | explicit `(no changes)` patch |
| R1 | `CodingHarnessTest.earlyCompletionWithoutChangeFailsTaskOutcomeWhenNoopNotPermitted` (sandbox) | constraint-sensitive (`allow_noop` absent) | no-op completion → `task_outcome=FAILED` |
| R1 | `CodingHarnessTest.verifiedNoOpWithPermittedContractSucceeds` (sandbox) | constraint-sensitive (`allow_noop` present + verified) | same no-op shape → `task_outcome=SUCCEEDED` only with verification evidence |
| R2/R4 | `EvalPackHermeticCounterexampleTest` (flow-dev-factory) | counterexamples | clean stops whose requested outcome is not achieved are rejected — see below |
| R2/R4 | `EvalPackHermeticFalseVerificationCounterexampleTest` (flow-dev-factory) | false-verification counterexamples (T24) | absent/skipped evidence, truncation/marker-mixed output and post-check drift (incl. untracked/lossy shapes) are all `FAILED` with their reason on both terminal paths, and the consumer consequence is proven in a fresh checkout — see below |
| R3 | `DevFactoryEndToEndScenarioTest.taskFileInInboxRunsDevFactoryToCompletion` (app) | integration (Testcontainers) | inbox file → full pipeline to COMPLETED; four artifacts land in the store |
| R3/R4 | `EvalPackHermeticDurabilityCounterexampleTest` (flow-dev-factory) | durability counterexamples (T25) | export failure, terminal exception and requeue-ordinal runs publish `INCOMPLETE` bundles whose snapshots/patches reconstruct the unique bytes — see below |
| R4 | `EvalPackHermeticSummaryTest` (flow-dev-factory) | honest summary | renders `target/eval-pack-summary.md` from persisted report data |
| R4 | `EvalPackHermeticSummaryTest.extendedSummaryCoversT24T25CounterexampleRowsWithHonestyRules` (flow-dev-factory) | honest summary (extended to the new membership) | renders `target/eval-pack-summary-t24-t25.md` rows for the six T24/T25 members from persisted report.md/bundle-manifest data under the unchanged honesty rules |
| R4 | `CodingWorkerEarlyCompletionComparisonTest` (flow-dev-factory) | evidence preservation | final answer/stop reason/tokens survive in report.md and trajectory for failing-shaped and successful runs alike |

## Pack membership

The `eval-pack` tag is the complete membership. After the T24/T25
extension it is **16 tagged executions in `factory-flow-dev-factory`**
(12 across the `EvalPack*` classes — 5 existing, plus 3 in the new
`EvalPackHermeticFalseVerificationCounterexampleTest`, 3 in the new
`EvalPackHermeticDurabilityCounterexampleTest`, and the one added
summary-extension method in `EvalPackHermeticSummaryTest` — plus the
2 `CodingWorkerFullExportScenarioTest` and 2
`CodingWorkerEarlyCompletionComparisonTest` members), together with the
2 `factory-sandbox` (`CodingHarnessTest`) and 1 `factory-app`
(`DevFactoryEndToEndScenarioTest`) members: **19 tagged executions
pack-wide, up from 12** before the extension.

## Counterexamples (acceptance must reject)

Each counterexample is a scripted run whose components pass and whose model
stops cleanly, yet the requested outcome is not achieved. Acceptance reads
the **persisted** `report.md` `task_outcome` — never an in-memory callback —
and each case also proves non-achievement through the observable criterion:

1. **Early bail without reproduction** — the model looks around, cannot
   reproduce the planted regression, stops cleanly. The consumer checkout
   still fails `check.sh`; persisted outcome must be
   `FAILED (NO_OP_NOT_PERMITTED)`.
2. **Tests green but no requested change** — the existing suite passes (the
   trajectory records a successful `exec`), the model claims completion, but
   the requested `farewell.sh` does not exist. Must be
   `FAILED (NO_OP_NOT_PERMITTED)`; passing tests never buy acceptance.
3. **`allow_noop` without verification evidence** — the contract permits a
   no-op, but the model ran no successful verification tool call. Must be
   `FAILED (NO_VERIFICATION_EVIDENCE)`.
4. **Absent or skipped test evidence** (T24) — a fixture-committed fake
   `mvnw` exits 0 printing `No tests to run.` or `Tests are skipped.` while
   the model claims BUILD SUCCESS. Must be
   `FAILED (REQUIRED_CHECK_FAILED)` on both terminal paths (never
   `CHANGES_DELIVERED`, never `NO_OP_VERIFIED`); the fresh consumer
   checkout still fails `check.sh` despite the claimed exit-0 verification.
5. **Truncated or marker-mixed check output** (T24) — a >50 KiB
   maven-shaped body whose green head survives the real `OutputLimiter`
   truncation (the skipped tail is cut), or a clean four-group summary
   mixed with maven's explicit incomplete-evidence markers. Must be
   `FAILED (REQUIRED_CHECK_FAILED)`; the consumer checkout stays red.
6. **Post-check content drift, including untracked shapes** (T24) — the
   check passes and then the model re-edits the tracked fix (`Hello` becomes
   `Howdy`), removes an untracked file the green receipt was bound to
   (`farewell.sh` vanishes from the patch), or leaves unverified untracked
   bytes that travel in the delivered patch (`scratch.txt`). Must be
   `FAILED (REQUIRED_CHECK_STALE)` — a receipt bound to an earlier
   working-tree state never certifies — and the consumer receives exactly
   the drifted or lossy content.

### T25 durability counterexamples

Each durability counterexample is a scripted run whose coding work exists
but whose run cannot complete; preservation is proven with bytes (bundle
manifests/digests, the decoded workspace snapshot, a fresh consumer
checkout), never narration. The export failure arrives as one real model
exec turn (`rm -rf .git`); the terminal-exception and requeue members use
a scripted-harness seam only because a real model turn cannot reach those
host-side failure points — the worker, sandbox, recovery store, engine and
HITL route stay real:

1. **Export failure** — the model destroys `.git` with a plain exec turn.
   The run fails naming the git diff failure and the bundle locator; an
   `INCOMPLETE (EXPORT_FAILED)` bundle carries the trajectory plus a
   worktree snapshot that decodes back to the unique bytes and the applied
   fix; the workspace is torn down only after that preservation.
2. **Terminal exception after coding work** — the harness dies mid-run.
   The worker surfaces the infrastructure failure with the original cause
   preserved; the `INCOMPLETE (TERMINAL_EXCEPTION)` bundle's snapshot still
   decodes to the unique bytes before teardown proceeds.
3. **Escalation-approved requeue under a new attempt ordinal** — attempt 1
   claims `CHANGES_DELIVERED` but its declared-output write fails against a
   host-side persist obstruction: it ends `PERSIST_FAILED` with the
   workspace retained under `sbx-<exec>-attempt-1`; a real
   `HitlDecisionService` approval requeues the step, which must run as
   attempt 2 without sweeping attempt 1's workspace or mutating its
   byte-identical bundle; attempt-1's bundled patch applies in a fresh
   consumer checkout of the recorded base.

## Honest reporting rules

- Accepted outcome comes from the persisted `task_outcome` per scenario;
  success is **never** inferred from the number of passing tests.
- Token counts are printed only when the scripted provider actually reported
  usage; a run with no reported usage prints `unknown` (both totals zero
  cannot be a real measurement).
- Latency is the measured wall time of the full coding-worker execution.
- T25 durability rows are rendered from the persisted recovery-bundle
  manifest (`failure_reason`, completeness) and the bundled trajectory; a
  run with no persisted usage record prints `unknown` for tokens and for
  its stop reason — never a fabricated number.
- Escaped defects found by the pack are reported in the run report, not
  hidden in artifact counts.

## Fresh-reviewer guidance (R5)

Assess the pack on **discrimination** and **unnecessary complexity**, not
conformity to an implementation plan: would each counterexample still be
rejected if acceptance regressed to trusting clean stops or green suites?
Is any member redundant with another (same shape, same criterion)? The
`eval-pack` tag is the complete membership; anything untagged is not pack
evidence. Discrimination evidence (R6) for the new families — named local
mutations that turn their pack members red, restored byte-identically via
git — is preserved under `target/eval-pack-discrimination/`.
