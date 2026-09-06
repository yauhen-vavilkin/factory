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
| R3 | `DevFactoryEndToEndScenarioTest.taskFileInInboxRunsDevFactoryToCompletion` (app) | integration (Testcontainers) | inbox file → full pipeline to COMPLETED; four artifacts land in the store |
| R4 | `EvalPackHermeticSummaryTest` (flow-dev-factory) | honest summary | renders `target/eval-pack-summary.md` from persisted report data |
| R4 | `CodingWorkerEarlyCompletionComparisonTest` (flow-dev-factory) | evidence preservation | final answer/stop reason/tokens survive in report.md and trajectory for failing-shaped and successful runs alike |

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

## Honest reporting rules

- Accepted outcome comes from the persisted `task_outcome` per scenario;
  success is **never** inferred from the number of passing tests.
- Token counts are printed only when the scripted provider actually reported
  usage; a run with no reported usage prints `unknown` (both totals zero
  cannot be a real measurement).
- Latency is the measured wall time of the full coding-worker execution.
- Escaped defects found by the pack are reported in the run report, not
  hidden in artifact counts.

## Fresh-reviewer guidance (R5)

Assess the pack on **discrimination** and **unnecessary complexity**, not
conformity to an implementation plan: would each counterexample still be
rejected if acceptance regressed to trusting clean stops or green suites?
Is any member redundant with another (same shape, same criterion)? The
`eval-pack` tag is the complete membership; anything untagged is not pack
evidence.
