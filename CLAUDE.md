# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

FOLIO AI SDLC Factory — a plugin-based AI orchestration platform for the FOLIO
software development lifecycle. Flows are **data** (YAML plugin descriptors),
agents are **stateless workers** that communicate only through **immutable
versioned artifacts**, and every consequential step passes a
**human-in-the-loop (HITL) gate**.

The full product spec ("FOLIO AI SDLC Factory") defines five flows: A — Test
Factory, B — Kong & Keycloak Release Automation, C — Release Pipeline
Orchestrator, D — AI Junior Developer, E — Grails-to-Java Rewrite Orchestrator
(which composes A and D as sub-flows). **Milestone 1 (this repo today) ships
the shared framework plus Flow A only.** New flows must be added as plugins —
never by modifying the control plane.

## Commands

```bash
docker compose up -d                     # PostgreSQL (required for run and tests)
mvn spring-boot:run -pl factory-app      # run the app (needs ANTHROPIC_API_KEY)
mvn verify                               # full build + unit/integration tests (Docker required — Testcontainers)
mvn -pl factory-core test -Dtest=SubFlowIntegrationTest          # single test class
mvn -pl factory-core test -Dtest=SubFlowIntegrationTest#method   # single test method
```

Trigger Flow A locally without Jira credentials:

```bash
curl -s -X POST localhost:8080/api/triggers/manual \
  -H 'Content-Type: application/json' \
  -d @factory-app/src/main/resources/samples/sample-story-inline.json
```

Then work the QA gates at `http://localhost:8080/reviews`, watch progress at
`/executions`, check connectors at `/api/status`. With zero connector
credentials the flow still completes end-to-end: execution runs in **advisory
mode** and every external sync is audited as `CONNECTOR_SKIPPED`.

## Stack

Java 21, Spring Boot 4.x, Spring AI 2.x (Anthropic starter by default),
PostgreSQL + Flyway, Maven multi-module. **Jackson 3** (`tools.jackson.*`
packages, not `com.fasterxml.jackson`); JPA JSON columns go through
`Jackson3JsonFormatMapper`. Tests use Testcontainers PostgreSQL and WireMock
(connectors), plus a scripted `StubChatModel` for LLM calls — no real API keys
needed in tests.

## Module architecture

Dependency direction (each depends only on those above it):

```
factory-core                control plane — knows NOTHING about any flow
factory-connectors          Jira / GitHub / TestRail REST clients + graceful fallbacks
factory-agents              LLM worker base (Spring AI ChatClient), prompt loading,
                            frontmatter codec, secret-scan post-processor
factory-flow-test-factory   Flow A plugin: flows/test-factory.yaml + 5 workers + prompts
factory-app                 Spring Boot composition root: REST API, HITL web UI, Flyway
```

The hard rule: `factory-core` cannot even compile against a flow module.
Flow modules plug in via the `AgentWorker` SPI and a YAML descriptor; only
`factory-app` aggregates everything.

### Execution model (the big picture)

1. **FlowRegistry** (`core/registry/`) scans `classpath*:flows/*.yaml` at
   startup, parses descriptors (`FlowDescriptorParser`), validates worker ids
   and sub-flow references against the `AgentWorkerRegistry`, and mirrors each
   descriptor to the DB.
2. **PipelineRouter** (`core/trigger/`) matches incoming `TriggerEvent`s
   (manual API, Jira/GitHub webhooks, sub-flow invocations) against the
   descriptors' data-driven trigger contracts (event type + JSON-pointer
   filters) and creates a `PipelineExecution`.
3. **ExecutionPoller** (`core/engine/`) claims runnable executions from the DB
   with `FOR UPDATE SKIP LOCKED` plus a lease reaper (crash recovery), then
   hands them to the **ExecutionEngine**, which advances a step cursor through
   the descriptor's `agent_chain`. Step types: `AGENT`, `HITL_GATE`,
   `SUB_FLOW`.
4. **AGENT** steps resolve the worker bean by id and call it with a
   scope-bounded `AgentContext` — the worker can read only its declared
   `inputs` and write only its declared `outputs` (data isolation is enforced
   by the framework, not by convention).
5. **HITL_GATE** steps open a `HitlReview` and park the execution.
   `HitlDecisionService` implements approve / amend / reject; an amendment is
   saved as a **new attributed artifact version**, never an overwrite
   (amendments are validated per-flow via `ArtifactAmendmentValidator`).
6. **SUB_FLOW** steps (`SubFlowInvoker`) start a child execution of any
   registered flow; the parent waits — including through the child's HITL
   gates — then collects mapped output artifacts. Built now; Flow E will use
   it to compose Flows A and D.

### Framework invariants (enforced, don't work around them)

- **Immutable artifacts** — `ArtifactStore` is insert-only versioning; nothing
  updates an artifact row in place.
- **Append-only audit** — `AuditLog` records every step, artifact write, HITL
  decision, and sub-flow invocation; the DB schema blocks UPDATE/DELETE on the
  audit table.
- **Bounded retry** — default 3 attempts per step with per-flow
  backoff (`retry_policy` in the descriptor); exhaustion escalates the
  execution into the same human review inbox rather than failing silently.
- **No autonomous merge/deploy authority** — external side effects sit behind
  HITL gates; unconfigured connectors degrade to `CONNECTOR_SKIPPED`, never
  hard-fail the flow.

### LLM agent workers (factory-agents + flow modules)

`AbstractLlmAgentWorker` is the base: it loads
`prompts/<worker-id>/system.md` + `user.md` (single-pass `{placeholder}`
rendering via `PromptLoader`), calls the Spring AI `ChatClient`, parses the
response with `FrontmatterCodec` (YAML frontmatter + markdown body), and runs
post-processors (e.g. `SecretScanPostProcessor`; Flow A adds
`KarateSanityPostProcessor`). Flow A's five workers live in
`factory-flow-test-factory/.../worker/`; its chain is
`flows/test-factory.yaml`: triage → test-spec → HITL gate 1 → test-automation
(Karate) → test-execution → HITL gate 2 → finalizer (TestRail/GitHub/Jira
sync).

## Adding a new flow (the plugin pattern)

1. New module (or reuse one) with `src/main/resources/flows/<flow>.yaml`:
   triggers, `agent_chain` of `AGENT`/`HITL_GATE`/`SUB_FLOW` steps with
   declared `inputs`/`outputs`, retry policy.
2. Implement any new `AgentWorker` beans (existing worker ids are reusable
   across flows).
3. Add the module to `factory-app`'s dependencies.

No engine, router, or gateway changes — if a change seems to require touching
`factory-core` for a specific flow, the design is wrong.

## Configuration notes

- All config flows through env vars (`FACTORY_*`, `ANTHROPIC_API_KEY`) mapped
  in `factory-app/src/main/resources/application.yaml` — see the README table.
- Kebab-case properties there use **explicit `${ENV_VAR:}` placeholders**
  because relaxed binding alone would not map the underscored env vars; follow
  that pattern when adding properties.
- Webhooks (`/api/webhooks/jira`, `/api/webhooks/github`) are unauthenticated
  until `FACTORY_WEBHOOKS_SHARED_SECRET` is set (checked against `?token=`).

## Testing conventions

- Engine semantics (retry → escalation, poller crash recovery, sub-flow
  parent/child, HITL decisions) are covered by `factory-core` integration
  tests using fake workers and fake flow descriptors under
  `factory-core/src/test/resources/flows/`.
- Full Flow A end-to-end lives in `factory-app` (`FlowAEndToEndTest`,
  `FlowAZeroCredentialsTest`): scripted LLM, WireMock'd Jira/GitHub/TestRail,
  including a QA amendment at gate 1 and the zero-credentials path. When
  changing flow behavior, extend these rather than mocking the engine.
