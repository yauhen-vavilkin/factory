# Execution rules

## Precedence and migration

This package is the user's approved **re-plan**, not an append-only expansion of the old plan. For remaining implementation scope, use this package over the old M2–M5 prompts/SPEC. HYBRID/Pi ownership is fixed. Preserve historical documents/reports; mark their future scope superseded, do not retroactively rewrite acceptance evidence.

Read applicable AGENTS.md instructions. During plan adoption, amend only the obsolete Dev Factory plan pointer/policy so it does not re-import the old roadmap; preserve unrelated instructions. Record the reason explicitly. Unresolved higher-level repository/security conflicts require clarification, not silent bypass.

Use `docs/exec-plans/dev-factory-v1-replanned-handoff/` as the package path. Create its working `STATUS.yaml` from STATUS.template.yaml only if absent, merging actual local facts. Use `reports/M1.md` through `reports/M4.md` for this plan; old reports remain in their original directory. A hash stored inside its own commit cannot be the final commit hash: store the starting/implementation checkpoint and return the resulting commit SHA separately, without endless amendment loops.

## Work discipline

Implement the requested capability. Inspect relevant existing code first, search narrowly, and read full logs from saved files only when failure excerpts are insufficient. Keep a short worklist; no extra planning framework, issue explosion, parallel overlapping edits or automatic agent swarm. Consult pinned upstream docs for a concrete compatibility question, not to repeat runtime selection.

Prefer flow/worker/adapter changes. A core change requires a recorded failing scenario, explanation of why extension points cannot solve it, smallest generic fix and regression. Preserve working M0/M1 mechanisms; LOC reduction alone is not a reason to rewrite or delete them.

Deliver a working narrow checkpoint early: M2's first evidence is actual Pi RPC/native tools, not a catalog of future abstractions. Track time to the first demonstrated capability. If work expands substantially before that, reduce optional scope and document the blocking dependency; don't use arbitrary LOC targets or skip mandatory safeguards.

Preserve staged/unstaged/untracked user work. No reset/clean/stash, blanket staging, force push, external PR/Jira writes or destructive data reset. Use a local feature branch when appropriate without dropping existing work. Make cohesive local commits, not a mandated giant milestone commit. Commit only the agent's files/hunks; report an inseparable mixed-file staging issue rather than committing user work.

## Validation and stopping

Run focused tests during development, then affected integration tests and one required acceptance sweep. Reuse completed evidence only when code/config/environment affecting it are unchanged. Record exact commands, durations, exit codes, selected cases, executed/skipped counts and log paths. Zero selected tests is not a pass; no live calls hidden inside automated suites.

Ordinary red tests are debugging work. Fix reproducible in-scope defects autonomously. Do not weaken assertions, reduce mandatory suite scope, fabricate hashes/receipts, silently change models or bump target dependencies.

A pre-existing unrelated failure requires baseline reproduction and an impact statement. It is not automatically a reason to repair all legacy code; it also cannot be hidden as green. Required path checks must still pass. The M1 report notes grouped Spring DB test interference: use established isolated-class runs when diagnosing it and report that limitation honestly, rather than repeatedly paying for the same failed combined command.

A genuine blocker is an unavailable required capability, contradictory contract, unsupported pinned interface, or defect that cannot be fixed within accepted boundaries. Save progress/evidence and stop that acceptance, not the entire repository's unrelated hermetic work. Provider/account/environment failures are not automatically Pi NO-GO.

## Durable report

Keep STATUS small: plan ID, current milestone/state, branch, starting/checkpoint HEAD, report path, blockers, next action. No duplicated full test logs.

Each report records capability evidence, changed areas, exact validations, requirement/check IDs, side effects and remaining limitations. Separate `workflowOperational`, actual task outcome and `firstLowSolved`. Unrun tests remain NOT_RUN. Return a brief Russian summary with report and commit/artifact paths.
