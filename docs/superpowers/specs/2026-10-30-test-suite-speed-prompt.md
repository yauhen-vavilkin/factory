# Prompt: faster test suite for factory-app and factory-flow-dev-factory

Run this after `2026-10-30-dev-flow-cleanup-prompt.md` has landed (its branch merged or checked out), in a
Claude Code session at the repository root, Claude Opus 5.5, effort xhigh, Docker running. Work
autonomously. `factory-core` is out of scope even though its 14 Spring test classes spend about 48 s of
67 s on context startup for the same reasons; mention that in the report, do not touch it.

## Goal

Cut the wall-clock time of `mvn -B -ntp verify` for `factory-app` and `factory-flow-dev-factory` by at least
half without deleting a test or weakening an assertion, and remove the copied test scaffolding. Evidence and
numbers: `docs/superpowers/specs/2026-10-30-dev-flow-code-audit.md`, section 9.

## Read first

`AGENTS.md` ("Testing conventions"), audit section 9, `factory-app/pom.xml` and `factory-core/pom.xml`
Surefire comments (`reuseForks=false`, with the reasons given at commit `21a952f`), and the 20
`@SpringBootTest` classes of factory-app.

## Rules

- No test is deleted and no assertion is weakened. Test counts per module are equal before and after
  (except tests you add). Docker-gated tests (`FACTORY_DOCKER_TEST`) keep their gates.
- Main code changes only where a test cannot otherwise be made fast, and then the smallest possible
  change with no new configuration key. Example: `FeedbackPrMonitor.poll()` is public; a test calls it
  instead of waiting 30 s for the schedule. Nothing in main code changes behaviour.
- No `Thread.sleep` in tests. Waiting uses Awaitility with a short poll interval and an explicit reason.
- Isolation by cleanup, not by fresh JVMs or fresh databases: a test class leaves no rows, threads, servers
  or static state that another class can see. Any `@DirtiesContext` that survives has a one-line comment
  saying which state it protects against.
- Flakiness is a failure: the final state passes `mvn -B -ntp clean verify` three times in a row.
- Measure. Record per class `time=` from `*/target/surefire-reports/TEST-*.xml`, the number of Spring
  contexts started (count "Started FactoryApplication" in the test output) and the wall-clock of the two
  modules, before and after. Delete stale reports with `mvn clean` first; `factory-app/target` holds 22
  reports from classes that no longer exist.

## Steps, in order

1. **Baseline.** `mvn -B -ntp clean verify` twice; keep the second run's numbers.
2. **Why `reuseForks=false`.** Set `reuseForks` to `true` for factory-app only and run the module's tests
   three times. Collect every failure. For each, find the leaking component (engine poller threads from a
   context that was closed, the PR monitor executor, a static `WireMockServer` not stopped, keep-alive
   connections of a `RestClient` pool from a closed context) with thread dumps and the failing test's
   stack. Fix the leak at its source in test code (stop servers in `@AfterAll`, close contexts cleanly,
   register shared servers once per JVM) or, if a main bean does not stop on context close, fix its
   lifecycle in the smallest way. Only when three runs are green remove the Surefire override and its
   comment from `factory-app/pom.xml`.
3. **One database per JVM.** A test support class in `factory-app/src/test` starts one
   `PostgreSQLContainer` per JVM and exposes its connection to every context (a `DynamicPropertyRegistrar`
   bean or an imported `@TestConfiguration` with `@ServiceConnection`; choose the one that makes the
   container a true singleton across contexts). Flyway runs once per context against the same database;
   check that repeated starts are idempotent. Replace the 20 per-class `@Container` fields.
4. **One meta-annotation.** `@FactoryIntegrationTest` carries `@SpringBootTest`, `@Import(StubLlmConfiguration)`,
   the shared database and the common properties (`spring.ai.model.chat=none`,
   `factory.engine.enabled=false`); a test `application.yaml` under `factory-app/src/test/resources` holds
   the defaults that most classes repeat. Group the remaining differences into as few profiles as possible
   (engine running with `poll-interval-ms=250`; tasks execution enabled; WireMock-backed Jira/GitHub). Aim
   for at most five distinct contexts in factory-app. A class that genuinely needs its own context says
   why in one comment line.
5. **Shared WireMock.** One `WireMockServer` per JVM for the four classes that start their own, registered
   through the same registrar, `reset()` in `@BeforeEach`.
6. **The two slow classes.** `TaskEndToEndTest`: replace the 90 s `await` that waits for the scheduled PR
   monitor by calling the monitor's `poll()` from the test and awaiting the gate change; keep the assertion.
   `PrFeedbackIntegrationTest`: build the git repository once per class (`@BeforeAll`, `@TempDir`), reset
   its state per test with `git` commands instead of re-initialising; profile the 12-round test and remove
   only waiting, never rounds.
7. **dev-factory sleeps and fixtures.** `ProcessesTest`: timeouts under test become sub-second (`timeout 1 s,
   process sleeps 2 s`), the `Thread.sleep(6000)` goes; `DevelopReadinessTest`: find what the
   `Thread.sleep(4000)` waits for and replace it with an injected clock or Awaitility;
   `CandidateVerifierTest`, `CandidateDeliveryTest`, `FeedbackGitTest`: build the git fixture once per class
   and copy it per test when a test mutates it.
8. **Shared fixtures.** One `TaskFixture` (the `new Task(...)` / `TaskAttempt` builders, `admit`,
   `resetTaskState`, `forceState`, `failExecution`, `writeArtifact`) and one `DevFlowHarness` (`git`,
   `createRepository`, `createGitRoot`, `stubIssue`, `stubWorkloads`, `awaitPendingReview`, `latest`) under
   `factory-app/src/test`, used by the nine `Task*` classes, `UiRenderSmokeTest`, `TaskEndToEndTest`,
   `TaskAutonomousFlowIntegrationTest` and `DeveloperIntakeDecisionTest`. Each helper keeps the exact
   fixture values the tests assert on.
9. **Three green runs**, then the report.

## Acceptance

- factory-app and factory-flow-dev-factory together at or under half the baseline wall-clock; the number of
  Spring contexts started in factory-app at or under five; one PostgreSQL container per module run.
- Same test counts; three consecutive green `clean verify` runs; no `Thread.sleep` in the two modules'
  tests; `reuseForks` override gone from `factory-app/pom.xml`.
- Checkstyle clean, no new suppression, no new configuration key, no behaviour change in main code.

## Reporting

Add a "Status" line under each bullet of audit section 9 (`Done: <commit>` or `Skipped: <reason>`), and a
"Result" table with before and after numbers per module (wall-clock, Surefire sum, contexts started, test
counts). One commit per step; messages explain which leak or wait was removed. Your final message is that
table plus the commit list.
