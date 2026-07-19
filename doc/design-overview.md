# FOLIO AI SDLC Factory — Platform Design Overview

This document describes the design of the Factory's orchestration platform: the
control plane, the plugin model, the shared services every flow consumes, and
the principles and safety controls that govern them. It deliberately ignores
individual flows — a flow is a *client* of this platform, and the platform has
no knowledge of any specific flow. For the currently implemented flow and how
to run it, see the [README](../README.md); for building flows of your own on
this platform, see the [developer guide](extending-the-factory.md).

## 1. Context and goals

The FOLIO engineering ecosystem spans 450+ repositories, a hybrid
Java/Spring/Vert.x/Grails/React codebase, and a semi-annual release cadence.
Manual cognitive load (per-repo test coverage, release coordination, branch
lifecycle), release complexity, test coverage gaps, hand-executed
infrastructure runbooks, and Grails legacy modernisation debt all scale
non-linearly with the module count.

The Factory introduces an AI-driven orchestration layer with these strategic
objectives:

| Dimension | Target outcome |
|---|---|
| Efficiency | 10–20% cycle-time improvement per automated SDLC phase |
| Quality | Consistent, standardised artifacts with reduced rework |
| Composability | Flows reuse common infrastructure and invoke each other |
| Extensibility | New flows added via plugin registration, without re-architecting |
| Team enablement | Engineers shift from manual executors to reviewers and approvers |

The platform's defining characteristic is that its capability set is **not
fixed**: any SDLC automation (documentation, security scanning, performance
regression, compliance mapping, …) can be added as a registered plugin at the
team's pace, bounded by prioritisation rather than platform constraints.

## 2. Architectural principles

Eight principles govern how agents are composed, how state is managed, how
human oversight is preserved, and how the platform evolves:

1. **Shared infrastructure** — one control plane, one agent worker library,
   one artifact registry, one HITL gateway. Built once, consumed by every
   flow, including all future flows.
2. **Plugin-based flow registration** — each flow is a self-contained,
   independently deployable unit. Adding a flow means registering a descriptor
   (triggers, artifact schemas, agent composition). The control plane is never
   modified; existing flows are unaffected.
3. **Flow composition over isolation** — any registered flow can invoke any
   other registered flow as a sub-flow by passing structured artifacts through
   the shared framework. Composition is a platform capability, not a
   per-flow feature.
4. **Narrow agent scope** — each agent worker has exactly one specialised
   responsibility. Workers are stateless and reusable across flows.
5. **Immutable intermediate artifacts** — agents communicate exclusively
   through structured documents (Markdown, JSON, YAML) written to a persistent,
   versioned artifact registry. No agent-to-agent in-memory state. Any step
   can be independently restarted, manually corrected, or replaced without
   cascading side effects.
6. **Human-in-the-loop gates** — every pipeline pauses at defined checkpoints
   for human review. Gates are non-negotiable at design confirmation,
   pre-merge code review, and deployment approval stages.
7. **Bounded retry and graceful degradation** — self-correction loops are
   capped by a maximum attempt count; on exhaustion the agent persists its
   state and escalates to a human reviewer.
8. **Data isolation** — agents operate only on the files, schemas, and
   specifications relevant to their assigned task. The full repository tree is
   never injected into an agent's context.

## 3. High-level design

```
┌─────────────────────────────────────────────────────────────────┐
│                 SHARED ORCHESTRATION FRAMEWORK                  │
│                                                                 │
│  Pipeline Router   State Manager   HITL Gateway    Audit Log    │
│  routes trigger    persists exec   surfaces review immutable    │
│  events to flows   state; enforces packages to     record of    │
│  via registry      retry budgets   human approvers all actions  │
│                                                                 │
│  ┌───────────────┐ ┌──────────────┐ ┌────────────────────────┐  │
│  │ AGENT WORKER  │ │ INTEGRATION  │ │  ARTIFACT REGISTRY     │  │
│  │ LIBRARY       │ │ CONNECTORS   │ │  versioned store for   │  │
│  │ (shared)      │ │ (shared)     │ │  all flow documents    │  │
│  └───────────────┘ └──────────────┘ └────────────────────────┘  │
└───────────────────────────────┬─────────────────────────────────┘
                                │
┌───────────────────────────────┴─────────────────────────────────┐
│                  FLOW REGISTRY (PLUGIN LAYER)                   │
│        flow 1 · flow 2 · flow 3 · … · flow N (future)           │
└─────────────────────────────────────────────────────────────────┘
```

Every component above the plugin layer is flow-agnostic and data-driven. The
only place a flow "exists" from the platform's perspective is its entry in the
Flow Registry.

### 3.1 Flow Registry and the plugin descriptor

Each flow is registered as a plugin descriptor:

```
Flow Plugin Descriptor
  id:              unique flow identifier
  name:            human-readable name
  version:         semantic version of the flow definition
  triggers:        list of event types that activate this flow
  input_schema:    expected input artifact structure
  output_schema:   emitted output artifact structure
  agent_chain:     ordered list of agent worker invocations
  hitl_gates:      positions and review package definitions
  sub_flows:       references to other registered flows (optional)
  retry_policy:    per-agent retry budgets
```

Introducing a new flow — of any kind — always follows the same pattern:

1. Define the flow's triggers, input/output artifact schemas, agent chain, and
   HITL gate positions.
2. Author any new agent workers required (existing workers are reused where
   applicable).
3. Register the plugin descriptor in the Flow Registry.
4. The Pipeline Router begins routing matching events automatically.

No control-plane component is modified and no existing flow is touched.

### 3.2 Pipeline Router

The entry point of the orchestration layer. It reads the Flow Registry at
startup and on registration of new flows, receives triggering events from
integrated systems (issue-tracker status transitions, VCS webhook events, CI
pipeline hooks, sub-flow invocations from parent flows, manual initiations),
and routes them to flows based on the registered trigger contracts. The Router
is entirely data-driven — it has no hardcoded knowledge of any flow.

### 3.3 State Manager

Maintains the execution state of every active pipeline instance, including
nested sub-flow instances. Each instance carries a unique execution identifier
and, where applicable, a parent execution reference for full traceability of
composed flows. State is persisted as structured documents and survives
process restarts, agent failures, and manual interventions. Retry budgets and
escalation thresholds are enforced here, configured per flow via the plugin
descriptor.

### 3.4 HITL Gateway

Surfaces pipeline state to human reviewers at the checkpoint positions
declared in each flow's descriptor, generating structured review packages and
delivering them through the team's existing notification channels. When a gate
belongs to a sub-flow, the parent flow remains waiting while the sub-flow
awaits its human review. The Gateway has no hardcoded knowledge of where gates
appear in any flow.

### 3.5 Artifact Registry

The versioned store for all flow documents and the only communication channel
between agents. Writes are insert-only: a correction or reviewer amendment
becomes a new, attributed version rather than an overwrite, so every step's
inputs are reproducible after the fact.

### 3.6 Audit Log

Every agent action, artifact write, HITL decision, pipeline state transition,
and sub-flow invocation is recorded append-only, using one structured schema
for all flows. For composed flows the audit record preserves the full
invocation graph.

### 3.7 Integration Connectors

Shared read/write clients for external systems (issue tracker, VCS, CI/CD,
test management). Connectors are registered in a shared library and become
available to any flow that declares a dependency on them; new connectors are
added without modifying existing ones.

### 3.8 Agent Worker Library

Agent workers are stateless, single-responsibility processing units defined
once and reused by every flow that needs the capability (triage, design, test
specification, test automation, code generation, compilation guarding, test
execution, source analysis, release orchestration, backporting, validation,
documentation, …). When a future flow requires a missing capability, a new
worker is authored and added to the library; existing workers are not
modified. Each worker consumes declared input artifacts and produces declared
output artifacts — nothing else.

## 4. Data flow

A single execution, from the platform's point of view:

1. A trigger event arrives (external webhook, manual initiation, or a parent
   flow's sub-flow invocation).
2. The Pipeline Router matches it against registered trigger contracts and
   creates an execution instance.
3. The State Manager advances the execution through the flow's agent chain.
   Each agent step reads its declared input artifacts from the Artifact
   Registry and writes new artifact versions as output.
4. At each declared gate, the HITL Gateway parks the execution and publishes a
   review package; a human approves, amends (producing a new artifact
   version), or rejects.
5. Sub-flow steps create child executions of other registered flows; the
   parent waits — through the child's HITL gates — and collects the child's
   mapped output artifacts.
6. Failed agent steps retry within the flow's retry budget; exhaustion
   escalates to human review instead of failing silently.
7. Every event along the way is written to the Audit Log.

## 5. Quality and safety controls

These non-functional controls apply uniformly to all flows and are enforced by
the shared framework, not by individual flow implementations:

- **Static analysis gate** — AI-generated code must pass configured static
  analysis and style checks before a pull request is created.
- **Test data sanitisation** — test generation agents are restricted to
  predefined synthetic data templates.
- **Bounded retry** — self-correcting loops are limited to 3 automated
  remediation attempts before escalating to human review.
- **Scope boundary enforcement** — agents receive only the files, schemas, and
  specifications directly related to their task.
- **Immutable audit trail** — all executions, agent outputs, sub-flow
  invocations, and reviewer decisions are logged append-only.

## 6. Governance

- **No autonomous merge authority** — the Factory cannot merge code to master
  or release branches without human approval.
- **No production deployment authority** — the Factory can prepare and
  validate release artefacts but cannot deploy to production without explicit
  human sign-off.
- **Reviewer accountability** — humans who approve AI-generated artefacts
  assume the same accountability as reviewers of human-authored work. AI
  origin is disclosed on every artefact.
- **Audit completeness** — all Factory-generated artefacts, sub-flow
  invocations, and reviewer decisions are captured in the shared audit log
  (target: 100% coverage).

## 7. Key decisions and trade-offs

| Decision | Rationale | Trade-off accepted |
|---|---|---|
| Flows as data (descriptors), not code in the control plane | New flows without re-architecture; control plane stays small and stable | Flow expressiveness is bounded by the descriptor schema; schema evolution must stay backward-compatible |
| Artifacts as the only inter-agent channel | Restartability, manual correction, auditability, reviewer amendment | More I/O and serialisation than in-memory hand-off; artifact schemas become contracts to maintain |
| Stateless single-purpose workers | Reuse across flows; independent testing; bounded context per agent | More orchestration steps per flow than a monolithic agent would need |
| Mandatory HITL gates | Trust, accountability, phased adoption | Throughput is bounded by human review latency; parent flows block on sub-flow gates |
| Bounded retry with human escalation | No infinite self-correction loops; failures surface to people | Some recoverable failures consume reviewer attention |

## 8. Mapping to the implementation

The platform components map onto repository modules as follows — see
[CLAUDE.md](../CLAUDE.md) for the deeper code-level walkthrough:

| Design component | Implementation |
|---|---|
| Flow Registry / plugin descriptors | `factory-core/registry` — scans `classpath*:flows/*.yaml`, validates, mirrors to DB |
| Pipeline Router | `factory-core/trigger` — data-driven trigger contract matching |
| State Manager / execution engine | `factory-core/engine`, `factory-core/service` — step cursor, DB polling with leases, retry budgets |
| HITL Gateway | `factory-core/hitl` + web UI in `factory-app` — approve / amend / reject |
| Artifact Registry | `factory-core/service/ArtifactStore` — insert-only versioning |
| Audit Log | `factory-core/service/AuditLog` — append-only, DB-enforced |
| Integration Connectors | `factory-connectors` — Jira, GitHub, TestRail with graceful fallbacks |
| Agent Worker Library | `factory-core/agent` SPI + `factory-agents` LLM worker base |
| Flow plugins | one module per flow (e.g. `factory-flow-test-factory`) |
