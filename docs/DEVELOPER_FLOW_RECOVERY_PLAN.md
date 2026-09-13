# Developer Flow — Recovery Plan

Status: **recovery plan** (awaiting operator approval). Product definition:
[`PRODUCT.md`](PRODUCT.md). Target architecture:
[`DEVELOPER_FLOW_ARCHITECTURE.md`](DEVELOPER_FLOW_ARCHITECTURE.md). This plan
does not modify those documents; it defines the shortest safe path from the
current repository to the accepted architecture.

## 1. Purpose

Re-establish empirical product progress: evolve the current linear
`prepare → coding → verify → finalize` path into the closed-loop Developer
Flow — readiness with honest outcomes, cohesive Pi coding runtime, frozen
candidates, plan-authoritative verification, failure classification, bounded
repair, decisions with resume, and finally trusted delivery. Preserve working
implementation; change only what a demonstrated gap requires.

### Empirical baseline (verified against repository evidence)

- Pi is the coding runtime via `dev-factory-pi.yaml`
  (`pi-prepare/coding/verify/finalize-worker`, `PiCodingRunner`, gateway,
  `factory-pi:jdk21`, model `glm-5.3-flash`). It works: **MODSIDECAR-208 was
  solved end-to-end** (low-004, 2026-09-11, `result.json` SUCCESS, ~19 min,
  11 provider calls).
- The later seven-task batch (dataset v3e, 2026-09-12/13) produced **0/7**, so
  the cumulative M4 standing is 1/8 — but the failures were heterogeneous:
  - 1 environment failure misrecorded as coding FAILED (MGRENTITLE-172: unit
    tests green; Failsafe ITs died on missing Docker/Testcontainers because
    generic verify hardcodes `mvn -B -ntp verify`, stronger than the resolved
    plan `java-maven-verify` = `mvn test`);
  - 2 repairable model defects (MGRENTITLE-192: 2 Checkstyle violations,
    otherwise green; MODSCHED-76: test-compile + Checkstyle);
  - 4 timebox kills (SIGKILL at the hardcoded 30-min budget) where the agent
    looped over impossible Docker-backed verification (10–16 MB stdout,
    repeated Testcontainers attempts).
- `BLOCKED_ENVIRONMENT` and `NEEDS_DECISION` exist **only in docs** — the
  running system can emit only SUCCESS/FAILED/INCOMPLETE/CANCELLED/ERROR.
- Consequently 1/8 is **not** a coding-runtime quality score. Roughly: one
  curated Low solved; two near-misses a repair loop plausibly fixes; four
  starved by verification-environment confusion the orchestration should have
  prevented.
- Corrections to commonly repeated numbers: 1/8 is the cumulative dataset
  standing, not a single batch result; the successful solve used
  `glm-5.3-flash` (the v3e report's "full glm-5.3" claim is contradicted by
  primary evidence); the benchmark tiers are 3 Low / 3 Medium / 2 High.
- Operational debt noticed in evidence: leaked `factory-pi:jdk21` containers
  from killed runs (operator cleanup; not a milestone).

## 2. Current-to-target gap map

| Area | Verdict | Reason |
|---|---|---|
| Task admission (file inbox, strict 256 KiB parser, idempotent content-addressed admission, reject receipts) | **KEEP** | Hardened; identity/replay semantics documented and tested. |
| Task understanding / readiness | **ADAPT** | Deterministic resolution (repo, SHA pinning, profile) is sound, but generic prepare is a no-op (`READY`, baseline `NOT_REQUIRED`); no capability proof; no real readiness judgment. |
| Repository resolution | **KEEP (v1)** | Catalog + exact SHA works; all 8 benchmark tasks are single-repo. Multi-repo stays DEFER until a real workload needs it. |
| Environment capability detection | **ADD** | Does not exist. Its absence caused the env misclassification and aggravated 4 timebox kills. |
| Coding runtime integration (Pi, gateway, budgets, recovery bundles) | **KEEP** | Proven end-to-end on a real Low task. Runtime boundary already replaceable (`PiCodingRunner` adapter). |
| Candidate freeze (`git write-tree`, patch sha256, base revision, identity checks in verify) | **KEEP** | Matches the candidate-boundary architecture; identity is already enforced. |
| Verification policy | **ADAPT** | The resolved `VerificationPlan` is ignored by the generic path, which hardcodes stronger `mvn verify`; the one good task-specific checker (`Modsidecar208Verifier`) is hardcoded Java branching inside `PiWorker`. Verification must derive from the task's authoritative plan; task-specific checks must become catalog data, not code branches. |
| Failure classification | **ADAPT** | Rich per-stage reasons exist, but coding vs environment vs Factory faults are conflated into FAILED; product outcomes `BLOCKED_ENVIRONMENT` / `NEEDS_DECISION` are unreachable. |
| Repair loop | **ADD** | None. Flow and engine are strictly forward; failed verification goes straight to finalize. |
| HITL / resume | **ADAPT** | Core already has HITL machinery (`HITL_GATE`, `AWAITING_HITL`, `HitlDecisionService`, durable state) — the dev-factory-pi flow never uses it; ambiguity is a pre-flow BLOCKED hardcoded for one task (MGRENTITLE-172). |
| Semantic review | **DEFER** | No evidence yet that an LLM reviewer adds accepted-code value on this benchmark. Advisory experiment in Milestone 3; blocking adoption only on evidence. |
| Trusted delivery | **ADD (M4)** | `LOCAL_ONLY` today; no branch/push/PR anywhere. |
| Legacy `CodingHarness` path (`java-maven-21`, `CodingWorker`) | **FREEZE** | Must not become the primary loop again. No new work; removal deferred until the Pi path covers its scenarios. |

## 3. Recovery strategy

Vertical behavior first: every milestone ends in a demonstrable Developer Flow
outcome on real FOLIO tasks with real GLM, not in infrastructure. The order is
capability-driven, not diagram-driven:

1. Make the generic path honest (readiness, plan-authoritative verification,
   environment classification) — this alone plausibly converts the env failure
   and unblocks honest Low-task runs.
2. Close the loop (classification-driven bounded repair) — this addresses the
   two near-miss model defects.
3. Make humans an exception path with durable resume — retires the hardcoded
   ambiguity special case.
4. Deliver accepted candidates through a trusted boundary.

Deterministic logic owns routing, capability, classification and identity; the
Pi runtime keeps the cohesive engineering loop; no new LLM agents are
introduced except the optional advisory reviewer experiment in M3. Existing
engine HITL machinery is reused rather than rebuilt.

Benchmark discipline: tasks are chosen per capability under test; model-quality
failures are reported separately from environment/orchestration failures; no
undifferentiated 8-task batches until the current milestone's capability is
established on representative tasks. Verification plans per task are an
explicit, recorded operator decision (curated mapping) — never silently
strengthened or weakened.

## 4. Milestone 1 — Honest readiness and authoritative verification

**Capability.** Any submitted task (not just the curated one) gets a real
readiness decision — required checks resolved from its verification plan,
their feasibility proven on the clean base before any model spend — and
verification runs exactly that plan; missing capability ends as
`BLOCKED_ENVIRONMENT` without a coding attempt.

**User-visible scenario.** Operator submits a Low task (e.g. MGRENTITLE-192)
via `./scripts/factory submit`. Prepare runs the plan's checks (`mvn test`) on
the clean base, records green baseline evidence, and declares READY. Real GLM
codes; the candidate is frozen and verified in a fresh sandbox by the same
plan. Result is SUCCESS, or FAILED with evidence attributed to the candidate.
A task whose authoritative plan requires Docker-backed ITs (e.g. MODROLESKC-424,
whose ground truth includes an IT) ends in prepare as `BLOCKED_ENVIRONMENT`
with zero provider calls — no 30-minute loop, no misrecorded coding failure.

**Minimal changes.**
- Generic verify executes the resolved `VerificationPlan` checks (catalog:
  `mvn test`), replacing the hardcoded `mvn -B -ntp verify`
  (`PiWorker.verify` generic branch).
- Generic prepare runs the same plan on the clean base and records baseline
  evidence (generalizes the existing MODSIDECAR-208 baseline pattern;
  deterministic, no new LLM). No green baseline ⇒ not READY.
- Verification plans declare required capabilities (e.g. an IT-inclusive plan
  declares `DOCKER`); the sandbox capability contract is static knowledge
  (no Docker inside `factory-pi:jdk21`); prepare classifies impossibility ⇒
  `BLOCKED_ENVIRONMENT`.
- `result.json` outcome vocabulary gains `BLOCKED_ENVIRONMENT` (reserved:
  `NEEDS_DECISION`); admission-time BLOCKED mappings updated accordingly.
- The coding task payload discloses which checks are runnable vs impossible,
  so the runtime does not burn its budget attempting Docker-backed
  verification.
- Curated per-task plan selection in `scripts/factory run-eval` (data, not
  code branches). The MODSIDECAR-208 curated path keeps working unchanged.

**Explicit non-goals.** No repair loop; no Docker/Testcontainers support; no
assessment LLM; no semantic review; no delivery; no engine changes; no
de-hardcoding of `Modsidecar208Verifier` (behavior preserved as-is).

**Real validation.**
- Regression: MODSIDECAR-208 re-run end-to-end with real GLM → still SUCCESS
  through the curated path.
- Generic path: MGRENTITLE-192 (or MODSCHED-76) with real GLM under the
  corrected plan → SUCCESS, or honest FAILED classified as candidate defect
  (Checkstyle/test-compile evidence) — either outcome demonstrates the
  capability; a SUCCESS additionally proves the first non-curated solve.
- Environment classification: MODROLESKC-424 → `BLOCKED_ENVIRONMENT` in
  prepare, zero provider calls, correct structured reason.

**Focused regression protection.** Deterministic tests pinning: generic verify
runs plan commands (never a hardcoded stronger lifecycle); plan-requiring
capability absent ⇒ `BLOCKED_ENVIRONMENT` and no sandbox/model invocation;
baseline-not-green ⇒ not READY; outcome vocabulary mapping. No new
speculative suites.

**Acceptance.** At least one non-curated real Low task completes through the
generic path with real GLM and a correct outcome; one Docker-requiring task
terminates `BLOCKED_ENVIRONMENT` before coding; zero runs in the validation
set record an environment failure as a coding FAILED.

**Stop condition.** Clean bases that cannot produce a green baseline under the
authoritative plan (repo-level breakage) or a plan vocabulary that cannot
express a task's real contract — stop and reassess the catalog design instead
of re-introducing hardcoded per-task branches or strengthening commands.

## 5. Milestone 2 — Closed loop: failure classification and bounded repair

**Capability.** A candidate that fails verification for repairable defects is
returned to the coding runtime with structured failure evidence; the repaired
candidate is a new frozen identity, fully re-verified; environment gaps and
Factory faults route to their own outcomes and never become coding requests.

**User-visible scenario.** MGRENTITLE-192 with real GLM: first candidate
misses Checkstyle; verification classifies `REPAIRABLE_DEFECT` with the
violations attached; the coding runtime receives candidate + evidence + task,
returns a repaired candidate (new tree/sha); full re-verification runs; result
is SUCCESS (bounded repair worked) or FAILED after the bound is exhausted,
with the attempt chain as evidence.

**Minimal changes.**
- Deterministic classification of verification failure evidence:
  candidate-caused (compile/test/Checkstyle/scope) → repair; environment
  signature (e.g. Testcontainers/`NoClassDefFound` patterns, including ones
  not pre-declared) → `BLOCKED_ENVIRONMENT`; identity/protocol/Factory faults
  → `ERROR`; budget/timebox exhaustion → `FAILED` (never silently retried).
- One bounded back-edge in the engine: verification classification triggers
  re-entry into coding, budgeted by policy, audit-visible; every iteration is
  a new attempt with its own candidate identity and full re-verify. No LLM
  decides routing. (Implementation may equally use a bounded sub-flow; the
  constraint is the loop lives in Factory orchestration, not inside a worker
  hiding state transitions.)
- Repair input contract: previous candidate + classified evidence + original
  task — not an undifferentiated "try again".
- Graceful timebox termination before SIGKILL where the runtime allows it
  (cancel protocol already exists in `PiCodingRunner.cancel`).

**Explicit non-goals.** No arbitrary fixed repair count as a constant — the
bound is a named policy; no semantic-review findings routed to repair yet; no
Docker capability; no assessment changes.

**Real validation.** MGRENTITLE-192 and MODSCHED-76 with real GLM — the two
known near-misses. Success = at least one accepted via repair; both must end
in honest terminal outcomes with correct classes even if the model cannot
finish.

**Focused regression protection.** Deterministic tests for the classification
routing table; repair produces a new candidate identity and invalidates prior
evidence; environment failure never enters repair; repair bound enforcement.

**Acceptance.** At least one real task accepted through defect → repair →
re-freeze → re-verify; classification audit shows no environment or Factory
failure was dispatched as a repair request in any validation run.

**Stop condition.** If repair turns near-misses into loops without converging
(diminishing signal), stop and reassess the bound/policy rather than raising
it; if engine back-edge implementation grows beyond a minimal, auditable
re-entry, stop and reconsider (e.g. bounded sub-flow) instead of growing a
workflow engine.

## 6. Milestone 3 — Genuine decisions with durable resume

**Capability.** Genuine ambiguity produces a structured `NEEDS_DECISION`
artifact (facts, question, options, consequences); the operator resolves it;
execution resumes from durable state and completes; the hardcoded per-task
ambiguity special case is retired.

**User-visible scenario.** MGRENTITLE-172 (whose source issue permitted two
valid interpretations of `maxItems`) is submitted. Resolution surfaces the
structured decision instead of a generic BLOCKED; the operator picks an option
(e.g. raise to 100); the flow resumes from stored state — no re-investigation,
no new admission — runs coding/verification with real GLM, and ends in an
honest outcome.

**Minimal changes.**
- Wire `NEEDS_DECISION` to the existing core HITL machinery
  (`HITL_GATE`/`AWAITING_HITL`/decision/resume) in the dev-factory-pi flow.
- Decision artifact as a structured, reviewable output (discovered facts,
  question, options with trade-offs) — reusing the shape already modeled by
  the existing ambiguity record, generalized: decision requests come from
  task-declared ambiguity or deterministic rules, not from a hardcoded task
  id test.
- Resume semantics: decision answer joins durable execution state; downstream
  steps see it as input; no restart from scratch.

**Explicit non-goals.** No LLM assessment agent (a deterministic +
task-declared source is sufficient for the known ambiguity class; an
assessment LLM is added only if a real decision the flow should have caught is
missed); no semantic review as a gate.

**Advisory semantic-critique experiment (same milestone, small).** Run an
independent GLM critique over the frozen accepted candidates from M1/M2
runs, non-blocking, output as classified findings. Measure: does it catch
known escaped defects (compare against ground-truth rubrics) without
excessive false blockers? Promote to a routing-relevant reviewer only with
that evidence; otherwise it stays advisory or is dropped.

**Real validation.** MGRENTITLE-172 end-to-end with real GLM:
decision artifact → operator answer → resume → honest terminal outcome.
Plus the advisory-critique run over ≥2 accepted candidates with its
hit/false-blocker tally recorded.

**Focused regression protection.** Deterministic tests: decision pauses
execution and persists; answer resumes exactly once from durable state;
routine failures never create decision artifacts.

**Acceptance.** One real ambiguity resolved through the decision path with
resume, ending in an honest outcome; hardcoded task-id ambiguity branch
removed; advisory experiment report exists with a promote/drop
recommendation.

**Stop condition.** If wiring HITL into the flow requires engine redesign
(the existing machinery should suffice), stop; if the advisory critic produces
predominantly false blockers on known-good candidates, record that and drop it
rather than tuning it indefinitely.

## 7. Milestone 4 — Trusted delivery

**Capability.** An accepted, verified candidate is reproduced Factory-side
from its frozen identity and delivered as branch / push / PR through trusted
credentials that never exist inside the coding sandbox.

**User-visible scenario.** A task that passed M1–M3 verification is submitted
with delivery mode `DELIVER_PR`. After verification PASS, Factory checks the
candidate identity (patch sha + tree + base), applies the frozen patch to the
exact base revision in a trusted Factory-side context, pushes a branch, and
opens a PR with the runtime's suggested text clearly attributed. The PR diff
is byte-identical to the verified candidate.

**Minimal changes.**
- A delivery step (Factory-side, after verify PASS) performing
  identity-checked reproduction → branch → push → PR via the existing
  connector credential boundary; delivery operates on the candidate identity,
  never on sandbox state.
- New `deliveryMode` alongside `LOCAL_ONLY`; outcome semantics per
  `PRODUCT.md`: `SUCCESS` requires delivered-verified candidate under
  delivery mode; `LOCAL_ONLY` keeps current semantics.
- Repo-local `GH_TOKEN` handling per workspace convention; delivery-scope
  credentials only.

**Explicit non-goals.** No merge automation (PRs are eligible for review, not
auto-merge); no credentials in the sandbox; no multi-repo delivery.

**Real validation.** Deliver a genuinely verified candidate from an M1–M3 run
to a real remote (fork or designated target branch); confirm the PR diff
matches the frozen candidate byte-for-byte and identity checks fail-closed on
mismatch (deterministic negative test).

**Focused regression protection.** Identity mismatch blocks delivery;
delivery cannot run on non-verified or stale identities; no sandbox credential
leakage path (static/config assertion).

**Acceptance.** One real verified candidate delivered as a PR whose diff
equals the verified candidate exactly, through credentials isolated from the
coding runtime.

**Stop condition.** If trusted reproduction cannot guarantee byte-identical
delivery (e.g. repo formatting churn), stop and reassess the reproduction
mechanism — do not deliver approximations.

## 8. Deferred work

- **Docker/Testcontainers execution capability** (sibling service containers
  or equivalent) — only after `BLOCKED_ENVIRONMENT` classification is proven
  and the operator decides the IT-requiring workload justifies it; host socket
  mount remains forbidden.
- **Semantic review as a blocking gate** — pending M3 advisory evidence.
- **Medium/High execution profiles** (longer budgets, IT-inclusive plans,
  larger diffs) — after Low-task capability is stable; MODSCHED-70 and
  MGRENTITLE-161 are not targets until then.
- **Multi-repository tasks; multi-candidate repos** — after a real workload
  requires them.
- **Legacy `CodingHarness`/`java-maven-21` path removal** — frozen now; remove
  once the Pi path demonstrably covers its scenarios.
- **De-hardcoding `Modsidecar208Verifier` branches into catalog data** —
  cleanup after the generic plan mechanism proves equivalent expressiveness.
- **Coding-runtime/model change** — only on empirical evidence of a Pi-specific
  limitation (not a Factory/environment/model-tier issue). Current pairing:
  Pi + `glm-5.3-flash` via gateway.
- **Cost accounting** (`UNPRICED` usage today) — when metric work needs it.
- **Broad benchmark batches** — only after each milestone's capability is
  established on representative tasks.

## 9. Global stop rules

- Stop if a milestone grows substantially beyond its single user-visible
  capability.
- Stop before introducing any new subsystem not required by a demonstrated
  blocker (no Docker-in-Docker, event stores, capability frameworks, provider
  redesigns, agent SDKs, workflow engines).
- Never weaken a task's authoritative requirement to obtain green results;
  never strengthen it silently — plan selection per task is recorded and
  operator-owned.
- Never convert an environment failure into a coding failure (or vice versa);
  classification correctness outranks outcome counts.
- No repeated review/verify cycles without evidence they improve accepted
  code; repair bounds are policy decisions, not ratchets.
- No broad benchmark batches while the current capability is unproven on
  representative tasks.
- A milestone is not product-successful because Factory's own tests are green;
  only the real-task outcome evidence counts.
- Do not resume or extend this plan beyond the approved milestone without
  operator approval (`AGENTS.md` rule).

## 10. Evidence required before declaring recovery complete

1. Cumulative real-task run records (v1+ of the dataset) with per-run class
   split: model/coding vs environment vs Factory vs decision — no environment
   failure recorded as coding failure.
2. At least one non-curated Low task accepted end-to-end with real GLM under
   plan-authoritative verification (M1).
3. At least one accepted solution via bounded repair (defect → repair →
   re-freeze → full re-verify) and correct routing across all failure classes
   (M2).
4. One genuine ambiguity resolved through `NEEDS_DECISION` with durable
   resume; hardcoded ambiguity branch gone; advisory-critique evidence with a
   promote/drop decision (M3).
5. One trusted delivery whose PR diff is byte-identical to the verified
   candidate, credentials isolated (M4).
6. Verification-authority audit: every graded run maps to its resolved plan
   and checks — no run graded by an undeclared stronger command.
