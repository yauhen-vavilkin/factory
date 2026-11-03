# AGENTS.md

`CLAUDE.md` is a symlink to this file, so Claude Code and AGENTS.md-aware agents read the same text.

## What this is

FOLIO AI SDLC Factory — a plugin-based AI orchestration platform for the FOLIO
software development lifecycle. Flows are **data** (YAML plugin descriptors),
agents are **stateless workers** that communicate only through **immutable
versioned artifacts**, and every consequential step passes a
**human-in-the-loop (HITL) gate**.

The full product spec ("FOLIO AI SDLC Factory") defines five flows: A — Test
Factory, B — Kong & Keycloak Release Automation, C — Release Pipeline
Orchestrator, D — AI Junior Developer, E — Grails-to-Java Rewrite Orchestrator
(which composes A and D as sub-flows). **This repo ships the shared framework,
the Test Factory flow, and the Developer Flow (`factory-flow-dev-factory`,
including My tasks: the Jira task queue in `factory-app/tasks`).** New flows must be added as plugins —
never by modifying the control plane.

## Product postulates

Simple. Transparent. Easy to configure. These outrank feature ambitions: a design
that adds layers, packages or configuration surface carries that cost explicitly
in the plan — it is never assumed away by "the feature needs it".

In practice: extend existing classes, packages and pages first. A new package or
layer needs a stated reason in the plan or the commit message. "Minimal" never
excuses dropping input validation at trust boundaries, error handling, security
or accessibility.

Factory competes with plain Claude Code, so a change must get a better result out of
the model with the least machinery: deterministic Factory-side logic before new model
behaviour, and short prompt additions.

One persona: the developer who runs Factory locally also configures it. Config files
define what is available and the UI lets that person choose from it. Designs and UI
text do not invent a separate operator or admin who decides for "users".

## Commands

```bash
docker compose up -d postgres            # database for spring-boot:run (plain `up -d` starts the whole stack, app on :8080 included)
mvn spring-boot:run -pl factory-app      # run the app (needs ANTHROPIC_API_KEY or FACTORY_LLM_* env vars)
mvn verify                               # full build + all tests (Docker required: Testcontainers starts its own PostgreSQL)
mvn -B -ntp -pl factory-core -am test -Dtest=SubFlowIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false   # one test class
mvn -B -ntp -pl factory-core -am test -Dtest='SubFlowIntegrationTest#method' -Dsurefire.failIfNoSpecifiedTests=false   # one method
node --test factory-app/src/test/js/poll.test.js   # console JS tests (not run by Maven or CI)
mvn validate                             # Checkstyle only; it also runs first in every build
```

Tests run under Surefire only. This repo has no Failsafe, so the workspace-level
`failsafe:integration-test -Dit.test=...` advice does not apply here; `*IntegrationTest`
classes run in the normal `test` phase. `-am` builds the sibling modules in the same
run, so a single test never runs against stale jars from `~/.m2`.

`spring-boot:run -pl factory-app` resolves the sibling modules from `~/.m2`,
not the reactor — after changing `factory-core`/`factory-agents`/a flow module,
run `mvn install -DskipTests` first or the app runs (or fails) on stale jars.

Trigger the Test Factory flow locally without Jira credentials:

```bash
curl -s -X POST localhost:8080/api/triggers/manual \
  -H 'Content-Type: application/json' \
  -d @factory-app/src/main/resources/samples/sample-story-inline.json
```

Then work the QA gates at `http://localhost:8080/reviews`, watch progress at
`/executions`, check connectors at `/api/status`. The rest of the console:
`/` (dashboard), `/flows`, `/artifacts`, `/workers`, `/prompts`, `/audit`,
`/status`. With zero connector credentials the flow still completes
end-to-end: execution runs in **advisory mode** and every external sync is
audited as `CONNECTOR_SKIPPED`.

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
factory-flow-test-factory   Test Factory plugin: flows/test-factory.yaml + 5 workers + prompts
factory-flow-dev-factory    Developer Flow plugin: dev-factory.yaml, Claude Code / Pi coding
                            runtimes, verification, delivery, PR feedback, task attempt policy
factory-app                 Spring Boot composition root: management console (server-rendered
                            Thymeleaf UI), REST API, Flyway
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
   gates — then collects mapped output artifacts. Flow E will use it to
   compose Flows A and D.

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
`prompts/<worker-id>/system.md` + `user.md` (single-pass `{{placeholder}}`
rendering via `PromptLoader`), calls the Spring AI `ChatClient`, parses the
response with `FrontmatterCodec` (YAML frontmatter + markdown body), and runs
post-processors (e.g. `SecretScanPostProcessor`; the Test Factory flow adds
`KarateSanityPostProcessor`). Test Factory's five workers live in
`factory-flow-test-factory/.../worker/`; its chain is
`flows/test-factory.yaml`: triage → test-spec → HITL gate 1 → test-automation
(Karate) → test-execution → HITL gate 2 → finalizer (TestRail/GitHub/Jira
sync).

### Developer Flow invariants

- Secrets never reach the database, artifacts, logs, argv or pages. Everything that
  comes from the coding CLI goes through `CodingRuntime.redact` (for Claude Code,
  `ModelCatalog.redact`), which must cover the secret of every model in
  `config/developer-models.yaml`.
- Hidden reasoning (thinking blocks, raw model messages) never reaches the transcript
  or a page. The guarding tests stay and get new cases when you add output: the
  `HIDDEN_*_SENTINEL` checks in `UiRenderSmokeTest` and `ClaudeCodeCodingRuntimeTest`,
  and `DeveloperExecutionViewTest#rendersConservativePiLifecycleLabelsWithoutMessageOrReasoningContent`.
- Do not change the step outputs in `flows/dev-factory.yaml` or `FeedbackArtifacts.VERSION`:
  the engine fails a step with missing or undeclared outputs, and a version change
  silently stops PR feedback on open PRs (`FeedbackWorker`).

### Management console (factory-app web UI)

Server-rendered Thymeleaf, no build step: shared layout + fragments under
`templates/`, tokens/app CSS and a small vanilla-JS layer under `static/`.
Conventions that are easy to violate silently:

- **Layout parameter shadowing** — every page is wrapped by
  `fragments/layout :: page(title, activeNav, content, scripts)`, and those
  four names stay in scope inside the inserted page body, shadowing any
  same-named model attribute. Never name a model attribute `title`,
  `activeNav`, `content`, or `scripts`.
- **Templates stay dumb** — controllers build page rows as public records; SpEL reads
  `${row['key']}` and `${row.key}` from a record, and a missing component fails the
  render instead of showing an empty cell. A row record carries every key its template
  reads (nullable where the old `Map` left a key out). Older pages still build `Map`
  rows; convert them when you rework the page. Shared row/format helpers live in
  `UiFormat` (timestamps, audit rows, event labels/tones, step labels).
- **Slice, not Page, for unbounded tables** — audit and artifact feeds page
  with `Slice`/`slicePagination`/`SliceResponse` so no per-request `count(*)`
  runs against append-only tables. Bounded lists (executions, reviews) use
  `Page`/`PageResponse`.
- **Exception split** — `ApiExceptionHandler` (`@RestControllerAdvice`, scoped
  to `@RestController`) returns JSON; `UiExceptionHandler` renders the HTML
  404 page for `@Controller` routes.
- Page-size params: REST validates strictly (`PageValidation.pageable`, 422);
  UI clamps leniently (`PageValidation.clampSize`).
- Every error a person sees says the cause and what to do, in plain words.
- Keep the existing visual system (`tokens.css`, `app.css`, existing fragments). Make a
  static HTML mockup only when the layout is unclear or the operator should choose
  between options; clear UI fixes go straight to code.

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
- Flyway migrations (`factory-app/src/main/resources/db/migration`) are append-only:
  never edit a committed one; a new one takes the next free `V<n>__` number.

## Open questions

`docs/open-questions.md` lists known gaps and ideas outside the current scope (coding
runtime tuning, issues seen in live runs). Check it when you work on the coding runtime
or the Developer Flow. Record new out-of-scope findings there instead of fixing them on
the side.

## Testing conventions

- Engine semantics (retry → escalation, poller crash recovery, sub-flow
  parent/child, HITL decisions) are covered by `factory-core` integration
  tests using fake workers and fake flow descriptors under
  `factory-core/src/test/resources/flows/`.
- Full Test Factory end-to-end lives in `factory-app` (`TestFactoryEndToEndTest`,
  `TestFactoryZeroCredentialsTest`): scripted LLM, WireMock'd Jira/GitHub/TestRail,
  including a QA amendment at gate 1 and the zero-credentials path. When
  changing flow behavior, extend these rather than mocking the engine.
- UI pages get two tiers: standalone MockMvc controller tests (dispatch and
  model shape) and `UiRenderSmokeTest` — the only tier that resolves the
  layout/component fragments. Anything content-bearing must assert real page
  text there; standalone tests cannot catch fragment-scope bugs.
- Shared test support lives in factory-app `support/` (`FactoryIntegrationTest`,
  `SharedDatabase`, `SharedDatabaseCleanup`, `SharedWireMock`, `DevFlowHarness`) and
  `tasks/TaskFixture`; test defaults are in `src/test/resources/config/application.yaml`.
  Build new test classes on them so they reuse the existing Spring contexts.
- A test is right when it checks the required, observable behavior. When one fails,
  first decide whether the code, the expectation, the fixture or the environment is
  wrong, and fix that. When behavior changes on purpose, update the tests that assert
  the old behavior instead of deleting them.

## Code style

The target is the style of the Eureka modules in this workspace (`mgr-tenant-entitlements`,
`mod-roles-keycloak`, `applications-poc-tools`): short methods, one job per class, types
instead of strings. `mvn verify` runs Checkstyle with `checkstyle/checkstyle.xml`;
`checkstyle/suppressions.xml` lists the methods that were over the limits when the check
was added. Never add an entry there; delete the entry when you bring its method under the limit.

- A method has at most 25 lines (blank and comment lines not counted) and at most 7
  parameters. A public method reads as a short list of steps, each step a private method
  named for what it does or returns (adapted from `mgr-tenant-entitlements`):

  ```java
  public ExtendedEntitlements performRequest(EntitlementRequest request) {
      var flow = flowProvider.createFlow(request);
      executeFlow(request, flow);
      var entitlements = request.getApplications().stream()
              .map(applicationId -> buildEntitlement(request.getTenantId(), applicationId))
              .toList();
      return buildEntitlements(entitlements, UUID.fromString(flow.getId()));
  }
  ```

  Parameters that travel together become a record.
- A class has one job. A bean with more than about 8 dependencies, or a class past about
  300 lines, does several jobs: split it along them.
- Types, not strings: states, outcomes and kinds are enums, structured data is a record.
  `Map<String, Object>` is only for JSON whose shape is open (audit details, external payloads).
- Values are non-null by construction. A lookup that can find nothing returns `Optional`;
  fields and parameters are never `Optional`. Check for null or blank input only at trust
  boundaries (HTTP, webhooks, external APIs, configuration).
- Lombok: `@RequiredArgsConstructor` for beans, `@Slf4j` for loggers, `@Getter`/`@Setter` on
  JPA entities for the fields that need them, `@Builder` where a constructor would take many
  optional values. Never add setters to artifact or audit entities. No `@Data`,
  `@SneakyThrows`, or `@EqualsAndHashCode`/`@ToString` on entities.
- `var` for local variables when the right side shows the type. Guard clauses and early
  returns instead of nested `if`.
- A method name says what it does or returns (`findActiveAttempt`, `toRow`, `isExpired`).
  Full words; `id`, `url` and `sha` are fine.
- Comments explain why, never what. Javadoc only where the name and types do not explain
  the contract.

## Code and commits

- Touch only what the task needs; don't refactor unrelated code.
- UI text, code comments and commit messages: plain English, B2 level.
- The working branch is `m1-sprint-pilot`, far ahead of `main`. A new session or a
  worktree agent starts from `main` by default: give it `m1-sprint-pilot` as the base, or
  make `git reset --hard <branch commit>` its first step. Parallel agents get disjoint
  files and never run `mvn install`: they share the snapshot jars in `~/.m2`.

## Communication

- Disagree when I'm wrong. Don't soften technical criticism and don't validate a flawed
  approach because I proposed it.
- No filler, no recaps of my request, no flattery, no offers to elaborate.
