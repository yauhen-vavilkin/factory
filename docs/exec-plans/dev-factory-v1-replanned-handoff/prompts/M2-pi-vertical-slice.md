Implement the smallest real Pi-backed Factory vertical slice in the local yauhen-vavilkin/factory checkout.

The package is `docs/exec-plans/dev-factory-v1-replanned-handoff/`.
Read applicable AGENTS.md files, this package's STATUS.yaml, EXECUTION_RULES.md, ARCHITECTURE.md, PI_INTEGRATION.md, the M2 section of MILESTONES.md and reports/M1.md. Consult LOCAL_RUN.md only for the relevant launcher interface. This package supersedes the old over-engineered remaining milestones.

Require the preparation checkpoint to be accepted locally. Record HEAD and working-tree state; preserve unrelated changes. Inspect relevant existing integration points before coding. The runtime choice is fixed; do not repeat comparative research.

Required result: a submitted tiny Maven fixture runs through the actual Factory engine and PostgreSQL, a disposable Docker environment, real pinned Pi native tools, exported candidate, fresh independent verification and a structured final result. The upstream is scripted/fake; no paid credential is required and no real provider request is authorized by this task.

Implement in this order:
1. Prove actual no-TTY Pi RPC and a native edit/shell operation with a small Java test driver using the production CodingRunner/process-session code. Save the raw exchange. Do this before expanding surrounding infrastructure.
2. Complete the small replaceable CodingRunner adapter and full-duplex sandbox process-session boundary. Implement incremental UTF-8 JSONL, concurrent stdout/stderr capture, correlated command responses, agent_settled completion, bounded artifacts, cancellation and whole-container stop. Do not wrap blocking exec as an RPC protocol or add a new agent loop.
3. Add the narrowly scoped fixed-route gateway and run budgets described in PI_INTEGRATION.md. The upstream key never reaches the coding container. Prove actual network/credential restrictions against a fake endpoint before declaring isolation.
4. Register dev-factory-pi with its separate inbox event and prepare/Pi/verify/finalize workers. Reuse admission, artifacts and suitable exporter/recovery helpers. Keep the legacy flow/worker for regressions; no duplicate admission or silent fallback.
5. Apply only the explicit M1-to-Pi contract/profile adaptations in ARCHITECTURE.md: truthful fresh-resolution mode, actual Pi image/platform, gateway network and relevant unknowns. Keep legacy strict behavior. No fake readiness values or seed architecture.
6. Complete the scripted normalization fixture from MILESTONES.md. The production adapter runs real Pi; trusted tests outside coding accept a correct candidate and reject an intentionally wrong one. Expose the scripted smoke and inspectable run artifacts.

Execute all M2-C1 through M2-C7 checks. In particular prove retry after agent_end, native compaction, large output/early-error preservation, disabled project autoload, cancellation of detached descendants, safe partial export after crash, gateway budget enforcement, no repeat coding after cleanup failure, and independently rejected false success. Capture actual Docker/Pi evidence, not only mocked-process tests.

Run focused tests during implementation, affected integration tests, `./scripts/factory validate --unit` at acceptance and the implemented `./scripts/factory smoke --pi --scripted`. Record exact selections/counts/exits and log paths. Preserve affected legacy behavior. Missing Docker is a blocker; missing a paid key is not.

Work autonomously through ordinary failures. Keep a short worklist and durable checkpoints in reports/M2.md/STATUS.yaml, with time to the first real Pi tool operation. Use targeted searches and saved logs; no broad framework refactor, new provider platform, immutable-cache system or core redesign. A necessary core change requires a reproducer and smallest tested fix.

On completion, map M2 check IDs to evidence, report changed areas, unresolved limitations and cleanup. Accept only when required mechanical checks pass. Save safe local commits containing only your changes; no external writes. Return a brief Russian summary with outcome, commands/evidence locations and commit SHA.
