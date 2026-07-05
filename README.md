# FOLIO AI SDLC Factory

An AI-driven, plugin-based orchestration platform for the FOLIO software
development lifecycle. Flows are **data** (YAML plugin descriptors), agents are
stateless workers that communicate only through **immutable versioned
artifacts**, and every consequential step passes a **human-in-the-loop gate**.

Milestone 1 ships the shared orchestration framework plus **Flow A — the Test
Factory**: Jira story → scope manifest → manual test plan → QA review →
generated Karate scripts → (advisory or real) execution → QA sign-off →
TestRail / GitHub / Jira sync.

## Architecture

```
factory-core/                the control plane (no knowledge of any flow)
  registry/    FlowRegistry — scans classpath*:flows/*.yaml, validates, mirrors to DB
  engine/      ExecutionEngine (step cursor), ExecutionPoller (DB polling,
               FOR UPDATE SKIP LOCKED, lease reaper), retry budgets, escalation
  agent/       AgentWorker SPI + registry (scope-bounded AgentContext)
  hitl/        HitlDecisionService — approve / amend / reject semantics
  service/     StateManager, ArtifactStore (insert-only versioning), AuditLog (append-only)
  trigger/     PipelineRouter — data-driven trigger contract matching
factory-connectors/          Jira, GitHub, TestRail REST clients + graceful fallbacks
factory-agents/              LLM base worker (Spring AI ChatClient), prompt loading,
                             frontmatter codec, secret-scan post-processor
factory-flow-test-factory/   Flow A plugin: flows/test-factory.yaml + 5 workers + prompts
factory-app/                 Spring Boot app: REST API, HITL web UI, Flyway, config
```

Key invariants, enforced by the framework:

- **Plugin flows** — adding a flow = new module with a `flows/*.yaml` descriptor
  and worker beans. Zero control-plane changes (`factory-core` cannot even
  compile against a flow module).
- **Immutable artifacts** — agents only read declared inputs and write new
  artifact versions; reviewer amendments become attributed versions.
- **Bounded retry** — 3 attempts per step (configurable per flow), then the
  execution escalates into the same human review inbox.
- **Append-only audit** — every step, artifact write, decision and sub-flow
  invocation; the DB blocks UPDATE/DELETE on the audit table.
- **Sub-flow composition** — any flow can invoke any registered flow; the
  parent waits (including through the child's HITL gates) and collects mapped
  output artifacts. Built now; Flow E will compose Flows A and D with it.

## Quickstart

Prerequisites: Java 21, Maven 3.9+, Docker.

```bash
# 1. Database
docker compose up -d

# 2. LLM provider (Spring AI — Anthropic by default, swappable via config)
export ANTHROPIC_API_KEY=sk-ant-...

# 3. Run
mvn spring-boot:run -pl factory-app

# 4. Trigger Flow A with the bundled sample story (no Jira needed — issue inline)
curl -s -X POST localhost:8080/api/triggers/manual \
  -H 'Content-Type: application/json' \
  -d @factory-app/src/main/resources/samples/sample-story-inline.json
```

Then open <http://localhost:8080/reviews> and work the two QA gates
(approve/amend/reject — edits are saved as new artifact versions). Watch
progress on <http://localhost:8080/executions> (full audit timeline per
execution) and check connector status on <http://localhost:8080/api/status>.

Without any connector credentials the flow still completes end-to-end: test
execution runs in **advisory mode** and every external sync is recorded as
`CONNECTOR_SKIPPED` in the audit log and the `sync_report.md` artifact.

## Configuration

| Environment variable | Purpose |
|---|---|
| `ANTHROPIC_API_KEY` | LLM provider key (Spring AI Anthropic starter) |
| `FACTORY_LLM_MODEL` | Chat model id (default `claude-sonnet-4-5`) |
| `FACTORY_DB_URL` / `_USER` / `_PASSWORD` | PostgreSQL (default `jdbc:postgresql://localhost:5432/factory`) |
| `FACTORY_CONNECTORS_JIRA_BASE_URL` / `_EMAIL` / `_API_TOKEN` | Jira REST v2 |
| `FACTORY_CONNECTORS_GITHUB_TOKEN` (+ `_BASE_URL` for GHE) | GitHub REST |
| `FACTORY_CONNECTORS_TESTRAIL_BASE_URL` / `_USERNAME` / `_API_KEY` / `_PROJECT_ID` | TestRail API v2 |
| `FACTORY_FLOWA_TARGET_REPO` | `owner/repo` receiving generated test scripts |
| `FACTORY_FLOWA_TESTRAIL_SECTION_ID` | TestRail section for generated cases |
| `FACTORY_FLOWA_JIRA_TRANSITION` | Optional Jira transition on completion |
| `FACTORY_FLOWA_EXECUTION_BASE_URL` + `_KARATE_JAR` | Enable real Karate execution (otherwise advisory mode) |

Webhooks: `POST /api/webhooks/jira` and `/api/webhooks/github`
(optional shared secret: `factory.webhooks.shared-secret` + `?token=`).

## Adding a new flow (the plugin pattern)

1. Create a module (or reuse one) with `src/main/resources/flows/my-flow.yaml`:
   triggers, `agent_chain` of `AGENT` / `HITL_GATE` / `SUB_FLOW` steps with
   declared `inputs`/`outputs`, retry policy.
2. Implement any new `AgentWorker` beans (existing workers are reusable by id).
3. Add the module to `factory-app`'s dependencies.

The registry discovers the descriptor at startup, validates worker ids and
sub-flow references, mirrors the YAML to the database, and the router begins
routing matching triggers. No engine, router or gateway changes.

## Verification

```bash
mvn verify        # unit + integration tests (Testcontainers PostgreSQL; Docker required)
```

The suite includes two full Flow A end-to-end tests (scripted LLM, WireMock'd
Jira/GitHub/TestRail): the happy path with a QA amendment at gate 1, and the
zero-credentials path. Engine semantics (retry → escalation, poller crash
recovery, sub-flow parent/child, HITL decisions) are covered in
`factory-core`'s integration tests.
