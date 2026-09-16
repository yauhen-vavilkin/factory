# FOLIO AI SDLC Factory

An AI-driven, plugin-based orchestration platform for the FOLIO software
development lifecycle. Flows are **data** (YAML plugin descriptors), agents are
stateless workers that communicate only through **immutable versioned
artifacts**, and every consequential step passes a **human-in-the-loop gate**.

Milestone 1 ships the shared orchestration framework plus **the Test Factory
flow**: Jira story → scope manifest → manual test plan → QA review →
generated Karate scripts → (advisory or real) execution → QA sign-off →
TestRail / GitHub / Jira sync.

## Documentation

| If you want to… | Read |
|---|---|
| Run the platform and the Test Factory flow locally | [Quickstart](#quickstart) below |
| **Build your own flows, agent workers, prompts, or connectors** | [doc/extending-the-factory.md](doc/extending-the-factory.md) — the developer guide, with a full worked example |
| Look up any flow YAML field and its validation rules | [doc/flow-descriptor-reference.md](doc/flow-descriptor-reference.md) |
| Understand the architecture and its principles | [doc/design-overview.md](doc/design-overview.md) |
| Operate it in production (stuck runs, alerts, cost, retention) | [doc/runbook.md](doc/runbook.md) |
| Back up / restore the database | [doc/backup-dr.md](doc/backup-dr.md) |
| Tune pool sizing / plan a load test | [doc/performance.md](doc/performance.md) |

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
factory-flow-test-factory/   Test Factory plugin: flows/test-factory.yaml + 5 workers + prompts
factory-app/                 Spring Boot app: management console (server-rendered UI),
                             REST API, Flyway, config
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
# 1. Database only (the full Compose stack is the Developer Flow demo below)
docker compose up -d postgres

# 2. LLM provider (Spring AI — Anthropic by default, swappable via config)
export ANTHROPIC_API_KEY=sk-ant-...

# 3. Build the modules into ~/.m2 — `-pl factory-app` resolves its siblings
#    from there, so this must run before the first spring-boot:run
mvn install -DskipTests

# 4. Run
mvn spring-boot:run -pl factory-app

# 5. Trigger the Test Factory flow with the bundled sample story (no Jira needed — issue inline)
curl -s -X POST localhost:8080/api/triggers/manual \
  -H 'Content-Type: application/json' \
  -d @factory-app/src/main/resources/samples/sample-story-inline.json
```

Then open the management UI at <http://localhost:8080/> — a dashboard with KPI
cards and charts. The run deliberately **pauses at the first QA gate**
(`AWAITING_HITL`) and does not proceed until you act on it: work the two QA
gates at <http://localhost:8080/reviews>
(approve/amend/reject — edits are saved as new artifact versions) and watch
progress at <http://localhost:8080/executions> (full audit timeline per
execution). The rest of the console: `/flows`
and `/workers` (registered flow plugins and the agent worker library),
`/artifacts` (immutable artifact versions across all executions), `/prompts`
(read-only viewer for each worker's bundled prompt templates), `/audit` (the
append-only event log) and `/status` (engine and connector health).

Manual triggers are never deduplicated by default — re-running the curl starts a
new execution. To opt a manual trigger into dedup, add an idempotency key to the
request body: `{"flowId": "...", "payload": {...}, "dedupKey": "my-key"}`
(trimmed; max 255 characters, else 422; blank treated as absent). Webhook
triggers dedup automatically via `factory.limits.dedup.*`.

Without any connector credentials the flow still completes end-to-end: test
execution runs in **advisory mode** and every external sync is recorded as
`CONNECTOR_SKIPPED` in the audit log and the `sync_report.md` artifact.

## Container image

A multi-stage `Dockerfile` at the repo root builds `factory-app`'s Spring Boot
jar on a Temurin 21 + Maven 3.9 stage and runs the extracted layered jar on a
minimal `eclipse-temurin:21-jre` runtime as a non-root user, with a `HEALTHCHECK`
against `/actuator/health`.

```bash
docker build -t folio-factory-app:local .

# needs a reachable PostgreSQL — start one with `docker compose up -d postgres`
# (on Linux add: --add-host=host.docker.internal:host-gateway — the name is
#  only auto-provided by Docker Desktop)
docker run --rm -p 8080:8080 \
  -e FACTORY_DB_URL=jdbc:postgresql://host.docker.internal:5432/factory \
  -e ANTHROPIC_API_KEY=sk-ant-... \
  folio-factory-app:local
```

CI (`.github/workflows/ci.yml`) builds on JDK 21, runs `mvn verify`
(Testcontainers on GitHub-hosted runners), uploads test reports + the CycloneDX
SBOM, and builds the Docker image. A weekly `dependency-check.yml` runs an
opt-in OWASP scan (`mvn -Powasp verify`); Dependabot keeps Maven and Actions
dependencies current.

### Developer Flow demo

Developer Flow needs the Pi provider values and an explicitly authorized user-owned
fork in `.env`; see the Developer Flow block in `.env.example`. Then build both
runtime images and start the complete local control plane:

```bash
docker compose up -d --build
curl -s -X POST localhost:8080/api/triggers/manual \
  -H 'Content-Type: application/json' \
  -d '{"flowId":"dev-factory","payload":{"issueKey":"MODSIDECAR-196"}}'
```

The Factory container owns the Docker socket only for this trusted single-user MVP.
Pi runs in a separate container without that socket or Jira/GitHub credentials;
verification reconstructs the frozen patch in another fresh container. Delivery is
blocked unless the exact candidate has a matching successful verification receipt.
Compose publishes Factory and PostgreSQL on `127.0.0.1` by default. The MVP still
runs the trusted Factory container as root with the Docker socket, makes its private
workload root writable for the checkout UID/GID, and uses a 7200-second global engine
lease to cover baseline plus coding. Tightening those local-only controls is deferred;
no additional security or lease framework is part of this stabilization.

## Operations

Production procedures live under `doc/`:

- [`doc/runbook.md`](doc/runbook.md) — execution lifecycle, recovering stuck
  runs, draining for deploy, metrics & suggested alerts, cost controls, retention.
- [`doc/backup-dr.md`](doc/backup-dr.md) — PostgreSQL backup/PITR, the
  restore drill, and audit-log durability. Helper: [`scripts/pg-backup.sh`](scripts/pg-backup.sh).
- [`doc/performance.md`](doc/performance.md) — DB pool sizing rationale and a
  load-test plan.

Metrics are exposed at `/actuator/prometheus`; Kubernetes probes at
`/actuator/health/liveness` and `/readiness`. Set `FACTORY_LOG_FORMAT=ecs` (or
`logstash`) for structured JSON logs with `executionId`/`stepId` correlation.

## Configuration

| Environment variable | Purpose |
|---|---|
| `ANTHROPIC_API_KEY` | LLM provider key (Spring AI Anthropic starter) |
| `FACTORY_LLM_MODEL` | Chat model id (default `claude-sonnet-4-5`, or `llama-3.3-70b-versatile` with the `openai` provider) |
| `FACTORY_LLM_PROVIDER` | `anthropic` (default) or `openai` for any OpenAI-compatible endpoint (Groq, Gemini, OpenRouter, local Ollama) |
| `FACTORY_LLM_API_KEY` | API key for the OpenAI-compatible provider |
| `FACTORY_LLM_BASE_URL` / `_COMPLETIONS_PATH` | OpenAI-compatible endpoint (defaults target Groq: `https://api.groq.com/openai/v1` + `/chat/completions`) |
| `FACTORY_DB_URL` / `_USER` / `_PASSWORD` | PostgreSQL (default `jdbc:postgresql://localhost:5432/factory`) |
| `FACTORY_DB_POOL_MAX` / `_MIN_IDLE` | HikariCP pool sizing (defaults `16` / `4`; see `doc/performance.md`) |
| `FACTORY_DB_POOL_CONNECTION_TIMEOUT_MS` / `_MAX_LIFETIME_MS` / `_IDLE_TIMEOUT_MS` / `_LEAK_DETECTION_MS` | HikariCP tuning (defaults `30000` / `1800000` / `600000` / `60000`) |
| `FACTORY_HTTP_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | Connector HTTP timeouts (Duration; defaults `5s` / `30s`) |
| `FACTORY_CONNECTORS_JIRA_BASE_URL` / `_EMAIL` / `_API_TOKEN` | Jira REST v2 |
| `FACTORY_CONNECTORS_GITHUB_TOKEN` (+ `_BASE_URL` for GHE) | GitHub REST |
| `FACTORY_CONNECTORS_TESTRAIL_BASE_URL` / `_USERNAME` / `_API_KEY` / `_PROJECT_ID` | TestRail API v2 |
| `FACTORY_TEST_FACTORY_TARGET_REPO` | `owner/repo` receiving generated test scripts |
| `FACTORY_TEST_FACTORY_TESTRAIL_SECTION_ID` | TestRail section for generated cases |
| `FACTORY_TEST_FACTORY_JIRA_TRANSITION` | Optional Jira transition on completion |
| `FACTORY_TEST_FACTORY_EXECUTION_BASE_URL` + `_KARATE_JAR` | Enable real Karate execution (otherwise advisory mode) |
| `FACTORY_LIMITS_MAX_CONCURRENT_EXECUTIONS` / `_MAX_EXECUTIONS_PER_DAY` | Concurrency cap / daily budget (`0` disables; defaults `8` / `200`, 429 on budget) |
| `FACTORY_LIMITS_DEDUP_ENABLED` / `_DEDUP_WINDOW` / `_DEDUP_ID_POINTERS` | Trigger dedup (default on, `10m`, `/issueKey`) |
| `FACTORY_LIMITS_MAX_ARTIFACT_BYTES` / `_MAX_TRIGGER_PAYLOAD_BYTES` | Write-time size caps (defaults `5000000` / `262144`) |
| `FACTORY_RETENTION_ENABLED` / `_TTL_DAYS` / `_RUN_CRON` | Purge of old terminal runs (**off** by default; audit is never purged) |
| `FACTORY_LOG_FORMAT` | Structured logging: `ecs`/`logstash`/`gelf` (empty = human-readable) |
| `FACTORY_ENGINE_LEASE_TIMEOUT_SECONDS` / `_SHUTDOWN_AWAIT_SECONDS` | Crash-recovery lease / graceful-drain budget (defaults `7200` / `30`); for Developer Flow, keep the lease above twice its stage timeout plus setup/freezing margin |
| `FACTORY_ENGINE_POLL_INTERVAL_MS` / `_BATCH_SIZE` / `_WORKER_THREADS` | Poller cadence / claims per poll / engine pool size (defaults `2000` / `5` / `4`) |
| `FACTORY_ENGINE_REAP_INTERVAL_MS` | Lease-reaper and sub-flow reconciliation cadence (default `30000`) |
| `FACTORY_ENGINE_RECLAIM_RUNNING_ON_STARTUP` | Requeue orphaned RUNNING rows at boot (default `true`; **must be `false` for multi-instance** — see `doc/runbook.md`) |
| `FACTORY_RETENTION_BATCH_SIZE` | Executions purged per retention run (default `100`) |
| `FACTORY_TEST_FACTORY_BASE_BRANCH` | Branch generated test PRs fork from (default `main`) |
| `FACTORY_WEBHOOKS_SHARED_SECRET` | Webhook auth token (see below; unauthenticated while unset) |
| `FACTORY_SHUTDOWN_PHASE_TIMEOUT` | Per-phase graceful-shutdown budget (Duration; default `60s`) |

Webhooks: `POST /api/webhooks/jira` and `/api/webhooks/github`
(shared secret: `FACTORY_WEBHOOKS_SHARED_SECRET` checked against `?token=`).
**Webhooks are unauthenticated while the secret is unset** — the app logs a
warning at startup; always set it in non-local deployments.

## Adding a new flow (the plugin pattern)

1. Create a module (or reuse one) with `src/main/resources/flows/my-flow.yaml`:
   triggers, `agent_chain` of `AGENT` / `HITL_GATE` / `SUB_FLOW` steps with
   declared `inputs`/`outputs`, retry policy.
2. Implement any new `AgentWorker` beans (existing workers are reusable by id).
3. Add the module to `factory-app`'s dependencies.

The registry discovers the descriptor at startup, validates worker ids and
sub-flow references, mirrors the YAML to the database, and the router begins
routing matching triggers. No engine, router or gateway changes.

**[doc/extending-the-factory.md](doc/extending-the-factory.md) is the full
guide**: a step-by-step tutorial building a complete flow (LLM worker,
prompts, HITL gate, connector-backed worker), plus in-depth chapters on
triggers and webhooks, worker failure/retry semantics, prompt and artifact
conventions, sub-flow composition, quality post-processors, adding
connectors, per-flow configuration, testing patterns, and adopting the
platform on a non-FOLIO project. The YAML schema itself is specified in
[doc/flow-descriptor-reference.md](doc/flow-descriptor-reference.md).

## Verification

```bash
mvn verify        # unit + integration tests (Testcontainers PostgreSQL; Docker required)
```

The suite includes two full Test Factory end-to-end tests (scripted LLM, WireMock'd
Jira/GitHub/TestRail): the happy path with a QA amendment at gate 1, and the
zero-credentials path. Engine semantics (retry → escalation, poller crash
recovery, sub-flow parent/child, HITL decisions) are covered in
`factory-core`'s integration tests.
