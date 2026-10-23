# M1 Sprint Pilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Deliver the approved M1: simple startup, manual or automatic Jira delegation, autonomous completion to a verified PR or a clear stopped result, readable UI and preserved tokenomics.

**Architecture:** Extend the existing Factory. Keep Jira HTTP in connectors; put pilot discovery, durable task/admission state and coordination in a cohesive app-level pilot package; expose one narrow execution-policy port from Developer Flow. Reuse the existing engine, coding runtime, verification, delivery, accounting and server-rendered UI.

**Tech Stack:** Existing Java 21, Maven, Spring Boot 4.1.0, Spring Data JPA/PostgreSQL/Flyway, Thymeleaf, JUnit/AssertJ, MockRestServiceServer and Testcontainers. No new frontend framework or orchestration service.

**Spec:** [Approved product design](../specs/2026-10-23-autonomous-sprint-pilot-design.md), revision committed as 6fa6615.

**Status:** Planning only. No production code, runtime checks, paid inference or external writes have been performed for this plan. The source inspected was main at 6fa66152d5ce840d7f25012cd98e7f3cfc5a6762.

## Global Constraints

- **Simple first use.** **Autonomous daily operation.** **A friendly, readable UI.** **Preserved tokenomics.** All four remain release gates.
- “General configuration through the UI is optional.” Manual selection, the admission-mode switch and Retry are required operational controls.
- One local installation, one team board or saved filter, one explicit sprint; multiple Jira projects and prepared repositories. Each task targets one repository; tasks run sequentially.
- Sprint and eligible statuses are required. Assignee, project allowlist and label are optional AND restrictions; inherited saved-filter clauses remain in force.
- “Jira remains read-only.” No external notifications, merge or deployment.
- Normal PR destination is explicitly authorized folio-org; our live trials use personal forks. M1 publishes the branch and PR in the same configured repository.
- “Manual admission is required in M1 and enabled by default for testing.” Automatic mode requires an explicit scope/destination enable action.
- “One issue gets at most one initial attempt; only explicit Retry creates a subsequent attempt.” Withdrawing an admission before execution does not consume that initial attempt.
- “Proposed defaults are forty coding turns and a sixty-minute deadline from preparation through delivery, for both initial and retry attempts.” Queue and human-review waiting are excluded.
- Qualification profile: Claude Code 2.1.287, literal model identifier claude-sonnet-5-5, local CodeMie proxy, Java 21/Maven. These are targets, not evidence that a provider currently works.
- Initial PR ready releases the pilot reservation; human PR review/merge does not hold the execution slot.
- One processing installation per overlapping Jira scope; independent installations use non-overlapping filters. No distributed coordination in M1.
- No automatic fresh coding retries, new review loops, general recovery framework, private skill sharing, weaker verification, forced pushes or hidden destination changes.

## Review Focus

Each risk below has an owning test section; none is left as a general “be careful” instruction.

1. Jira issue keys, optional fields and saved-filter definitions can change. Immutable issue identity survives; changed authority cannot silently launch different work. Tests: S01, S02, S07.
2. A crash can happen between admission, execution creation, paid start and publication. Every boundary must produce one owned attempt or visible uncertainty, never a blind duplicate. Tests: S03, S05, S06, S08.
3. Existing Developer Flow can request human decisions or retry an infrastructure step. Pilot ambiguity must stop without HITL or another model call, while legacy interactive behavior remains intact. Tests: S04, S05.
4. Successful engine completion, expired timeout or killed Docker CLI is not sufficient evidence of a verified PR or an inactive workload. Tests: S05, S06, S09.
5. Historical, partial and cumulative usage differs from a single complete run. Retries and feedback must preserve cost meaning without double-counting; refresh must not lose a user's pending task selection. Tests: S09, S10.

---

## Execution model and order

This is one coordinated milestone with focused sections, not twelve independent projects. Keep one plan and one product specification; do not copy product decisions into competing plans.

Default execution is sequential. Each row is suitable for one focused implementation thread and a fresh code review. Do not start dependent work from unreviewed files. S06 can be developed independently of S07 after S05, but parallel coding is unnecessary for this pilot and creates avoidable shared-file conflicts.

| Section | Independently reviewable result | Depends on |
| --- | --- | --- |
| S01 | Team source resolves into a validated, complete Jira query | Existing connectors/catalog |
| S02 | Durable discovered tasks and admission/attempt history | S01 |
| S03 | Selected tasks enter a persistent queue and launch once | S02 |
| S04 | Pilot tasks use existing Developer Flow without intermediate HITL | S03 |
| S05 | Deadlines, inactive-workload checks and understandable outcomes | S04 |
| S06 | Authorized official/fork delivery with current task authority | S05 |
| S07 | Automatic admission with explicit scope and mode transitions | S03, S05, S06 |
| S08 | Explicit fresh Retry without duplicate work | S07 |
| S09 | Logical-task view and accurate cumulative tokenomics | S08 |
| S10 | Readable task list/detail and operational controls | S09 |
| S11 | One coordinated startup path and reusable readiness evidence | S01–S10 |
| S12 | Integrated acceptance and first-engineer pilot handoff | S11 |

New pilot processing stays disabled by default until S11 readiness allows it. Earlier sections are verified using local fixtures and fake providers; merging a partial section must not activate background coding.

### Repository boundaries and file map

The following prefixes expand to exact repository-relative directories:

| Prefix | Directory | Responsibility |
| --- | --- | --- |
| APP | factory-app/src/main/java/org/folio/factory/app | Application integration |
| APPTEST | factory-app/src/test/java/org/folio/factory/app | App/integration tests |
| FLOW | factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory | Existing coding/verification/delivery; narrow pilot policy port |
| FLOWTEST | factory-flow-dev-factory/src/test/java/org/folio/factory/devfactory | Flow tests |
| CONN | factory-connectors/src/main/java/org/folio/factory/connectors | HTTP adapters |
| CONNTEST | factory-connectors/src/test/java/org/folio/factory/connectors | HTTP contract tests |
| CORE | factory-core/src/main/java/org/folio/factory/core | Generic execution primitives only |
| CORETEST | factory-core/src/test/java/org/folio/factory/core | Generic engine regressions |
| RES | factory-app/src/main/resources | Templates, configuration and Flyway |

New pilot code lives together under APP/pilot, divided only where responsibilities justify it: source, persistence, admission, execution, startup and presentation. HTTP controllers stay under APP/web. New entity/repository scans must be added narrowly in APP/FactoryApplication.java; they currently scan core packages only.

| Keep/reuse | Planned adaptation |
| --- | --- |
| JiraConnector.getIssue, JiraRestConnector, JiraProperties | Add a separate read-only source/search interface; preserve existing issue reads and unrelated connector writes |
| IssueSnapshot.of, RepositoryPolicy.candidates/find, developer-repositories.yaml | Normalize immutable issue/project identity; use bounded consumed content and unique repository routing |
| PipelineRouter, StateManager, ExecutionPoller/ClaimService | Reuse engine execution and claims; add only the narrow prepare/create split in S03 |
| InitialCodingAttempt, CodingRuntime, DockerWorkloads | Reuse actual implementation/baseline/runtime; add durable pilot policy and remaining-budget checks |
| CandidateVerifier, VerificationReceipt, VerificationPolicy | Preserve acceptance of the exact frozen candidate and required report evidence |
| CandidateDelivery, PublicationGit, PullRequestRenderer | Preserve deterministic identity and non-force delivery; change destination authorization and add authority rechecks |
| AttemptUsageSummary, AttemptCharge, StepUsageSummary, FeedbackUi | Expose typed totals for aggregation; preserve execution-level compatibility |
| Existing templates/layout/CSS/poll.js | Add /tasks views using current components; keep /executions as the technical history |
| DeveloperPreflight/Probe, Compose/runtime image | Compose one startup operation; do not turn GET preflight/configuration into paid inference |

### Clean-code rules for every section

- Keep controllers and scheduled callbacks thin: validate input, call an application service, map the result.
- Use explicit immutable command/result types at cross-section boundaries. Keep untyped maps/JSON at existing artifact or HTTP boundaries, not as the pilot state machine.
- Keep source resolution, state transitions, dispatch, runtime lifecycle, publication and UI formatting separate. Do not build a single PilotService with all these responsibilities.
- Reuse existing utilities, transaction patterns, redaction and monetary calculation. Do not copy the harness, verifier, accounting formulas or feedback publication into a new subsystem.
- Keep initial coding, explicit fresh Retry and existing PR-feedback continuation distinct.
- Use short named methods and explicit transitions. No speculative plugin registry, generic workflow DSL, generic event bus or unrelated refactor.
- Test observable behavior and real persistence boundaries. PostgreSQL tests must prove uniqueness/rollback; mocks alone cannot prove concurrency.
- Every section gets a local reviewed commit containing only its scope. Fix material review findings before the next section.

### Test conventions

All commands below are for future implementation, run from the repository root. Existing integration suites here use Maven Surefire's test phase, including classes named IntegrationTest. Do not substitute the FOLIO-module Failsafe command.

For each section: add the named behavioral tests, run the focused command to observe the expected failing assertion or missing contract, implement the bounded change, rerun to BUILD SUCCESS, inspect the fresh reports for the selected tests, then review and commit. A skipped Docker test is a gap, not a pass.

Assertion fragments below belong inside the named tests; fixture setup follows existing tests in the owning module. New names are planned additions, not claims that files already exist.

## S01 — Resolve the team source and filter

**Outcome:** Read-only discovery can obtain all matches from a multi-project team source without creating executions.

**Files**
- Create CONN/jira/JiraSourceConnector.java and JiraSourceModels.java; extend JiraRestConnector.java and unconfigured wiring.
- Create APP/pilot/source/PilotSourceProperties.java and PilotScopeResolver.java.
- Modify CONNTEST/jira/JiraRestConnectorTest.java; create APPTEST/pilot/source/PilotScopeResolverTest.java.
- Add the commented source fields to RES/application.yaml and the future pilot template only when consumed.

**Interfaces**
- JiraSourceConnector.resolveSource(SourceReference) → ResolvedSource; getSprint(String sprintId) → Sprint; searchIssues(String jql, String cursor) → IssuePage.
- JiraSourceModels owns these immutable records: SourceReference(kind BOARD/FILTER, id); ResolvedSource(id, name, URI link, filterId, query); Sprint(id, name); IssuePage(List<DiscoveredIssue> issues, String nextCursor).
- DiscoveredIssue contains immutable id, current key, actual project key, components, created time and JiraIssue content. Do not derive immutable identity solely from the mutable issue key.
- PilotScopeResolver.resolve(PilotSourceProperties) → PilotScope; PilotScope is a nested immutable record containing source, sprint, effectiveQuery and authorityFingerprint.

- [x] **1. Pin the supported Jira read contract.** Inspect the configured deployment's supported board/filter/sprint/search reads or its exact documented API. Capture sanitized fixtures for the adapter. Existing getIssue uses REST v2; that alone does not establish the search API or pagination contract. Support the pilot deployment, not every Jira edition.
- [x] **2. Add filter/search tests.** Cover two projects, blank optional fields, configured assignee/label/project restrictions, quotes in values, inherited label clauses, grouped OR in the base query, ORDER BY preservation and all search pages. A failed later page must not become a successful empty/partial result. Assert canonical fingerprints ignore result ordering but change when authority changes.
~~~java
assertThat(scope.effectiveQuery()).contains("sprint", "status");
assertThat(scope.effectiveQuery()).doesNotContain("labels =", "assignee =");
assertThat(allPages).extracting(DiscoveredIssue::projectKey)
    .containsExactlyInAnyOrder("MGRAPPS", "MGRENTITLE");
~~~
- [x] **3. Run the focused command and confirm the new assertions fail for the current code.**
~~~sh
mvn -B -ntp -pl factory-app -am -Dtest=JiraRestConnectorTest,PilotScopeResolverTest,UnconfiguredConnectorsTest -Dsurefire.failIfNoSpecifiedTests=false test
~~~
- [x] **4. Implement the connector and resolver.** Use RestClient, existing credentials/timeouts and structured query construction. Parenthesize the inherited filter expression and append AND restrictions before its top-level ORDER BY; handle quoted/nested content, not a naive string split. Resolve a board to its existing saved filter. Accept URL/ID forms scoped to the configured Jira instance. No Jira mutations or model calls.
- [x] **5. Rerun the command, review source scope/error handling, and commit S01.** Done when invalid/unreadable scope fails clearly and complete fixtures yield correct matches without mandatory label/project/assignee restrictions.

**Done 2026-10-23.** Contract pinned to the configured Jira Cloud deployment (folio-org.atlassian.net): board/filter/sprint via documented GETs, search via POST /rest/api/2/search/jql with opaque nextPageToken (legacy startAt search is deprecated on Cloud). Focused command green: JiraRestConnectorTest 18, PilotScopeResolverTest 11, UnconfiguredConnectorsTest 3 — 32 run, 0 failed/skipped. Implemented by worker, reviewed by independent critic (ACCEPT WITH FIXES; all findings applied, including the Cloud search re-pin). Live-deployment qualification remains with S12.

## S02 — Persist discovery, tasks and attempt identity

**Outcome:** Discovery survives restart and represents unexecuted Jira tasks separately from engine executions.

**Files**
- Create APP/pilot/persistence/PilotTask.java, PilotAttempt.java, PilotInstallation.java and their Spring Data repositories.
- Create APP/pilot/source/PilotDiscoveryService.java and PilotDiscoveryPoller.java.
- Create RES/db/migration/V5__pilot_tasks.sql; recheck the next available Flyway number when implementation starts.
- Modify APP/FactoryApplication.java entity/repository scans narrowly.
- Create APPTEST/pilot/PilotPersistenceIntegrationTest.java and PilotDiscoveryIntegrationTest.java.

**Interfaces and storage**
- PilotDiscoveryService.sync() → SyncResult(complete, discoveredCount, lastSuccessfulSync, problem).
- PilotTask key is unique (normalized Jira instance, immutable issue ID). Store display key/title, actual project, latest bounded IssueSnapshot, observed source revision, current eligibility and routing explanation. Known dependencies yield an unsupported eligibility reason instead of a dependency scheduler; snapshot-recorded links are sufficient, never fetched. Keep consumed/approved snapshots on attempts separate.
- PilotAttempt stores taskId, monotonic ordinal, INITIAL/RETRY, requestId, approved input/scope/destination identity, admission state, executionId, startedAt/deadline, paid-start checkpoint, result/known publication identity. Use typed states and optimistic versioning.
- PilotInstallation is the single local operational record: current mode, accepted authority fingerprint, sync health, activeAttemptId and runtime-readiness reference.
- Enforce unique requestId, unique execution binding and unique (taskId, ordinal). Lock the logical task when reserving the initial attempt; enforce at most one live admission per task at the database boundary.
- Keep execution/artifact history in the same database. Pilot startup will require destructive retention disabled, matching its existing default; do not add a retention framework or silently change settings of non-pilot installations.

- [ ] **1. Add PostgreSQL/Flyway tests for identity and concurrency.** A renamed key with the same issue ID updates one task. Concurrent admission reservations cannot create two initial attempts. A withdrawn unstarted admission is retained but can be readmitted. Rollback cannot leave half an attempt binding.
~~~java
assertThat(tasks.findByJiraInstanceAndIssueId(instance, issueId)).isPresent();
assertThat(tasks.count()).isEqualTo(1);
assertThat(liveAdmissionsForTask).hasSize(1);
~~~
- [ ] **2. Add complete-sync tests.** Read all pages before a transaction updates the visible snapshot. A page failure preserves previous rows/last-success time, marks sync stale and blocks admission. Unsupported/ambiguous repository mappings and snapshot-recorded dependencies remain visible as ineligible. Discovery performs zero model and engine-launch calls.
- [ ] **3. Run new tests to fail, then implement schema/repositories/sync.** Reuse RepositoryPolicy and IssueSnapshot; no long database transaction around remote pagination. Preserve terminal task history when it leaves the current filter.
~~~sh
mvn -B -ntp -pl factory-app -am -Dtest=PilotPersistenceIntegrationTest,PilotDiscoveryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
~~~
- [ ] **4. Rerun with real PostgreSQL, review the migration and commit S02.** The five-minute callback only discovers; no execution is admitted automatically.

## S03 — Manual admission and atomic serial dispatch

**Outcome:** An explicit selected subset is queued durably and each admitted task launches one existing Developer Flow execution.

**Files**
- Create APP/pilot/admission/PilotAdmissionService.java and PilotCommands.java.
- Create APP/pilot/execution/PilotDispatcher.java and PilotExecutionLauncher.java.
- Modify CORE/trigger/PipelineRouter.java; create CORE/trigger/PreparedManualTrigger.java for the narrow preparation seam.
- Extend CORETEST/trigger/TriggerPreflightTest.java and PipelineRouterIntegrationTest.java.
- Create APPTEST/pilot/PilotAdmissionIntegrationTest.java and PilotDispatchIntegrationTest.java.

**Interfaces**
- PilotCommands.Selection(taskId, expectedVersion, inputFingerprint, destinationFingerprint).
- PilotCommands.RunSelected(requestId, expectedAuthorityFingerprint, List<Selection> selections).
- PilotAdmissionService.runSelected(RunSelected) → AdmissionResult(List<UUID> admittedTaskIds, Map<UUID,String> rejectedReasons).
- PilotDispatcher.dispatchNext() → Optional<UUID> executionId; PilotExecutionLauncher.launch(UUID attemptId) → UUID.
- PipelineRouter.prepareManual(String flowId, JsonNode payload, String dedupKey) → PreparedManualTrigger performs schema/size/preflight checks before the pilot transaction.
- PipelineRouter.routePrepared(PreparedManualTrigger) → UUID repeats current flow identity, dedup and database budget checks before creation; it accepts only the internal prepared type, not a REST request. Existing routeManual behavior and generic trigger endpoints remain compatible.

- [ ] **1. Add subset/stale/idempotency tests.** No default selection; only selected eligible rows are admitted. A changed individual row is rejected with a reason; a global stale/source problem rejects the batch. Duplicate request and stale double click resolve to the existing admission.
- [ ] **2. Add crash/transaction tests.** Inject failure after StateManager.createExecution but before attempt binding. The outer transaction must roll back execution, binding and active reservation together. Repeated dispatch after commit must return the same binding, including after the short trigger-dedup window.
~~~java
assertThat(result.admittedTaskIds()).containsExactly(taskAId);
assertThat(result.rejectedReasons()).containsKey(changedTaskId);
assertThat(executionsAfterInjectedRollback).isEmpty();
assertThat(secondDispatchExecutionId).isEqualTo(firstDispatchExecutionId);
~~~
- [ ] **3. Run the focused command to fail.**
~~~sh
mvn -B -ntp -pl factory-app -am -Dtest=PilotAdmissionIntegrationTest,PilotDispatchIntegrationTest,PipelineRouterIntegrationTest,TriggerPreflightTest -Dsurefire.failIfNoSpecifiedTests=false test
~~~
- [ ] **4. Implement admission and dispatch.** Re-read scope/task and run read-only prerequisites outside the short transaction. Inside it, lock/revalidate the installation and attempt versions, reserve one active attempt, invoke prepared engine creation with an attempt-specific dedup key and bind executionId atomically. StateManager.createExecution already participates in an outer REQUIRED transaction. Do not bypass router payload limits, budget or preflight.
- [ ] **5. Keep the queue separate from engine executions.** Create an engine execution only for the one selected next attempt, not for every queued row. Stable order is Jira created time then key. A task leaving scope before its first start is withdrawn; a manual input/destination change returns it for selection.
- [ ] **6. Rerun tests, review the generic router compatibility and commit S03.** Do not release the active reservation from RUNNING status alone; S05 supplies the outcome/ownership decision. Tests use fake launch/readiness dependencies until then.

## S04 — Autonomous execution using the existing flow

**Outcome:** A pilot task either proceeds through the current workflow or records a stopped result, without asking the engineer to finish intermediate steps.

**Files**
- Create FLOW/pilot/PilotAttemptPolicy.java and PilotExecutionContext.java.
- Create APP/pilot/execution/PersistedPilotAttemptPolicy.java.
- Modify FLOW/DevFactoryConfiguration.java; worker/IntakeWorker.java, IntakeResolveWorker.java, DecisionGateWorker.java, DevelopWorker.java, InitialCodingAttempt.java, CodingDecisionGateWorker.java and DevelopContinuationWorker.java.
- Modify FLOW/runtime/CodingProtocol.java for contribution/NEWS/PR instructions.
- Extend FLOWTEST/worker/IntakeWorkerTest.java, CodingDecisionGateWorkerTest.java, DevelopContinuationWorkerTest.java and DevelopReadinessTest.java.
- Create APPTEST/pilot/PilotAutonomousFlowIntegrationTest.java.

**Interfaces**
- PilotAttemptPolicy.find(UUID executionId) → Optional<PilotExecutionContext>.
- PilotExecutionContext contains attemptId, approved bounded IssueSnapshot, repository key, admission kind and input/scope/destination fingerprints. The persisted execution binding, not trigger JSON, grants pilot policy.
- PilotAttemptPolicy.claimPaidStart(UUID executionId) → boolean; a second call for the same attempt cannot start another initial coding invocation.
- PilotAttemptPolicy.recordStop(UUID executionId, String stage, String reason) → void; store the reason in the attempt and existing declared result artifacts.
- The app supplies the persistence-backed implementation; no-pilot resolution leaves legacy flow behavior unchanged.

- [ ] **1. Add pilot and legacy comparison tests.** Ambiguous routing, missing requirements and NEEDS_DECISION stop in pilot mode without HITL, while legacy manual behavior still opens its existing review.
- [ ] **2. Add the critical routing regression.** Intake currently seeds a recommended repository answer. Merely skipping DecisionGateWorker is insufficient: pilot ambiguity must produce a blocked/non-MAPPED intake, so IntakeResolveWorker cannot accept that seeded recommendation.
- [ ] **3. Add a complete stopped-chain test.** Use existing flow execution with fake runtime/Git; assert all declared outputs exist, original reason/usage survive and no downstream model/verification/publication runs after stop.
~~~java
assertThat(pilotHitlReviews).isEmpty();
assertThat(providerInvocations).isZero();
assertThat(publicationInvocations).isZero();
assertThat(resultReason).contains("ambiguous");
~~~
- [ ] **4. Run the focused command to fail, then implement policy integration.**
~~~sh
mvn -B -ntp -pl factory-app -am -Dtest=PilotAutonomousFlowIntegrationTest,IntakeWorkerTest,CodingDecisionGateWorkerTest,DevelopContinuationWorkerTest,DevelopReadinessTest,VerifyWorkerTest,DeliveryWorkerTest -Dsurefire.failIfNoSpecifiedTests=false test
~~~
- [ ] **5. Preserve the existing non-ready artifact chain.** Blocked intake → non-ready brief/request → DevelopWorker placeholders → VerifyWorker NOT_RUN → DeliveryWorker NOT_RUN → feedback initialization skips non-delivered work. Do not add generic engine branching or remove required artifacts.
- [ ] **6. Bind the consumed task to the admitted snapshot.** Intake must not replace the engineer's approved requirements with a second untracked Jira read. No automatic clarification continuation for pilot attempts; explicit Retry is a new execution in S08.
- [ ] **7. Complete the contribution prompt.** Require reading repository instructions/contribution policy, applicable NEWS/changelog rules, required checks and PR conventions. Use a representative fixture to assert those requirements reach the coding request. Do not copy private skills or claim IDE verification.
- [ ] **8. Rerun, review and commit S04.** Keep the flow ID dev-factory and existing feedback identity. Do not globally change max_attempts: 3; S05 prevents repeated paid work and converts pilot failures into bounded outcomes.

## S05 — Whole-attempt bounds, ownership and queue progress

**Outcome:** A failed task leaves evidence and releases the queue only when its workload is inactive; a shared outage pauses new starts clearly.

**Files**
- Create APP/pilot/execution/PilotOutcomeService.java and PilotRecoveryService.java.
- Create FLOW/pilot/AttemptDeadline.java; extend PilotAttemptPolicy and PilotExecutionContext.
- Modify FLOW/worker/BaselineBuild.java, InitialCodingAttempt.java and necessary runtime/verification/delivery call sites to carry remaining time.
- Reuse and narrowly extend FLOW/runtime/DockerWorkloads.java, Processes.java, candidate/CandidateFreezer.java, verification/CandidateVerifier.java and delivery/PublicationGit.java.
- Create APPTEST/pilot/PilotLifecycleIntegrationTest.java and FLOWTEST/pilot/AttemptDeadlineTest.java.
- Extend FLOWTEST/runtime/DockerWorkloadsTest.java and existing worker/runtime tests for replay.

**Interfaces**
- AttemptDeadline.remaining(Clock clock) → Duration; expiresAt is persisted once when preparation starts. Existing shorter operation limits still apply.
- PilotAttemptPolicy.deadline(UUID executionId) → Optional<AttemptDeadline>.
- PilotOutcomeService.refresh(UUID attemptId) → AttemptOutcome; Outcome contains state, stage, reason, known PR identity and workload-inactive evidence.
- PilotRecoveryService.reconcileActive() → void; scheduled/startup reconciliation examines the one reserved pilot attempt.
- Outcome states distinguish PR_READY, STOPPED, NEEDS_INSPECTION and a shared processing pause. Engine COMPLETED alone is not PR_READY.

- [ ] **1. Add fake-clock deadline tests.** Several individually short stages can exhaust the sixty-minute total; expiry before a next stage prevents its side effect. Queue time does not consume the budget and restart does not reset it.
- [ ] **2. Add replay/ownership tests.** A paid-start checkpoint with no definitive finish never starts coding again automatically. Lost ownership/failed cleanup holds the reservation. Stale workers cannot publish or mark a newer attempt complete.
~~~java
assertThat(deadline.remaining(clock)).isEqualTo(Duration.ofMinutes(5));
assertThat(secondPaidStartClaim).isFalse();
assertThat(nextTaskStartedWhenCleanupUnknown).isFalse();
~~~
- [ ] **3. Add queue-outcome tests.** Success/failure/success advances after a task-specific inactive failure. Shared provider/auth/environment error produces one installation pause. A PR ready task releases the slot before feedback/merge. FAILED_ESCALATED is not sufficient proof of a safe terminal stop.
- [ ] **4. Run the focused command to fail, then implement deadline and strict ownership handling.**
~~~sh
mvn -B -ntp -pl factory-app -am -Dtest=AttemptDeadlineTest,PilotLifecycleIntegrationTest,ProcessesTest,DevelopReadinessTest,ClaudeCodeCodingRuntimeTest,CandidateVerifierTest,ExecutionEngineTest -Dsurefire.failIfNoSpecifiedTests=false test
~~~
- [ ] **5. Pass the remaining budget through preparation, baseline, coding, verification and publication.** A per-command timeout of 3600 seconds does not implement a total deadline. Stop later side effects when the attempt is stopped, expired or no longer owned.
- [ ] **6. Confirm actual workload inactivity.** Reuse DockerWorkloads.cleanOwned(executionId, stepId) and the FeedbackResources cleanup pattern. Workload.close logs/swallowing cleanup failure or killing docker exec alone is not proof. Keep uncertainty visible and block another paid task until resolved.
- [ ] **7. Exercise real local-container termination and replay fixtures.**
~~~sh
FACTORY_DOCKER_TEST=true mvn -B -ntp -pl factory-flow-dev-factory -am -Dtest=DockerWorkloadsTest,ClaudeCodeDockerTest,FeedbackDockerTest -Dsurefire.failIfNoSpecifiedTests=false test
~~~
These tests use local synthetic providers; verify no configured live-provider endpoint is used.
- [ ] **8. Review and commit S05.** Expected task failures propagate stopped artifacts. Unexpected engine retries must still encounter the durable paid-start/ownership guards, never a fresh model invocation.

## S06 — Official/test destinations and current publication authority

**Outcome:** Verified work publishes to the selected authorized repository without changing destinations or creating duplicate PRs after uncertainty.

**Files**
- Modify FLOW/delivery/DeliveryTarget.java, CandidateDelivery.java and PullRequestRenderer.java; FLOW/worker/DeliveryWorker.java.
- Extend FLOW/pilot/PilotAttemptPolicy.java; create APP/pilot/execution/PilotPublicationGuard.java.
- Modify APP/developer/DeveloperPreflightProbe.java and config/developer-repositories.yaml only where required.
- Extend CONN/github/GitHubConnector.java and GitHubRestConnector.java for exact read-only publication lookup across PR states.
- Extend FLOWTEST/delivery/CandidateDeliveryTest.java, FLOWTEST/worker/DeliveryWorkerTest.java, CONNTEST/github/GitHubRestConnectorTest.java and APPTEST/developer/DeveloperPreflightProbeTest.java.
- Create APPTEST/pilot/PilotPublicationIntegrationTest.java.

**Interfaces**
- PilotPublicationGuard.requireCurrent(UUID executionId, CodingRequest consumedRequest, DeliveryTarget destination) → void.
- PilotAttemptPolicy.beforePublication(UUID executionId, CodingRequest request, DeliveryTarget destination) → void delegates to that guard for pilot executions.
- Persist PublicationIntent(attemptId, executionId, repository, baseBranch, headBranch, baseSha, treeSha, patchSha256) before the first remote mutation.
- GitHubConnector.findPullRequests(String repository, String headBranch, String baseBranch) → List<PullRequest> includes open/closed/merged results; the caller matches stored identity. Keep existing findOpenPullRequest for legacy use.

- [ ] **1. Add destination tests.** Authorized folio-org succeeds; unauthorized official destination fails before writes; explicit fork override still works; changed/revoked target and permission failure never fall back elsewhere. Branch and PR target remain in the same repo.
- [ ] **2. Add publication-authority tests.** Current normalized consumed input, scope eligibility and destination must match before push and again before PR creation. Changed requirements or unavailable recheck stops and preserves the candidate; verification identity/current policy checks remain mandatory.
- [ ] **3. Add lost-response tests.** Simulate push/create succeeding remotely then losing its response, and existing open/closed/merged PRs. Reconcile the stored original head identity or return Needs inspection; never infer “no PR” from open-only lookup.
~~~java
assertThat(createdPullRequests).hasSize(1);
assertThat(remoteWritesAfterRevocation).isZero();
assertThat(retryAllowedWithUnknownPublication).isFalse();
~~~
- [ ] **4. Run the focused command to fail, then implement narrowly.**
~~~sh
mvn -B -ntp -pl factory-app -am -Dtest=PilotPublicationIntegrationTest,CandidateDeliveryTest,DeliveryWorkerTest,GitHubRestConnectorTest,DeveloperPreflightProbeTest,DeveloperRepositoryConfigurationTest,FeedbackCatalogTest -Dsurefire.failIfNoSpecifiedTests=false test
~~~
- [ ] **5. Replace the folio-org owner ban with explicit configured authorization.** Retain repository syntax validation, required branch access, candidate/receipt validation, stable owned branch identity, non-force push and protection of user changes. Compose the PR body from trusted artifact data only, including the Jira issue link composed from issue identity; never embed untrusted issue Markdown.
- [ ] **6. Reuse existing safe recovery; stop when evidence is insufficient.** A valid delivered PR becomes PR ready with its link. Do not build automatic repair of arbitrary branch conflicts or a general reconciliation UI. Read-only inspection may resolve known identity; uncertain outcomes keep Retry disabled.
- [ ] **7. Rerun, review and commit S06.** All tests use local Git/mocked GitHub. A real official PR is not part of this section's authorization.

## S07 — Automatic admission and authority changes

**Outcome:** The engineer can deliberately disable the preliminary gate and delegate all current/future matches without changing queue or retry semantics.

**Files**
- Create APP/pilot/admission/PilotModeService.java and PilotAutomaticAdmission.java.
- Extend PilotDiscoveryService, PilotAdmissionService and persisted installation state.
- Create APPTEST/pilot/PilotAutomaticAdmissionIntegrationTest.java.

**Interfaces**
- PilotModeService.setMode(AdmissionMode mode, String expectedAuthorityFingerprint) → ModeResult(mode, admittedQueueCount, explanation).
- AdmissionMode is REVIEW_BEFORE_RUN or AUTOMATIC.
- PilotAutomaticAdmission.admitEligible() → AdmissionResult uses the same task locks/admission records as manual selection; no second queue implementation.

- [ ] **1. Add automatic-mode tests.** Explicit enable covers existing and future eligible tasks. No-label filtering works. An unchanged restart preserves mode/history. Returning to manual stops new automatic admissions and leaves existing queued/running work delegated.
- [ ] **2. Add changed-authority tests.** Saved-filter query, routing or destination changes return the installation to manual and invalidate affected queued approvals. Initial tasks return for selection/withdrawal; queued retries use S08's stopped-result rule. Unchanged tasks retain their explicit delegation.
~~~java
assertThat(modeAfterUnchangedRestart).isEqualTo(AUTOMATIC);
assertThat(modeAfterFilterChange).isEqualTo(REVIEW_BEFORE_RUN);
assertThat(initialAttemptsForAlreadyAttemptedTask).hasSize(1);
~~~
- [ ] **3. Run the focused command to fail, then implement the mode transitions.**
~~~sh
mvn -B -ntp -pl factory-app -am -Dtest=PilotAutomaticAdmissionIntegrationTest,PilotAdmissionIntegrationTest,PilotDispatchIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
~~~
- [ ] **4. Revalidate before dispatch/publication.** Automatic initial work may consume refreshed input within unchanged authority; manual initial/explicit Retry cannot silently accept a changed approved input. Partial/stale discovery blocks new starts.
- [ ] **5. Rerun, review and commit S07.** No automatic sprint rollover, mandatory assignee or hidden label condition.

## S08 — Explicit fresh Retry

**Outcome:** A failed task can receive one deliberate new attempt with current requirements/base, without losing its history.

**Files**
- Create APP/pilot/admission/PilotRetryService.java.
- Extend PilotCommands, PilotAdmissionService and PilotDispatcher.
- Create APPTEST/pilot/PilotRetryIntegrationTest.java.

**Interfaces**
- PilotCommands.Retry(requestId, taskId, expectedVersion, inputFingerprint, destinationFingerprint).
- PilotRetryService.retry(Retry) → RetryResult(attemptId, executionId, state, explanation); executionId may be absent while queued.
- Reuse the S03 queue/launch mechanism and S05–S06 ownership/publication checks. Do not call InitialCodingAttempt.retry; that method is existing clarification continuation on the old base.

- [ ] **1. Add retry eligibility tests.** Terminal unsuccessful and proven inactive can retry; queued/running, unknown ownership/publication and PR-ready cannot. Current scope/routing still applies; an optional label is not independently required.
- [ ] **2. Add fresh-input/idempotency tests.** A deliberate Retry uses current bounded issue input and current source base, a new execution/owned attempt branch, and retains previous artifacts/costs. Duplicate and stale clicks never create two attempts.
- [ ] **3. Add queued-retry change tests in both modes.** If approved input/destination/scope changes before start, preserve the withdrawn admission, restore the prior stopped result with a reason and require another Retry. Never put an attempted task into initial checkbox selection.
~~~java
assertThat(newAttemptId).isNotEqualTo(previousAttemptId);
assertThat(secondClick.attemptId()).isEqualTo(firstClick.attemptId());
assertThat(attemptHistory).hasSize(2);
assertThat(changedQueuedRetry.state()).isEqualTo("STOPPED");
~~~
- [ ] **4. Run the focused command to fail, then implement explicit retry transitions.**
~~~sh
mvn -B -ntp -pl factory-app -am -Dtest=PilotRetryIntegrationTest,PilotAutomaticAdmissionIntegrationTest,PilotPublicationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
~~~
- [ ] **5. Rerun, review and commit S08.** No automatic retry loop, reset-history shortcut or force-push reuse of an old branch.

## S09 — Task projection and preserved tokenomics

**Outcome:** One logical task has a clear current result, complete attempt history and numerically correct totals.

**Files**
- Create APP/pilot/presentation/PilotTaskQuery.java, PilotTaskView.java and PilotTaskUsage.java.
- Extend APP/web/AttemptUsageSummary.java and AttemptCharge.java only to expose typed aggregation before formatting.
- Reuse DeveloperExecutionView, StepUsageSummary, FeedbackUi, DeveloperCostEstimator and UiFormat.
- Create APPTEST/pilot/PilotTaskViewTest.java and PilotTaskUsageTest.java; extend APPTEST/web/AttemptUsageSummaryTest.java.

**Interfaces**
- PilotTaskQuery.list(Pageable page) → Page<PilotTaskView>; detail(UUID taskId) → PilotTaskView.
- PilotTaskView contains task identity/Jira link, title, state/reason/stage, destination, PR link, relevant times, attempt history, allowed actions and PilotTaskUsage.
- PilotTaskUsage contains reported token categories, per-currency BigDecimal estimates and completeness/provisional flags. Aggregate typed accounting contributions by execution/attempt; never parse formatted UI strings.

- [ ] **1. Add state-projection tests.** Awaiting selection, withdrawn, unsupported, working, stopped, uncertain and PR ready have distinct meaning. Engine COMPLETED with failed verification is stopped. Keep history accessible after the task leaves the Jira filter.
- [ ] **2. Add numerical preservation tests using existing fixtures.** Cover legacy step accounting, current attempts, feedback rounds, retries, streamed cumulative snapshots, mixed currencies, cache-write TTL, unknown rates and partial usage. Any incomplete component keeps the task total incomplete.
~~~java
assertThat(taskUsage.estimatesByCurrency().get("USD"))
    .isEqualByComparingTo("1.24");
assertThat(taskUsage.complete()).isFalse();
assertThat(unstartedUsage.estimatesByCurrency()).isEmpty();
~~~
- [ ] **3. Run the focused command to fail, then implement typed projection/aggregation.**
~~~sh
mvn -B -ntp -pl factory-app -am -Dtest=PilotTaskViewTest,PilotTaskUsageTest,AttemptUsageSummaryTest,DeveloperExecutionViewTest,DeveloperCostEstimatorTest,ExecutionUiControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
~~~
- [ ] **4. Compare old and new execution views on identical recorded fixtures.** Preserve input/output/cache breakdowns, rates, totals and feedback. Task totals sum each contribution once; duration excludes human review. Setup smoke is a separate account, not a fake Jira task.
- [ ] **5. Rerun, review and commit S09.** No new pricing model or sprint analytics.

## S10 — Two readable screens and task controls

**Outcome:** Engineers can select work, switch admission mode, inspect results/costs and retry without engine knowledge.

**Files**
- Create APP/web/PilotTaskUiController.java.
- Create RES/templates/pilot-tasks.html and pilot-task.html; extract reusable developer evidence/accounting fragments only where shared.
- Modify RES/templates/fragments/layout.html navigation and existing CSS/poll.js narrowly.
- Keep existing executions.html, execution.html and their URLs as technical/legacy views.
- Create APPTEST/web/PilotTaskUiControllerTest.java; extend APPTEST/UiRenderSmokeTest.java.

**Interfaces**
- GET /tasks → list; GET /tasks/{taskId} → detail.
- POST /tasks/run-selected accepts S03 RunSelected data.
- POST /tasks/mode accepts S07 mode and expected authority fingerprint.
- POST /tasks/{taskId}/retry accepts S08 Retry data.
- POST routes delegate to services, then redirect with per-row results or clear conflict explanations. GET routes never admit work or invoke a model.

- [ ] **1. Add controller tests.** Correct command mapping, redirect/results, stale submission, no selection, disabled eligibility, known PR, unavailable Retry and GET-with-zero-provider-calls.
- [ ] **2. Add actual Thymeleaf rendering cases.** Test all list/detail states, no rows, long titles, escaped issue text, direct Jira links, PR links, many attempts and incomplete costs. Existing UiRenderSmokeTest is the renderer; MockMvc assertions alone do not prove template validity.
- [ ] **3. Run the focused command to fail, then implement the two screens.**
~~~sh
mvn -B -ntp -pl factory-app -am -Dtest=PilotTaskUiControllerTest,UiRenderSmokeTest,ExecutionUiControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
~~~
- [ ] **4. Put user results first.** Key opens Jira; title/details opens Factory. Show scope/mode/sync, readable state, destination, relative times and cost. Keep UUID/step internals below technical links. Reuse current typography/layout tokens, with adequate spacing and hierarchy.
- [ ] **5. Preserve selection during refresh.** Suppress automatic page reload while checkboxes are selected or show an update notice. Select-all applies only to the visible page; show the selected count. Server revalidation remains authoritative.
- [ ] **6. Verify in a browser with local fixture data.** At a normal laptop width, select a subset, identify a failed result/reason, open Jira/PR, find Retry and cost detail. Check keyboard labels, focus, non-colour status meaning and long content. Save a small before/after screenshot set; no live task mutation is needed.
- [ ] **7. Review and commit S10.** Full settings editor, exact mockup pixels, new charts and navigation redesign are not required.

## S11 — One startup action and trustworthy readiness

**Outcome:** A first-time engineer reaches discovery/delegation through the short prepared path, with paid smoke explicitly bounded and not repeated on unchanged restart.

**Files**
- Create APP/pilot/startup/PilotStartupService.java, PilotReadiness.java and PilotRuntimeSmoke.java.
- Create APP/web/PilotStartupController.java for explicit startup/status operations; keep DeveloperPreflightController GET read-only.
- Extend APP/developer/DeveloperPreflight.java and DeveloperPreflightProbe.java by reuse/composition, not by making read probes invoke models.
- Create .env.pilot.example and scripts/start-pilot.sh; modify docker-compose.yml, compose.runtime-claude-code.yml, RES/application.yaml and README.md as needed.
- Extend persistent readiness/check usage in a new Flyway migration rather than editing S02's committed migration.
- Create APPTEST/pilot/PilotStartupTest.java and PilotRuntimeSmokeTest.java; extend ComposeConfigurationTest and existing preflight/configuration tests.

**Interfaces**
- PilotStartupService.start() → PilotReadiness; status() → PilotReadiness performs no model call.
- PilotRuntimeSmoke.check(RuntimeFingerprint fingerprint) → SmokeEvidence, containing tested image/profile/model/connection/instructions identity, tool-use success, usage/rate evidence and result.
- POST /api/pilot/start runs the explicit operation; GET /api/pilot/status reports progress/result. scripts/start-pilot.sh orchestrates Compose and this operation as one user command.
- PilotReadiness separates discoveryAvailable, executionReady, blockers and reusable evidence identity. Missing rates, failed/unrun inference or unknown usage are not execution-ready.

- [ ] **1. Add startup tests.** Fresh setup, failed prerequisite with a concrete correction, discovery available while execution blocked, unchanged restart with zero new model calls, affected configuration invalidating evidence, and shared outage followed by the same start action.
- [ ] **2. Add retention/runtime tests.** The prepared pilot profile keeps destructive retention disabled; refuse pilot execution activation if it is enabled rather than deleting attempt/evidence history. Do not silently alter non-pilot retention. Existing inference NOT_RUN and pricing WARN cannot satisfy pilot readiness.
~~~java
assertThat(readiness.executionReady()).isFalse();
assertThat(modelCallsAfterUnchangedRestart).isZero();
assertThat(readiness.blockers()).contains("Runtime check has not completed");
~~~
- [ ] **3. Run focused tests to fail, then implement coordination.**
~~~sh
mvn -B -ntp -pl factory-app -am -Dtest=PilotStartupTest,PilotRuntimeSmokeTest,DeveloperPreflightTest,DeveloperPreflightProbeTest,DeveloperPreflightControllerTest,CodingRuntimeConfigurationTest,DeveloperRepositoryConfigurationTest,ComposeConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test
~~~
- [ ] **4. Implement the short template/guide.** Required values are connections, board/filter, sprint and enabled prepared destinations. Provide two qualified mappings and official/test examples; optional restrictions stay optional. Document how to obtain sprint ID, same-repository branch permission and non-overlapping installation filters. No manual disable/check/flag/restart choreography.
- [ ] **5. Implement a separate bounded smoke.** From the actual pinned coding image, make one tiny tool-using invocation with proposed smoke limits of five turns and two minutes, no task repository, Jira mutation or publication. Record setup usage separately. The start action prints/discloses this before invocation; ordinary page views do not trigger it.
- [ ] **6. Gate starts before restart recovery can pay again.** Existing queued pilot work must wait for applicable readiness; S05 ownership/paid checkpoints remain authoritative. Store only non-secret evidence/fingerprints, never credential values. Relevant credential/connection change invalidates the affected readiness without exposing secrets.
- [ ] **7. Rerun tests and verify Compose in an isolated test environment.** Check mounted source/config/persistent storage, pinned image/build path, proxy reachability naming, published loopback ports and redacted failures. Existing coding container's claude --version is not inference evidence.
- [ ] **8. Review and commit S11.** Actual provider qualification is separately bounded in S12; mock startup tests do not prove current CodeMie availability.

## S12 — Integrated qualification and pilot handoff

**Outcome:** The full M1 scenario is demonstrated, with recorded limits and no hidden dependency on its author.

**Files**
- Create APPTEST/pilot/PilotEndToEndTest.java and PilotRestartIntegrationTest.java.
- Extend local fixture tests from previous sections instead of creating a second simulator.
- Finalize README.md pilot guide.
- Record executed evidence in a concise final section of this plan; link only necessary logs/screenshots. Do not create a competing specification.

- [ ] **1. Add full deterministic scenarios.** Use PostgreSQL, mock Jira/GitHub and local test repositories: multi-project discovery → manual subset → success/failure/next task → explicit Retry; automatic current/future admission; restart; changed filter/input/destination; lost publication response; incomplete cost. Assert no Jira writes, intermediate HITL or duplicate coding/PR.
- [ ] **2. Run focused integration and final reactor regression.**
~~~sh
mvn -B -ntp -pl factory-app -am -Dtest=PilotEndToEndTest,PilotRestartIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -ntp verify
~~~
Record actual fresh reports, passed/skipped counts and reasons. Do not reuse historic test counts as current evidence.
- [ ] **3. Run the local Docker fixture suites if affected by integration changes.** Use S05's synthetic-provider command; confirm workload cleanup and no unrelated container deletion.
- [ ] **4. Perform one separately authorized bounded live qualification.** First use the real coding image for S11's disclosed smoke. Then use a small agreed set of suitable tasks in personal forks: at least two project/repository mappings, one successful PR, one understandable stopped result, next-task continuation and one deliberate Retry. Record exact runtime/config identity, usage completeness, result links and observed review effort. No official PR, merge or Jira write is included.
- [ ] **5. Have a first-time engineer follow the guide.** They must reach delegation independently, understand results and costs, and identify the correction for a failed prerequisite without source-code reading or maintainer intervention. Record friction; fix required product barriers, defer decoration.
- [ ] **6. Run a fresh whole-branch review against the design.** Inspect source and tests, not just prior agents' summaries. Check generic engine/legacy feedback compatibility, immutable identity, no duplicated calculations, no new broad abstractions and every acceptance row.
- [ ] **7. Commit final qualification fixes and stop.** A skipped live qualification or unavailable provider remains an explicit unverified gate; it is not evidence that M1 is fully ready for engineers.

## Traceability to the approved acceptance scenarios

| Product outcome | Implementation / evidence |
| --- | --- |
| Fresh setup and actionable startup error | S11, first-engineer exercise S12 |
| Team scope, optional filters, no new Jira metadata | S01–S02; combined scenario S12 |
| Multiple repositories and visible unsupported work | S01–S02, S09–S10 |
| Manual gate / chosen subset | S03, S10 |
| Automatic mode, restart and scope changes | S07; S05/S11 recovery gate |
| Complete autonomous coding/checks/NEWS/PR | S04–S06, S12 |
| Official versus test destination | S06, S11; official routing/connector tests, live trials only in forks |
| Failure, next task, limits and shared pause | S05 |
| Explicit Retry and retained history | S08–S09 |
| Durable admission and uncertain publication | S02–S03, S05–S06 |
| Friendly UI and clickable Jira/PR | S09–S10, first-engineer exercise |
| Preserved tokenomics, feedback and incomplete data | S09, S11 smoke accounting, S12 |
| No Jira writes/notifications/merge/deploy | S01, S06, S12 |

## Handoff between focused threads

The user plans separate focused implementation threads. This turn creates none. When the user authorizes execution/orchestration, start with S01 only; creating or messaging user-visible chats requires that authorization.

One section thread owns its listed files and dependencies. An orchestrator reads its diff and verification, requests one independent focused review, integrates the accepted commit, updates this plan's checkboxes/evidence, then starts the next section from that integrated revision. Do not treat “agent finished” as acceptance, and do not run several writers on the same checkout.

Use an isolated managed worktree when requested/appropriate for execution. Preserve existing unrelated changes; never reset/stash them. Avoid additional permanent status documents: this plan tracks section completion, while the design remains product authority.

### Prompt template for one implementation thread

~~~text
Implement section SXX of docs/superpowers/plans/2026-10-23-m1-sprint-pilot-implementation.md only.

Read the repository/workspace instructions, the linked approved design,
Global Constraints, Review Focus, the section's interfaces and its prerequisites.
Baseline: <integrated branch and commit>. Required completed sections: <IDs>.

Complete the section's local production code and meaningful tests. Keep current
module boundaries, small readable methods, typed transitions and existing utilities.
Do not begin the next section, redesign the engine/frontend, copy private skills,
weaken verification or silently expand product scope.

Use existing patterns. Verify current files before changing them.
Run the section's focused checks; report skips and missing runtime evidence honestly.
No paid model calls, real Jira writes, live PR creation, push, merge or deployment
unless separately authorized in this thread's concrete task.

Before finishing, inspect the actual diff and obtain a fresh focused code review.
Fix material findings, make a scoped local commit and stop.

Report: outcome, changed files, exact test results, remaining limitations,
commit ID and any interface change that the next section must know.
~~~ 

S12's live qualification needs its own concrete bounded authorization; the template does not grant it. Future orchestration may use user-visible threads or internal implementation/review agents, but the section boundary and acceptance gate stay the same.

## Planning verification

The plan was checked against the approved specification and current source. Source discovery was split across intake/persistence, runtime/delivery and UI/startup reviewers; final decomposition, interface consistency and specification coverage were reviewed here. All checks listed under S01–S12 are future work, not results from this planning turn.
