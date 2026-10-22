# FOLIO AI SDLC Factory

An AI-driven, plugin-based orchestration platform for the FOLIO software
development lifecycle. Flows are **data** (YAML plugin descriptors), agents are
stateless workers that communicate only through **immutable versioned
artifacts**. Each flow declares its review and verification gates.

The shared orchestration framework runs two flow plugins:

- **Test Factory**: Jira story → scope manifest → manual test plan → QA review →
  generated Karate scripts → (advisory or real) execution → QA sign-off →
  TestRail / GitHub / Jira sync.
- **Developer Flow**: task intake and clarification → isolated coding → independent
  candidate verification → pull request delivery → resumable PR feedback.

## Documentation

| If you want to… | Read |
|---|---|
| Run the platform and the Test Factory flow locally | [Quickstart](#quickstart) below |
| **Build your own flows, agent workers, prompts, or connectors** | [doc/extending-the-factory.md](doc/extending-the-factory.md) — the developer guide, with a full worked example |
| Look up any flow YAML field and its validation rules | [doc/flow-descriptor-reference.md](doc/flow-descriptor-reference.md) |
| Understand the architecture and its principles | [doc/design-overview.md](doc/design-overview.md) |
| Operate it in production (stuck runs, alerts, cost, retention) | [doc/runbook.md](doc/runbook.md) |
| Work on PR comments inside Developer Flow | [doc/pr-feedback.md](doc/pr-feedback.md) |
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
factory-flow-dev-factory/    Developer Flow: intake, isolated coding, independent verification,
                             exact candidate delivery and resumable PR feedback
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
# 1. Database only (the full Compose stack is the Developer Flow section below)
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

### Developer Flow with Docker Compose

For this path, install Docker Desktop with Compose 2.20+ and start its engine.
Java, Maven and Claude Code are supplied by the images; no host installation or
Claude login is needed. The first build downloads public base images, Maven
dependencies and the pinned coding CLI, so it needs internet access and can take
several minutes with an empty cache. Docker Desktop on macOS is the validated
local environment. A host proxy must be reachable from sibling coding containers;
plain Linux Docker needs its own host routing configuration.

From a fresh clone:

```bash
cp .env.example .env
```

The short `.env.example` configures **Claude Code with Sonnet 5.5** through a
local CodeMie proxy at `http://host.docker.internal:4001`, using Bearer
authentication and the standard local gateway token `codemie-proxy`. These are
also the Spring, Java and Compose defaults. Start the proxy yourself; Compose
does not manage it. The URL is resolved inside the coding
container and has no `/v1` suffix: the CLI appends `/v1/messages`.

Fill in the empty access values before triggering a task:

- Jira base URL, email and API token for reading the requested issue.
- A GitHub token with write access to the personal delivery repository. Source
  repositories must be public; source checkout does not use this token.
- Source/delivery repositories and an existing shared base branch. The bundled
  targets have delivery disabled; choose your own fork and authorize it explicitly.
  Verification is followed by automatic push/PR publication to the authorized
  target. `create-pull-request=false` does not disable git push.

For example, enable only your sidecar target (replace `your-github-user`):

```dotenv
FACTORY_SIDECAR_SOURCE_REPO=your-github-user/folio-module-sidecar
FACTORY_SIDECAR_DELIVERY_REPO=your-github-user/folio-module-sidecar
FACTORY_SIDECAR_BASE_BRANCH=master
FACTORY_SIDECAR_DELIVERY_AUTHORIZED=true
FACTORY_MOD_ROLES_KEYCLOAK_DELIVERY_AUTHORIZED=false
FACTORY_MGR_TENANT_ENTITLEMENTS_DELIVERY_AUTHORIZED=false
FACTORY_MGR_APPLICATIONS_DELIVERY_AUTHORIZED=false
```

Repository policy lives in [`config/developer-repositories.yaml`](config/developer-repositories.yaml):
one entry contains Jira mapping, source, shared base branch, build/verification
policy and delivery target. The four bundled entries keep the `.env` overrides
shown above. To add another repository, edit the catalog as described below;
inventing another `FACTORY_<REPO>_SOURCE_REPO` variable does not add an entry.

Compose forwards the documented application settings for both flows, including
Spring AI, connectors, limits, engine tuning and webhook authentication. Database
connection settings and the server's container listen address are supplied by
Compose for its own services; host-run Spring uses exported `.env` values instead.

Build and start, including the selected CLI version check:

```bash
docker compose up -d --build
docker compose ps -a
curl --fail http://localhost:8080/actuator/health
```

Wait for Factory to become healthy (`status: UP`); `coding-runtime` should have
exited with code 0. Health confirms application/database startup, not that a task
can run. Open <http://localhost:8080/developer/configuration> to check the effective
configuration and an issue key before triggering. Then open
<http://localhost:8080/flows/dev-factory>, choose **Trigger flow**, and submit an
issue such as `{"issueKey":"MODSIDECAR-196"}`.
Use an issue that still requires a change on your chosen base. Set a unique dedup
key for each intentional run. With a new empty database, starting the stack or
loading the UI makes no coding-model call. Existing queued executions can resume
on startup; triggering coding can incur API charges and publish a PR.

To diagnose a failed start, use `docker compose logs --tail=100 factory
coding-runtime postgres`. Avoid sharing `.env` or unfiltered `docker compose
config`/container environment output: they include credentials.

`docker compose down` stops this stack while retaining its database volume and
execution history. Do not use `down -v` or remove existing volumes to upgrade or
retry a build. To run a separate evaluation alongside an existing stack, choose
a new Compose project (`docker compose -p factory-evaluation ...`) and set these
values in its own `.env` before the same build/start command:

```dotenv
FACTORY_APP_PORT=18080
FACTORY_DB_PORT=15432
FACTORY_APP_IMAGE=folio-factory-app:evaluation
FACTORY_CLAUDE_CODE_IMAGE=factory-dev-claude-code:evaluation
FACTORY_PI_IMAGE=factory-dev-pi:evaluation
FACTORY_DEV_FACTORY_MAVEN_CACHE_VOLUME=factory-evaluation-m2-cache
```

The project scopes its PostgreSQL volume; the Maven cache has an explicit name,
so changing the project alone does not isolate that cache. These names and ports
must be unused. Use the same project name for later `ps`, `logs` and `down`.

The Factory container owns the Docker socket only for this trusted single-user MVP.
The coding runtime runs in a separate container without that socket or Jira/GitHub
credentials; verification rebuilds the frozen patch in another fresh container.
Delivery is blocked unless the exact candidate has a matching successful
verification receipt: required Maven suites must produce fresh, executed reports
(see `required-reports` in `config/developer-repositories.yaml`). `surefire` requires
unit-test reports; `failsafe` requires integration-test reports. Each required
family must be present, and every report in that family must contain an executed
test. A successful unit suite alone cannot approve an integration plan. Test class
names belong to the target project's Maven build and are not listed in Factory.
Integration remains blocked when its Docker dependencies are unavailable. The
trusted build reuses the configured Maven cache Docker volume, repairing cache
entry ownership before each build. Keep this volume when stopping or rebuilding
the stack.

Execution pages estimate API cost from the operator-owned
`config/developer-pricing.yaml` rate card. The YAML records public list prices
linked to their official sources — update it when pricing changes; Factory does
not fetch prices from the model API. An estimate is shown only when every positive
token category has a configured rate.

Compose publishes Factory and PostgreSQL on `127.0.0.1` by default. The MVP still
runs the trusted Factory container as root with the Docker socket and a 7200-second
engine lease; tightening these local-only controls is deferred.

### Adding or changing a repository

Edit `config/developer-repositories.yaml` to add a public Java/Maven repository.
For example, add this entry under `factory.dev-factory.repositories`:

```yaml
my-module:
  source-repo: your-github-user/my-module
  base-branch: main
  build-image: maven:3.9-eclipse-temurin-21
  verification-plan: java21-unit
  jira-projects: [MYMOD]
  # jira-components: [Backend]  # optional: restrict the Jira component mapping
  delivery:
    repository: your-github-user/my-module
    authorized: false
```

Use the actual Jira project key and a branch that exists in the source and
delivery repositories. The delivery base comes from this entry's `base-branch`;
there is no second branch to keep in sync. Set `authorized: true` only when the
destination is ready for automatic push and PR creation. Source and delivery may
be different repositories, but the delivery branch must contain the source base.

`verification-plan` names a trusted command in `factory.dev-factory.runtime.plans`
in the same file. The bundled `java21-unit` runs `mvn -B -ntp test`; add a separate
plan with `runtime.required-reports.<plan>: [surefire, failsafe]` when acceptance
requires both unit and integration tests. Use `[surefire]` for unit tests only.
Verification reads fresh Surefire/Failsafe XML reports across Maven modules,
checks executed-test counts and rejects failures, errors and entirely skipped
reports in required families. New and renamed test classes need no Factory
configuration changes. This policy checks executed report families; it does not
prove that every test in the source was discovered or detect deleted tests.
Existing operator catalogs with exact report paths remain supported, but the
bundled catalog does not depend on test class names. An exit code of zero alone
is insufficient. Commands and required reports are operator policy, never
supplied by Jira or the coding model.

Compose mounts this catalog read-only. To keep a private operator copy, set
`FACTORY_DEV_FACTORY_REPOSITORIES_FILE=./config/my-repositories.yaml` in `.env`.
The application also packages the checked-in catalog as its fallback; external
configuration overrides matching entries and adds new ones. A partial override
does not remove other catalog entries. The same checked-in external file is
loaded when Spring runs from the repository root.

After editing a catalog file, recreate Factory to reload the mounted file:

```bash
docker compose up -d --force-recreate factory
```

After changing `.env`, run `docker compose up -d` to recreate affected containers;
`restart` does not load new environment values. Neither operation requires an app
image rebuild for repository policy changes, and neither removes the database.
Keep source/delivery/verification settings stable while a task is running.

Current Developer Flow support is public source repositories, GitHub.com
delivery, and Maven verification. A custom GitHub Enterprise API is supported by
Test Factory, but Developer Flow preflight rejects it because Developer Flow's
Git publication targets GitHub.com. Private source authentication, GitLab
delivery, submodules and other build/report formats require implementation changes.

Older deployments may still supply `factory.dev-factory.delivery.targets.<key>`.
An explicit legacy target takes precedence over that entry's nested `delivery`
settings; missing repository/base fields inherit the catalog values. Legacy
authorization remains authoritative, including `false`; set it explicitly to
`true` to enable delivery. Remove legacy settings when migrating to the unified catalog. A different legacy
delivery branch name is rejected by preflight; a conflicting nested delivery base
is rejected while loading the catalog.

### Checking configuration before a task

The Developer Flow page links to **Check configuration** at
<http://localhost:8080/developer/configuration>. It displays effective runtime,
model and repository policy without credentials. Supply the intended issue key
for task-specific checks. The same read-only report is available as JSON:

```bash
curl --fail 'http://localhost:8080/api/developer/preflight?issueKey=MODSIDECAR-196'
```

Without an issue key, the report is a configuration overview and always has
`ready: false`; task-specific checks are `NOT_RUN`. With an issue key it also
reads Jira, resolves the repository mapping and source base, and checks whether
GitHub reports push permission and a destination base containing the source base.
This is an access check, not a guarantee that a later push will succeed. Docker,
the local coding image and gateway TCP connectivity from Factory
are checked before a new execution is created. A failed TCP probe is a warning:
host-run Spring and sibling coding containers can use different DNS and networks,
including Docker-only `host.docker.internal`. A missing build image is a warning
because Docker can pull it when needed. Blocking failures reject a new Developer
Flow trigger from the UI, manual API or webhooks before baseline build or coding.
An existing execution found by dedup is returned without starting another attempt.
The report endpoint returns HTTP 200 for both ready and blocked reports; inspect
`ready` and the individual checks.

When multiple repositories match, at least one must be ready to open the existing
repository-selection review. Failures for other candidates are warnings until a
repository is confirmed. The selected repository is checked again before checkout
and baseline, using the frozen source SHA from the task; choosing a disabled or
inaccessible destination cannot start coding. The same guard applies to an
implementation continuation.

Preflight never runs a build or tests, sends a coding inference request, pushes
Git changes or writes to Jira/GitHub. TCP reachability does not prove connectivity
from the coding container, TLS, model authorization, streaming or tool support;
these remain `NOT_RUN` until a separately authorized live smoke. A denied model
catalog request does not establish that inference is unavailable. Missing pricing
is a warning, so it does not prevent coding; cost remains unavailable or incomplete where a
matching rate or usage category is missing. Recheck after changing configuration;
preflight does not replace independent candidate verification or delivery checks.

### Coding runtime profiles

`factory.dev-factory.runtime.coding.pi` and `.claude-code` are independent
operator-owned profiles. Defaults are Claude Code, model `claude-sonnet-5-5`,
local CodeMie endpoint `http://host.docker.internal:4001` and `bearer-token`
authentication with the standard gateway token `codemie-proxy`. All profile
values can be overridden.
Only the selected runtime is validated before coding; empty inactive profiles do
not prevent application startup. Both adapters serve
initial coding, a fresh attempt after a confirmed clarification, and PR feedback.
Feedback retains partial edits on questions and failures; initial coding keeps
its existing completed-candidate policy. Factory still owns repository checks,
candidate export, independent verification, workload ownership and cleanup.

Default Claude Code configuration for the local CodeMie proxy:

```dotenv
FACTORY_DEV_FACTORY_CODING_RUNTIME=claude-code
FACTORY_CLAUDE_CODE_API=anthropic-messages
FACTORY_CLAUDE_CODE_BASE_URL=http://host.docker.internal:4001
FACTORY_CLAUDE_CODE_MODEL=claude-sonnet-5-5
FACTORY_CLAUDE_CODE_AUTHENTICATION=bearer-token
FACTORY_CLAUDE_CODE_AUTH_TOKEN=codemie-proxy
```

The example assumes an already running local CodeMie proxy on port 4001; Compose
does not start it. The standard token authenticates Factory to the local proxy;
CodeMie manages upstream SSO/provider authentication. If your proxy uses a custom
gateway key, set `FACTORY_CLAUDE_CODE_AUTH_TOKEN` to that value. For another
gateway, supply the prefix before `/v1/messages` reachable from the coding
container. Do not append `/v1`: the CLI adds
`/v1/messages`. Confirm that your gateway supports the default literal model ID
`claude-sonnet-5-5`; override `FACTORY_CLAUDE_CODE_MODEL` if it uses another
deployment ID. Leave optional effort unset for the first connection check.
A Chat Completions or Responses endpoint
cannot be used with Claude Code: an incompatible API type fails before the
coding container starts. Factory does not translate API protocols, rename model
IDs, or restrict models to an allowlist. Endpoint/model capabilities, streaming
and tool support remain the operator's responsibility. Live CodeMie integration
requires a separately authorized test; the local contract tests make no paid calls.
Model IDs are literal, including names such as `sonnet` and `default`: the adapter
pins CLI alias defaults, disables legacy remapping and uses the documented
[`CLAUDE_CODE_EXTRA_BODY`](https://code.claude.com/docs/en/env-vars) setting to keep
the request's `model` unchanged. A suffix such as `[1m]` is part of the endpoint's
model ID, not a Factory context-window option.

Claude Code defaults to `bearer-token` (`FACTORY_CLAUDE_CODE_AUTH_TOKEN` →
`Authorization: Bearer ...`). For another endpoint, explicitly select `api-key`
(`FACTORY_CLAUDE_CODE_API_KEY` → `x-api-key`) if required. Only the selected credential is sent. No host Claude credentials or
configuration are mounted. Runtime secrets are copied into a private temporary
file only during coding, outside `/workspace`, and removed afterwards. They are
not build arguments or Docker image environment values. Raw CLI/provider output
is not logged or saved by Factory; progress contains bounded tool names and token
counts. Claude's cost estimate is separate from Factory's operator rate card.
Missing or zero-filled provider usage stays marked incomplete, including when
other turns in the same attempt reported valid counts.

To use Pi instead, explicitly select it and configure its independent profile
(OpenAI Chat Completions example):

```dotenv
FACTORY_DEV_FACTORY_CODING_RUNTIME=pi
FACTORY_PI_PROVIDER=openai-compatible
FACTORY_PI_API=openai-completions
FACTORY_PI_BASE_URL=https://gateway.example/v1
FACTORY_PI_MODEL=operator-model-id
FACTORY_PI_AUTHENTICATION=api-key
FACTORY_PI_API_KEY=replace-with-your-key
```

Pi precedence is field-by-field: nonblank `FACTORY_PI_*` > legacy
`FACTORY_MODEL_*` > defaults. `FACTORY_PI_MODEL` falls back to `FACTORY_MODEL_ID`;
`FACTORY_PI_IMAGE` falls back to `FACTORY_DEV_FACTORY_CODING_IMAGE`. New empty values
keep the legacy behavior; remove an old value to clear it. Legacy variables never
configure Claude Code. Pi authentication is `api-key`; the Pi provider controls
its wire format. Optional harness settings are Pi `REASONING_EFFORT` and
`MAX_OUTPUT_TOKENS`, and Claude Code `EFFORT`, `MAX_TURNS` (40), and
`MAX_OUTPUT_TOKENS` (16384). Use the `FACTORY_PI_` or `FACTORY_CLAUDE_CODE_`
prefix respectively. Leave `FACTORY_CLAUDE_CODE_EFFORT` unset initially.
The common attempt timeout remains `FACTORY_DEV_FACTORY_TIMEOUT_SECONDS` (1800).

Claude Code is pinned to **2.1.287**, resolved from the official stable channel on
2026-10-14; both supported Linux architectures have pinned manifest checksums.
The [official setup guide](https://code.claude.com/docs/en/setup) documents the
stable channel and version management. Auto-updates are disabled. The adapter
uses [programmatic print mode](https://code.claude.com/docs/en/headless), NDJSON
output, bare mode, no session persistence and explicitly allowed local coding
tools. Permission mode is explicitly `dontAsk` with `--permission-prompts none`;
allowed tools run without a separate model-based permission classifier, and any
remaining permission request is denied without waiting. It works under the
workload's UID (including the Compose root UID), private `HOME=/tmp/factory-home`,
`/workspace`, and Java/Maven 21 without interactive login or permission approval.

Compose 2.20+ includes only the selected runtime service and checks its exact
version before starting Factory. Build/check each profile without credentials:

```bash
FACTORY_DEV_FACTORY_CODING_RUNTIME=pi docker compose build coding-runtime
FACTORY_DEV_FACTORY_CODING_RUNTIME=pi docker compose run --rm --no-deps coding-runtime
FACTORY_DEV_FACTORY_CODING_RUNTIME=claude-code docker compose build coding-runtime
FACTORY_DEV_FACTORY_CODING_RUNTIME=claude-code docker compose run --rm --no-deps coding-runtime

# Real CLI against local mock servers, plus existing workload/feedback tests.
FACTORY_DOCKER_TEST=true mvn -B -ntp -pl factory-flow-dev-factory -am \
  -Dtest=ClaudeCodeDockerTest,PiCodingHttpContractTest,FeedbackDockerTest,DockerWorkloadsTest \
  -Dfactory.pi.contract.image=factory-dev-pi:0.85.1-java21 \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -ntp verify
```

The contract tests use Docker Desktop's `host.docker.internal` and isolated test
credentials. Claude tests use the default pinned image tag. When starting Factory
directly, export `.env` before `mvn spring-boot:run -pl factory-app`:

```bash
set -a
source .env
set +a
```

Spring Boot does not load `.env` automatically. The coding container still uses
`host.docker.internal` to reach the host proxy. No Java edits are needed to switch
runtimes or endpoints.

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
| `FACTORY_DEV_FACTORY_MAVEN_CACHE_VOLUME` | Persistent trusted Developer Flow Maven cache volume (default `factory-dev-m2-cache`) |
| `FACTORY_DEV_FACTORY_REPOSITORIES_FILE` | Compose host path of the repository catalog (default `./config/developer-repositories.yaml`); mounted read-only |
| `FACTORY_DEV_FACTORY_CODING_RUNTIME` | Coding-runtime adapter selected by Developer Flow (`pi` or `claude-code`; default `claude-code`) |
| `FACTORY_MODEL_PROVIDER` / `_ID` / `_BASE_URL` / `_API` / `_API_KEY` | Legacy Pi profile settings; nonblank `FACTORY_PI_*` values override them |
| `FACTORY_PI_*` / `FACTORY_CLAUDE_CODE_*` | Independent image, API, model, endpoint, authentication and harness profiles; see [Coding runtime profiles](#coding-runtime-profiles) |
| `FACTORY_MODEL_REASONING_EFFORT` | Optional Pi thinking level; `max` for the CodeMie GPT-5.6 Luna profile, empty for the current GLM behavior |
| `FACTORY_MODEL_MAX_OUTPUT_TOKENS` | Pi model output limit (default `16384`; CodeMie profile `65536`) |
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

To check Pi's actual Chat Completions HTTP request against a local test endpoint,
build the coding image and run the opt-in contract test:

```bash
FACTORY_DEV_FACTORY_CODING_RUNTIME=pi docker compose build coding-runtime
mvn -pl factory-flow-dev-factory -am test -Dtest=PiCodingHttpContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfactory.pi.contract.image=factory-dev-pi:0.85.1-java21
```

The suite includes two full Test Factory end-to-end tests (scripted LLM, WireMock'd
Jira/GitHub/TestRail): the happy path with a QA amendment at gate 1, and the
zero-credentials path. Engine semantics (retry → escalation, poller crash
recovery, sub-flow parent/child, HITL decisions) are covered in
`factory-core`'s integration tests.
