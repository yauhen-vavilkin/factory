# Developer Flow — Conceptual Target Architecture

Status: **canonical direction**. This document defines the accepted
conceptual architecture for Developer Flow. It is deliberately **not** an
implementation plan: no milestones, no class inventories, no module
prescriptions. Product intent is defined in
[`PRODUCT.md`](PRODUCT.md). No implementation roadmap is authoritative
until a separate recovery plan is approved.

The core decision: **thin orchestration around a cohesive mature coding
runtime**. Factory owns the boundaries of the engineering loop; the coding
runtime owns the loop itself.

---

## Architecture overview

```text
Task (Jira issue / task description)
  ↓
Assess — understand · resolve repositories · readiness
  ├─ NEEDS_DECISION ─────→ Human decision → resume from durable state
  ├─ BLOCKED_ENVIRONMENT (missing capability)
  └─ READY
  ↓
Cohesive coding runtime            ← Factory provides budgets, cancellation,
(explore · plan · edit ·                credential, and freeze boundaries
 build · test · debug · self-review)
  ↓
Freeze exact candidate (immutable identity)
  ↓
Independent task-specific checks ─────────────────┐
  ├─ repairable defect → Repair → new candidate   │  every repair creates
  │   → re-freeze → full re-check                 │  a new candidate and
  ├─ environment gap → BLOCKED_ENVIRONMENT        │  invalidates prior
  └─ checks green ────────────────────────────────┘  evidence
  ↓
Semantic review (independent reviewer)
  ├─ repairable finding → Repair → new candidate → full re-verify + re-review
  ├─ decision required → Human decision → resume
  └─ accepted
  ↓
Verified candidate (exact identity + complete evidence chain)
  ↓
Trusted delivery — Factory-side reproduction of the exact candidate
                  → branch / push / PR
```

The important property is **closed-loop responsibility for what happens
next**: every outcome of every phase has a defined, owned successor
(repair, escalate, deliver, fail honestly). The sequence above is not a
pipeline of gates; it is one responsibility chain with bounded loops.

## Responsibility boundaries

| Owner | Owns | Never owns |
|---|---|---|
| Deterministic Factory logic | task lifecycle; readiness/routing; authoritative repository/revision selection; environment capability contract; sandbox/resource/credential lifecycle; cancellation and budgets; candidate freeze; evidence/artifact persistence; trusted delivery | engineering judgment |
| LLM reasoning (Factory-side) | assessment judgment; repository ambiguity investigation; semantic review; framing human decisions | authority over deterministic facts (state, identities, check results) |
| Coding runtime | the cohesive engineering loop: repository exploration, local implementation planning, edits, exploratory commands, builds/tests, debugging, reacting to compiler/test failures, self-review | delivery credentials; task outcome authority |
| Independent semantic reviewer | semantic judgment of a frozen candidate against the task | re-implementing the change |
| Human | genuine decisions and blocker resolution | routine engineering supervision |

## Assessment / readiness

Before expensive coding begins, the following must be known:

- the task is understood sufficiently to implement (goal, acceptance,
  constraints, definition of done);
- implementation repository candidate(s) are resolved;
- the authoritative base / revision for each is selected;
- required environment capabilities are known, present or declared missing;
- known missing information is identified;
- whether the system may proceed autonomously is decided.

Assessment must be **proportional**: a trivial, well-scoped task gets a
lightweight check, not a heavyweight analysis phase. Assessment failure
modes are `NEEDS_DECISION` (ambiguity) and `BLOCKED_ENVIRONMENT`
(missing capability) — not generic errors.

## Repository resolution

- Deterministic evidence narrows the candidate set first: issue-key →
  repository conventions, code-search hits, module ownership signals.
- Investigation (read-only, LLM-assisted where useful) resolves remaining
  ambiguity by looking at the actual workspace.
- **Context repositories** (read for understanding) are distinct from
  **implementation targets** (that may receive changes).
- Multi-repository work is recognized as a normal case, not an exception.
- Material ambiguity that cannot be resolved from evidence becomes
  `NEEDS_DECISION` with the discovered facts and concrete options — never
  a silent guess.

## Coding runtime ownership

The coding runtime normally owns the cohesive engineering loop:
repository exploration; local implementation planning; edits; exploratory
commands; builds and tests; debugging; reacting to compiler/test failures;
self-review.

Factory must **not** duplicate this loop. Concretely, Factory should not
grow its own micro-planner, editor, test-runner wrapper, or debug
strategist around the runtime. Factory's value at this boundary is:
budgets, cancellation, credential isolation, resource lifecycle, and the
freeze that turns the runtime's work into an exact candidate. A
multi-agent bureaucracy (planner → architect → coder → tester → reviewer
→ fixer inside Factory) is explicitly rejected unless future empirical
evidence proves a specific boundary necessary.

## Environment capability contract

Required capabilities (for example: "can run Docker", "can run
Testcontainers-based integration tests") are declared as a contract
**before** the expensive model loop begins.

- A check that is **known impossible** under the current contract must
  fail before or outside the coding loop — not be discovered mid-loop and
  not be misclassified as a coding failure.
- The concrete mechanism (Docker-in-Docker, host socket, remote runner,
  …) is intentionally not prescribed here; it is an implementation
  decision justified by real workload.

## Candidate boundary

- A candidate is an **exact, immutable snapshot**: the full change
  content (patches/trees) plus its authoritative metadata (repositories,
  base revisions, task identity).
- Freezing assigns the candidate a stable identity.
- All downstream activity — independent checks, semantic review, repair
  accounting, delivery — refers to that same identity.
- Any change to the content produces a **new** candidate and invalidates
  prior verification and review evidence. There is no such thing as
  patching evidence in place.

## Independent verification

- **Required checks** derive from the authoritative task / profile /
  policy — for example the module's real build-and-test contract. They
  are task-specific, not generic.
- **Advisory quality checks** may run and report, but must never silently
  redefine task or benchmark success.
- Factory must not strengthen a task contract merely because a generic
  verifier can run a stronger command. Contract changes require explicit
  task/operator authority.
- Verification failure classification: repairable defect → repair;
  environment gap → `BLOCKED_ENVIRONMENT`; Factory fault → `ERROR`.

## Semantic review

Purpose: detect **semantic mismatch, scope mistakes, unsafe implementation
choices, or requirements violations** that deterministic checks cannot
establish — "does this change actually do what the task meant, and is it
safe to propose?"

- The reviewer is conceptually a separate reasoning context, grounded in
  the task and the frozen candidate — **not** another full implementation
  agent and not a co-author of the code.
- Output is classified findings: repairable, decision-required, or accept
  — with reasons, not rewritten code.
- Review findings route the same way as verification findings.

## Bounded repair

- Findings that plausibly benefit from **more coding** go back to the
  coding runtime as repair input: compile/test defects caused by the
  candidate, semantic defects of repairable type.
- Findings that do **not** belong in repair: environment gaps (blocker),
  requirement ambiguity (decision), Factory/protocol faults
  (infrastructure error).
- Repair is bounded by budget/policy and by diminishing signal. No
  arbitrary fixed iteration count is imposed without justification; the
  bound is a policy decision, not a constant.
- Each repair produces a **new candidate**, re-freezes it, and re-runs the
  full independent verification and review chain for that new identity.

## Human-in-the-loop

A human request is a **structured decision artifact**, containing:

- discovered facts (what is known and how it was established);
- the unresolved question;
- why the decision matters (consequence of each direction);
- concrete options with trade-offs, when available;
- the minimum question(s) required to continue.

The workflow **resumes from durable state** after a decision — it never
restarts investigation from scratch. Routine engineering steps do not
create decision artifacts.

## Trusted delivery

- The coding sandbox is **not** the trusted delivery boundary.
- Trusted Factory-side delivery logic **reproduces the exact verified
  candidate** (identity-checked) before any branch / push / PR operation
  receives GitHub credentials.
- The coding runtime may draft suggested commit and PR text; it does not
  receive production delivery credentials by default.
- Delivery operates on the verified candidate identity — never on
  mutable sandbox state.

## Failure taxonomy

| Class | Meaning | Outcome |
|---|---|---|
| Coding / implementation failure | The runtime attempted the task within capability and the result is not good enough | `FAILED` (with evidence) |
| Environment blocker | Required capability missing | `BLOCKED_ENVIRONMENT` |
| Human decision | Genuine ambiguity or choice | `NEEDS_DECISION` |
| Factory / infrastructure error | Factory, protocol, or runtime-infrastructure fault | `ERROR` |
| Cancellation | Operator or budget termination | `CANCELLED` |

Correct classification is a product requirement: misclassification (e.g.
recording an environment blocker as a coding failure) corrupts both the
operator's trust and the success metrics.

## Anti-overengineering principles

- **No one agent per conceptual box.** Boxes in the overview are
  responsibilities, not prescribed agents or classes.
- **No abstractions for hypothetical future repositories** or workloads;
  generalize after the second real case, not before the first.
- **No infrastructure before a real workload demonstrates the need.**
- **Real provider execution enters validation early.** Mocks and scripted
  models prove plumbing only — never behavior or product readiness.
- **Green internal tests are not product success.** A milestone with all
  Factory tests passing says nothing until a real Developer Flow behavior
  is demonstrably enabled or made more reliable.
- **Tests protect understood behavior**; they must not become the source
  of product requirements.
