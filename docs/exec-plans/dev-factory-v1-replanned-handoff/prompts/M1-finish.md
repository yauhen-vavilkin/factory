Confirm the existing task-preparation checkpoint and adopt the Pi-first execution plan in the local yauhen-vavilkin/factory checkout. This is not a request to redesign or rebuild admission.

The handoff package is:
`docs/exec-plans/dev-factory-v1-replanned-handoff/`

Read applicable AGENTS.md files, this package's README.md, EXECUTION_RULES.md, ARCHITECTURE.md section 4, and the M1 section of MILESTONES.md. Inspect the existing status and M0/M1 reports under `docs/exec-plans/dev-factory-v1/`. No conversation history is required.

Starting evidence: inspected remote HEAD 9f46a1d9f52b96558116452e6b15f24ba8e650bb already records M1 ACCEPTED, with implementation commit b6ba5415f8e4ba54736454a36523264968a23e90. This is reported evidence, not proof of the local checkout. Record local branch, HEAD and staged/unstaged/untracked changes; preserve user work and do not reset/stash/clean.

Deliver the smallest stable checkpoint:
- Verify that existing explicit task preparation preserves the full task, exact repository/SHA, trusted profile and verification-plan selection, with resolved or explained-blocked intent.
- Reuse completed code and validation evidence when applicable. Rerun only focused checks needed for relevant drift or missing evidence. Do not rerun a full-day baseline or prune working catalogs/hashes merely because the previous plan was oversized.
- Fix only a reproduced gap preventing that preparation capability. Keep actual environment-freezing dependencies explicit; do not fabricate a seed/image/baseline hash to claim readiness.
- Adopt plan `pi-minimal-v2`. Add a concise scoped pointer to this package in AGENTS.md and mark the old remaining implementation roadmap superseded without deleting historical reports. Preserve unrelated repository instructions.
- Create this package's working STATUS.yaml from STATUS.template.yaml only if absent; otherwise merge current evidence. Preserve the old plan's status separately. Create `reports/M1.md` in the new package.

The accepted architecture is HYBRID: external Pi owns coding; Factory owns preparation, sandbox, candidate, verification and outcome. The new plan, including its explicit deferrals, governs future scope. Do not add runtime integration or more preparation generalization in this checkpoint.

Fix ordinary in-scope failures autonomously. A genuine unresolved prerequisite or specification conflict is BLOCKED with evidence; tests must not be weakened. Keep changes local and narrowly staged, preserving unrelated work.

Acceptance: local evidence and drift recorded, explicit preparation usable, old instructions no longer drive the active roadmap, and no discarded working mechanisms. If already satisfied, a plan/status/report-only result is correct.

Write a concise report with commands actually executed, exit codes/counts where relevant, reused evidence, product changes if any, and blockers. Commit only your changes when safe. Return a brief Russian summary with outcome, report path and resulting commit SHA.
