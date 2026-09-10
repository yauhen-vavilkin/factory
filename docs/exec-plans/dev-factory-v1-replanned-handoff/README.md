# Dev Factory — Pi-first implementation handoff

**Plan:** `pi-minimal-v2` · **Prepared:** 2026-09-10 · **Deliverable:** specifications and implementation prompts, not a code patch.

## Starting point — do not start over

The repository has moved since the original research. At inspected remote HEAD **`9f46a1d9f52b96558116452e6b15f24ba8e650bb`**, the committed status and report already mark **M1 ACCEPTED**, identifying implementation commit `b6ba5415f8e4ba54736454a36523264968a23e90`. They report 396 pure tests and focused admission/router/engine checks. These are **repository-reported results**, not tests rerun here. The local checkout and uncommitted changes remain unknown. [R1–R3](SOURCES.md)

Consequently, `M1-finish.md` is a short **checkpoint/plan-adoption prompt**, not another admission implementation or pruning project. It becomes a repair task only when local evidence shows a specific gap. Keep working M0/M1 code.

## Sequence

| Milestone | Observable result | Default implementation model |
|---|---|---|
| **M1 — Checkpoint and adopt** | Existing preparation accepted locally; conflicting old instructions retired | GPT-5.6 Sol / Medium |
| **M2 — Pi through Factory** | Real pinned Pi executes native tools in Docker, through the Factory flow, against a scripted endpoint; candidate independently checked | GPT-5.6 Sol / Medium |
| **M3 — First real FOLIO run** | `MODSIDECAR-208` submitted from its Markdown task; actual live result, patch, verification and usage inspectable | GPT-5.6 Sol / Medium |
| **M4 — Repeat and stabilize** | Fresh runs, cancellation/restart checks, targeted fixes, tested local runbook | GPT-5.6 Sol / Medium |

The first FOLIO execution belongs to **M3, before general hardening**. M4 is a bounded stabilization batch, not an invitation to implement the deferred backlog. No mandatory expensive-model milestone: consider one focused higher-reasoning review only for a reproduced, unresolved process-lifecycle/security problem. Model selection is an execution recommendation, not a guarantee of quality or account availability. [S1](SOURCES.md)

## Install this package and start

From the Factory checkout, extract the ZIP into `docs/exec-plans/`. It creates a new directory without replacing the old plan:

```bash
unzip /path/to/dev-factory-v1-replanned-handoff.zip -d docs/exec-plans/
```

Then open a fresh local coding-agent session in the Factory repository and paste **`prompts/M1-finish.md`**. It verifies existing evidence, adopts this plan, and creates the working status. If that checkpoint is already recorded for this plan and the checkout has not materially changed, use **`prompts/M2-pi-vertical-slice.md`**.

Each prompt reads the small shared documents on disk. No conversation history, bootstrap research session, or issue-management system is required. Use a fresh session for each milestone; resume interrupted work from its report, not from a second competing plan.

## Definition of useful v1

One operator can configure `.env`, start the existing host-JVM/Compose workflow, submit the real Low task, inspect the actual exported candidate and independent checks, and see provider calls, tools, durations, usage coverage and the failure boundary.

**Operational workflow** and **benchmark solved** are separate facts. A fully recorded failed model attempt proves the workflow ran, not that the task was solved. `SUCCESS` requires independent mandatory checks passing and means **eligible for review**, not PR-ready or safe to merge.

## Read only what is needed

- [ARCHITECTURE.md](ARCHITECTURE.md): ownership, flow, integration locations and essential safeguards.
- [PI_INTEGRATION.md](PI_INTEGRATION.md): exact runtime/process/proxy contract.
- [LOCAL_RUN.md](LOCAL_RUN.md): configuration and target commands.
- [MILESTONES.md](MILESTONES.md): acceptance gates and bounded work.
- [EXECUTION_RULES.md](EXECUTION_RULES.md): execution, validation and plan precedence.
- [DEFERRED.md](DEFERRED.md): explicit scope changes and deferred triggers.
- [SOURCES.md](SOURCES.md): inspected facts, reported evidence and unverified assumptions.

This package supersedes the **old M2–M5 implementation scope**, not the existing tests or historical reports. The accepted Pi decision is retained verbatim in `inputs/PI_DECISION.md`; deliberate scope amendments are listed in DEFERRED.md. No repository changes, Docker tests or live calls were performed while producing this archive.
