# Capability milestones — pi-minimal-v2

Each milestone is one bounded implementation task. Internal checkpoints are a work order, not additional projects. The implementation agent owns routine fixes, not the freedom to expand scope.

## M1 — Confirm preparation and adopt this plan

**Prerequisite:** user's current checkout. Remote M1 is already ACCEPTED; check local state instead of assuming more admission work is necessary.

**Capability:** a usable preparation checkpoint and exactly one active plan. **Likely changes:** planning/status pointers only; a focused `factory-flow-dev-factory` repair only for a demonstrated preparation defect.

**Minimum work:** inspect git status, existing M0/M1 reports/status and the explicit-task path. Keep schema, catalogs, parser, hashes and tests that already work. Do not prune merely to reduce LOC. Adopt the new plan in a short scoped AGENTS.md pointer and new working status without deleting old evidence. Record that the existing seed/network/profile assumptions will be adapted by the Pi slice, not rebuilt now.

**Validation:** inspect prior command evidence and relevant diffs; rerun focused preparation tests only when needed for changed/unverified local code. Verify explicit repository/SHA/profile/check lookup, full task text and READY-or-explainable-pending intent. A final environment contract is not yet required. Missing old SPEC.md is not a reason to recreate it from memory.

**Acceptance M1-C1:** local M0/M1 evidence and drift recorded. **M1-C2:** supported explicit intent path usable or exact defect fixed/tested. **M1-C3:** old M2–M5 marked superseded and existing work preserved. If already satisfied, produce the checkpoint report with no product diff.

**Non-goals:** new resolver intelligence, new schemas for their own sake, deleting tested features, rerunning every old suite repeatedly.

**Next evidence:** confirmed integration points and local baseline, then M2.

## M2 — Real Pi through the existing Factory flow

**Prerequisite:** M1 checkpoint; local Docker/JDK/build dependencies. No paid key required.

**Capability:** a submitted tiny fixture runs through `prepare → Pi → independent verify → finalize`, using actual pinned Pi native tools and a scripted HTTP model endpoint. The same adapter/process path will serve the FOLIO task.

**Minimum work, in this order:**

1. Prove real no-TTY Pi RPC and one native file/shell tool operation through a small Java test driver using the production `CodingRunner`/process-session implementation. Save the raw exchange immediately. Do this before building any broad flow, proxy or telemetry framework.
2. Add bounded streaming/event capture, settled-state handling and whole-workload stop/export. Implement the small fixed-route gateway and its run budget. Use a fake upstream while proving security and protocol mechanics.
3. Register the new Pi flow/workers and operator-selected inbox event, reusing admission/artifact services. Adapt M1's seed/profile/readiness assumptions exactly as ARCHITECTURE.md specifies. Put fake source/model providers behind explicit test configuration, not production bypasses or a general local-path admission exception.
4. Complete a tiny Maven normalization fixture: trim strings, remove null/blank entries, deduplicate preserving order. Pi actually edits it; trusted tests outside the coding workspace reject a deliberately wrong implementation. Persist result/patch/session/usage references and expose a scripted smoke command.

**Likely areas:** flow-local runner/workers/profile catalog/contract guard; sandbox process/lifecycle additions; Pi image and narrow gateway; app wiring/launcher. No new Maven module is required merely for the runner or gateway.

**Validation:** focused adapter unit tests; actual Pi + real Docker + fake HTTP; one full Factory scripted fixture through PostgreSQL. Required cases:

- **M2-C1:** pinned non-root/read-only/no-TTY startup; no project settings/extensions autoload and no real provider key/host socket in coding;
- **M2-C2:** UTF-8/framing/correlated responses, requested model mismatch, 401/429, and `agent_end` followed by retry; only settled completes normally;
- **M2-C3:** >=2MiB tool output, preserved early error/full-output reference, bounded buffers, observed native compaction without losing the task contract; forced compaction is tested with scripted responses, not an expensive live session;
- **M2-C4:** cancellation/deadline kills detached children, gateway token/budget cannot be bypassed, forbidden network/host destinations fail;
- **M2-C5:** stopped-workspace export survives a Pi crash, includes all relevant Git change types, and uses fresh trusted metadata;
- **M2-C6:** full scripted Factory flow passes the fixture; an intentionally wrong candidate fails independent checks; no duplicate legacy flow, hidden custom-harness call or repeat coding on cleanup failure;
- **M2-C7:** exact run references/raw usage/events retained; missing data remains unknown; affected legacy tests remain passing.

Run existing `./scripts/factory validate --unit` at the acceptance boundary. Add a focused Pi integration selection and `./scripts/factory smoke --pi --scripted`; include test names/counts and actual log paths. A fake Pi process alone cannot satisfy this milestone.

**Non-goals:** three live rehearsals, FOLIO-specific checker, JDK17, SDK bridge, runtime fallback, new editing loop, global recovery/accounting redesign.

**Next evidence:** actual Pi protocol/tool/runtime GO; missing account credentials do not block M2. A reproducible unsupported Pi interface is NO-GO; preserve the reproducer and request a runtime decision instead of forking Pi or automatically implementing OpenCode.

## M3 — First real MODSIDECAR-208 execution

**Prerequisite:** M2 machine tests; configured upstream account and explicit live authorization; approved FOLIO dependencies accessible.

**Capability:** the operator configures `.env`, invokes the real eval through Factory and inspects a final result, exported candidate, independent checks and usage. No new harness or generic infrastructure.

**Minimum work:** finish the thin LOCAL_RUN.md commands and effective config; create the curated Markdown-to-task mapping and task-specific verification plan; use fresh private Maven resolution through approved gateway routes; qualify the endpoint once; execute one real Low attempt with a new runKey.

**Exact benchmark:** `evals/tasks/MODSIDECAR-208.md`; `folio-org/folio-module-sidecar`; base **`c13e0383d9283ef554c195cf5357bb3c6eeb4e65`**. Read the full task. No solutions, future refs, PR patch, this implementation package or grader material in Pi's context. The task explicitly requires default 8, environment override, README documentation and `mvn test`. [R9, B1](SOURCES.md)

**Required verification:**

- **M3-C1:** fetch the exact source and run `mvn -B -ntp clean test` on the clean base. Use approved pinned system Maven when no verified wrapper is present; never blindly run nonexistent `./mvnw`. Here dependency access is allowed through the gateway, so `-o` is not mandatory. Missing historical dependencies are a baseline blocker, not permission to update them.
- **M3-C2:** prove the task-specific check fails on the base for the expected absent setting, not because tooling or dependencies fail.
- **M3-C3:** on the frozen candidate, a small trusted SmallRye configuration probe verifies default 8 and environment override 12 in separate clean processes. Use baseline-derived pinned libraries, candidate configuration as read-only data and no candidate-produced classes/cache. Check the README variable/default/description/required flag. Do not build a universal grader or compare exact human patch bytes.
- **M3-C4:** fresh checkout + exported full patch + fresh verifier state passes `mvn -B -ntp clean test`, with fresh nonempty Surefire XML and expected unit execution. Record skipped counts; no required suite may disappear. This task's AC is unit/test scope, not proof of all integration tests. Unexpected mandatory Docker-backed tests produce INCOMPLETE.
- **M3-C5:** candidate/base/hash identity, no-op policy and scope hold. Relevant additional tests/NEWS edits are allowed; removal of mandatory checks or unexplained test/build-bypass changes cannot get automatic success.
- **M3-C6:** effective model/thinking/options, upstream calls including retries/compaction, nullable token/cache usage, tool activity, latency, failure stage, cleanup and estimated/unknown cost are inspectable. Preflight usage is separate from benchmark usage; no double counting of native stats.
- **M3-C7:** default commands preserve completed artifacts and user's checkout, make no PR/Jira writes, and never replace real Pi execution with a manually patched target.

**Likely areas:** flow-specific preparation/verifier/finalizer and eval descriptor, existing app/launcher and summary surface. Gateway adds only the fixed Maven artifact routes required by this profile.

**Validation order:** affected automated cases → scripted full-flow smoke → explicit live doctor → one live eval → exported-candidate replay. Run unaffected full suites once at the release boundary, not per edit. Record required test counts and wall times. Do not rerun a failed live model until green; retain that failed experiment.

**Acceptance has two fields:** `workflowOperational` requires all mechanics/evidence criteria; `benchmarkOutcome` is the actual SUCCESS/FAILED/INCOMPLETE/ERROR/CANCELLED. M3 capability may be accepted with an honestly recorded model FAILED if all workflow/evidence checks passed. `firstLowSolved` remains false and overall “useful solution demonstrated” is not claimed. Missing required infrastructure/tests keeps the relevant acceptance BLOCKED.

**Non-goals:** fixing model intelligence, PR/review/Jira, supporting all repos, universal pricing or immutable caches.

**Next evidence:** the real execution's failure classification and observed operator friction define M4, not the old research backlog.

## M4 — Bounded stabilization and repeatability

**Prerequisite:** a real M3 attempt with usable evidence, or a specific M3 infrastructure blocker that can be addressed within the accepted boundary.

**Capability:** repeatable operation and a short verified runbook, with quality results separated from framework reliability.

**Minimum work:** triage M3 evidence; fix at most the few highest-impact demonstrated blockers (normally no more than three issue-sized fixes). Add a reproducing regression for each. If a proposed fix grows into a new subsystem, preserve the blocker and seek a scope decision instead of implementing it under “stabilization”.

Complete up to **three total live MODSIDECAR-208 attempts**, counting M3's attempt, with fresh runKeys/state. Preserve every failure and configuration revision. Do not spend on missing credentials or identical infrastructure errors. If the adapter/model configuration materially changes, report separate cohorts rather than a misleading success rate.

Run one interruption/restart scenario with scripted Pi and one cleanup-failure case: partial evidence retained, old workload stopped, no automatic paid replay, completed artifacts inspectable. Recheck scoped network/token revocation and storage guard with actual runtime evidence. Use existing retention/recovery; no automatic resume/fencing platform.

**Acceptance M4-C1:** mandatory checks on accepted candidates actually pass. **M4-C2:** repeated runs do not contaminate each other's workspace/cache or lose completed output. **M4-C3:** observed defects fixed/tested or explicitly blocked. **M4-C4:** LOCAL_RUN.md command sequence is reflected in the repository's tested runbook; user sees run IDs, actual quality outcomes, cost coverage, cleanup and remaining limitations.

At least one independently successful real Low result is required to claim a useful solution was demonstrated. Three unsuccessful coding attempts with sound runtime evidence become a quality blocker for a separate targeted investigation—not a reason to rebuild Factory or silently adopt another runtime. No statistical reliability claims from three runs.

**Likely areas:** only components implicated by evidence, plus runbook/eval reports. **Non-goals:** an automatic sweep of DEFERRED.md or a broad multi-repo benchmark campaign.

**Afterward:** choose a single concrete next capability, such as another Low or controlled PR delivery. Medium/High require their actual profiles and mandatory Testcontainers capability; no tests skipped to increase apparent coverage.
