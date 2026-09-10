Stabilize the existing Pi-backed Dev Factory workflow using its actual first-live-run evidence. This is a bounded reliability/operability task, not implementation of the deferred production backlog.

Work in the local yauhen-vavilkin/factory checkout. The package is `docs/exec-plans/dev-factory-v1-replanned-handoff/`.
Read applicable AGENTS.md files, package STATUS.yaml, EXECUTION_RULES.md, the M4 section of MILESTONES.md, DEFERRED.md and reports/M3.md. Inspect the referenced run artifacts and current code, not conversation history. Consult PI_INTEGRATION.md/ARCHITECTURE.md only for the implicated boundaries.

Starting condition: a real Pi-driven MODSIDECAR-208 attempt with inspectable evidence, or a specific documented infrastructure blocker on that path. Record HEAD and local changes and preserve unrelated work.

Deliver repeatable operation and a tested short runbook:
- Classify actual preparation/provider/Pi/sandbox/export/verification/storage problems from evidence. Select the few highest-impact reproducible fixes, normally no more than three issue-sized changes. Do not manufacture findings to fill the milestone.
- Reproduce each selected defect, add a regression, apply the smallest fix in the owning worker/adapter/configuration, and rerun affected checks. A required core edit needs its concrete blocker and smallest generic regression-tested correction.
- Prove scripted cancellation/restart and cleanup failure behavior: old workload stops, partial evidence is retained, completed artifacts remain inspectable, and coding is not automatically paid for again. Prove run-to-run workspace/cache isolation and revoked gateway tokens. Do not build distributed fencing or automatic conversation resume.
- Finish repository Quickstart/.env documentation using the actual working command sequence from LOCAL_RUN.md. Verify startup, submission, inspection/export, stop and restart; keep external writes disabled.

This task authorizes up to the remaining attempts needed for three total live MODSIDECAR-208 runs, including the earlier attempt. Use fresh runKeys and state. Preserve every result and configuration version. Do not spend on unresolved credentials/dependencies or repeat an identical infrastructure failure. Separate cohorts after a material runtime/configuration change rather than presenting them as a controlled quality comparison.

For each completed candidate, run all mandatory independent checks. Do not hand-edit the target fix, weaken verification, change the benchmark base, tune Pi intelligence opportunistically, or silently use another runtime/model. Failed attempts remain failed with their full usage/evidence.

Run focused regressions during changes and the required affected acceptance tests at the end. Record commands, exits, test counts, timings and artifact references. Zero selected or skipped mandatory tests cannot satisfy acceptance.

If a fix expands into a new subsystem or an accepted boundary cannot be met, retain the precise blocker instead of implementing the old roadmap under the name stabilization. Ordinary in-scope test failures are debugging work and should be fixed autonomously.

Write reports/M4.md covering M4-C1 through M4-C4, all live run IDs/outcomes, nonsecret configurations, known cost/usage coverage, cleanup/restart evidence, tested runbook commands and remaining risks. At least one independently successful real Low result is required to claim useful solution delivery. If none succeeds despite sound runtime evidence, record a quality blocker and firstLowSolved=false; do not claim statistical reliability or silently rebuild Factory.

Update STATUS.yaml, preserve all historical evidence and commit only your own changes when safe. No push, PR/Jira writes, destructive cleanup or blanket staging. Return a brief Russian summary with actual capability/quality outcomes, report path, commit SHA and any blocker.
