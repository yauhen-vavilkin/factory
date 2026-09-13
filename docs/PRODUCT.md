# Factory — Product Definition

Status: **canonical**. This document defines what Factory and Developer Flow
are supposed to be. The accepted conceptual architecture behind it is
[`DEVELOPER_FLOW_ARCHITECTURE.md`](DEVELOPER_FLOW_ARCHITECTURE.md).

## Product purpose

Factory is a **general orchestration platform** for engineering workflows.
It owns task lifecycle, durable state, boundaries, and evidence — not the
engineering itself.

**Developer Flow** is the workflow built on Factory that should behave
approximately like a **reasonably autonomous junior software developer** for
ordinary FOLIO development tasks.

## Primary user experience

The operator supplies:

- a development task — typically a Jira issue or an equivalent task
  description;
- the credentials and environment Factory is allowed to use.

The system takes ownership of:

- understanding and investigating the task;
- determining scope — repositories, authoritative base/revision, and
  environment readiness;
- implementing and debugging;
- independently verifying the result against the task's own contract;
- semantically reviewing the candidate;
- repairing when appropriate;
- producing an exact, verified candidate;
- trusted delivery (branch / push / PR).

Human intervention is an **exception path** for genuine decisions and
blockers — not a mandatory gate after every phase.

## Outcomes

Product-level terminal outcomes of a Developer Flow execution
(product semantics, not implementation semantics):

| Outcome | Meaning |
|---|---|
| `SUCCESS` | An exact verified candidate was produced and delivered through trusted delivery; the task's authoritative contract is satisfied. Eligible for review — not automatically mergeable. |
| `NEEDS_DECISION` | Progress stopped on a question only a human can answer (e.g. ambiguous requirements, conflicting valid options). Durable state is preserved; the flow resumes from it. |
| `BLOCKED_ENVIRONMENT` | A required environment capability is missing (e.g. no Docker / Testcontainers). An environment blocker, not a coding failure. |
| `FAILED` | The task was attempted within available capability and the engineering result is not good enough. Evidence shows what was attempted and why it failed. |
| `ERROR` | A Factory or protocol/infrastructure fault — the run does not reflect the task or the coding runtime (crash, protocol failure, lost state). |
| `CANCELLED` | Terminated by operator request or by a budget/policy limit. |

## V1 autonomy target

V1 is **not** a universal senior engineer. The useful target is:

- reliable autonomous handling of an explicitly supported class of FOLIO
  **Low** tasks and **selected Medium** tasks;
- correct escalation and blocker behavior for everything else:
  ambiguity → decision, missing capability → environment blocker,
  exhausted repair → honest `FAILED` with evidence.

Reliable escalation is a first-class deliverable, not a fallback shame.

## Product success metrics

Primary metrics:

- accepted solutions on representative real tasks;
- escaped semantic defects (defects a competent reviewer would have caught);
- unnecessary human interventions;
- correct blocker / HITL classification;
- successful bounded repair (defect found → repaired → re-verified);
- model and wall-time cost per accepted solution;
- looping / loss-of-intent cases (target: effectively zero).

Explicitly **not** primary product metrics:

- number of Factory tests;
- Factory LOC;
- artifacts / receipts count;
- number of internal milestones;
- number of framework abstractions.

## Non-goals

- A universal autonomous senior engineer.
- A generic multi-agent research platform.
- Rebuilding a mature coding harness inside Factory.
- A universal execution environment before actual workload requires it.
- Mandatory human approval between routine engineering steps.

## Governing rule

Every substantial change must answer:

> What real Developer Flow behavior does this enable or make materially
> more reliable?

A change that cannot answer this is not product work.
