# Developer Flow code audit (2026-10-30)

Scope: `factory-flow-dev-factory` and the Developer Flow parts of `factory-app` (`tasks`, `developer`,
`web/Developer*`, usage and cost classes, templates, static assets, tests). `factory-core`,
`factory-connectors`, `factory-agents` and the Test Factory flow are out of scope. No behaviour, UX or
feature change is proposed here; every item is a code, configuration or test-infrastructure cleanup.
Three items need an operator decision because they remove a documented capability (section 10).

Numbers come from the working tree at commit `7e50d99` and from the Surefire reports of the local build
of 2026-10-30 19:57 to 20:02. Line references are `file:line` at that commit.

## 1. Shape

| Module | main lines | test lines | methods | private | methods with at most 3 body lines |
|---|---|---|---|---|---|
| factory-flow-dev-factory | 9,486 | 6,083 | 567 | 283 | 252 |
| factory-app | 9,640 | 15,335 | 523 | 275 | 194 |

Largest packages (main lines): `app/web` 4,554, `devfactory/runtime` 3,112, `devfactory/worker` 1,775,
`devfactory/feedback` 1,730, `app/tasks/execution` 1,054, `app/tasks/presentation` 1,012,
`app/developer` 906, `devfactory/delivery` 865.

Configuration surface: 96 distinct env placeholders in `application.yaml`, 113 env vars passed by the
`factory` service in `docker-compose.yml`, 42 rows in the README env table. `Map<String, Object>`
appears 123 times in dev-factory main code and 162 times in factory-app main code (19 in core).
`checkstyle/suppressions.xml` still lists 40 methods over the 25-line / 7-parameter limits; 15 of them
are `ParameterNumber` on constructors or bean methods of the classes named below.

## 2. Legacy and dead configuration layers

Each of these exists for a configuration shape that the shipped files no longer use.

1. **Legacy Pi settings inside `DevRuntimeProperties.Coding`** (`runtime/DevRuntimeProperties.java:78-115`).
   The flat fields `image, provider, model, baseUrl, api, apiKey, reasoningEffort, maxOutputTokens` are the
   old Pi configuration (`FACTORY_MODEL_*`, `FACTORY_DEV_FACTORY_CODING_IMAGE`; `application.yaml:170-178`,
   `docker-compose.yml:97-103`). `piProfile()` (`:103-115`) merges them field by field under `pi.*`. The
   README calls them legacy (`README.md:617, 623, 624`). Readers: `PiCodingRuntime:25` and
   `DeveloperPreflight.java:268, 281, 455, 481`. Flatten to `coding.runtime`, `coding.pi.*`,
   `coding.claude-code.*`; drop the 9-arg `Coding` constructor (test-only) and its suppression.
2. **`DevDeliveryProperties`** (`delivery/DevDeliveryProperties.java`, 72 lines). `targets` is never set by
   shipped configuration (only tests set `factory.dev-factory.delivery.targets.*`); `effectiveTargets` and
   `legacyOverride` (`:37-71`) exist only to merge that absent map over the catalog;
   `requireTarget(String)` (`:16`) has no main caller; `createPullRequest` is hard-coded `true`
   (`application.yaml:202`, no env var), so the branch at `worker/DeliveryWorker.java:165` is dead. Replace
   with a `requireDelivery(key)` on `DevFactoryProperties` that returns `Repository.delivery()`, and delete
   the README paragraph at `README.md:323`. `FeedbackWorker.isDestinationAuthorized` (`:404`) then iterates
   the catalog directly.
3. **`RemovedDeliveryAuthorizationCheck`** (`app/RemovedDeliveryAuthorizationCheck.java`, 38 lines, test 41):
   refuses to start when an old `authorized` key is present. One-operator project; the key was removed
   before the current branch. Delete, keep one README sentence.
4. **`FACTORY_PILOT_*` fallbacks**: `application.yaml:145-158` (nested `${FACTORY_TASKS_X:${FACTORY_PILOT_X:}}`)
   and `docker-compose.yml:147-152`. The rename landed in `7e50d99` today. A stale `.env` then fails
   loudly instead of silently.
5. **Legacy report-path requirements**: `DevRuntimeProperties.MAVEN_REPORT_PATH` (`:20`) and the
   `validLegacyPath` branch (`:67-75`) accept `target/surefire-reports/TEST-x.xml` paths next to the suite
   names `surefire` / `failsafe`; `config/developer-repositories.yaml:48-50` uses suite names only, and
   `verification/SurefireEvidence.satisfies` (`:38`) carries the matching path branch.
6. **Historical constructors**: `VerificationReceipt` 18-arg constructor (`verification/VerificationReceipt.java:18-27`,
   "historical Surefire-only receipts"; main code uses the full form at `CandidateVerifier:118-125`, so it is
   test-only), `TaskExecutionContext` 6-arg (`tasks/TaskExecutionContext.java:25-29`), `CodingRequest` 9-arg
   (`runtime/CodingRequest.java:18-24`), `DevRuntimeProperties` 4-arg (`:24-27`, main 0 / test 7 files),
   `DevFactoryProperties.Repository` 6-arg (`:52-60`, main 0 / test 10 files), `Processes.Streamed` 4-arg.
   Tests pass the full form; the suppressions for `CodingRequest`, `Coding`, `VerificationReceipt`,
   `Repository` go with them.
7. **Pre-0.9.0 history paths in the UI**: `DeveloperExecutionView.LEGACY_PHASES` (`:47`, used at `:341` for
   flow version `0.6.*`), `ExecutionUiController.populateLegacyUsage` (`:219`, taken when the flow version
   is not `0.9.0`, `:200-203`), `StepUsageSummary.estimate/incomplete`, `DeveloperCostEstimator` (75 lines),
   the `estimatedCost` / `usageIncomplete` branches of `templates/execution.html` (`:49, 115, 129-135,
   252-263`). About 150 lines. Operator decision: executions older than 0.9.0 lose their cost line
   (tokens stay). See section 10.
8. **`DockerWorkloads.MavenCache.NONE`** (`runtime/DockerWorkloads.java:182`): no reference.

Estimated delta: about -300 main lines without item 7, about -450 with it; plus README and compose lines.
Risk: low; each item is a config shape with no shipped producer. Tests that construct the legacy forms
move to the full forms.

## 3. Test-only constructors and overloads in production code

- Ten classes carry a second constructor that only delegates with a null object:
  `IntakeWorker:55-66`, `IntakeResolveWorker:45-54`, `DecisionGateWorker:36-45`,
  `CodingDecisionGateWorker:36-45`, `DevelopContinuationWorker:31-39`, `VerifyWorker:26-33`,
  `DeliveryWorker` (9 and 10 parameters, `:57-91`), `FeedbackWorker` (13 and 14 parameters, `:49-96`),
  `ClaudeCodeCodingRuntime:27-34`, `ClaudeCodeProtocol:50-60`. The short form exists for tests only.
  With `@RequiredArgsConstructor` (house style) each class keeps one constructor and tests pass
  `TaskAttemptPolicy.none()` / `CodingTranscripts.none()` themselves.
- Overload ladders with one real implementation: `CodingRuntime.code` x3 (`runtime/CodingRuntime.java:27-39`,
  both runtimes implement all three), `Processes.run` x4 (`runtime/Processes.java:50-60`),
  `DockerWorkloads.Workload.execute` x3 (`:207-215`), `CandidateVerifier.verify` x3 public + 1 private
  (`:28-45`; callers use the deadline form at `VerifyWorker:59` and the source form at `FeedbackWorker:330`),
  `PublicationGit.run` x2 (`:18-23`).

Estimated delta: about -150 main lines, 6 suppressions. Risk: low (compile-checked).

## 4. Duplicated logic in the flow module

1. **Task guard** (the same two checks, three wordings): `InitialCodingAttempt.taskGuardProblem` (`:252-267`)
   with `stageTimeoutSeconds` (`:269-274`), `VerifyWorker.taskStop` (`:88-107`), `DeliveryWorker.taskStop`
   (`:286-305`) with `stageTimeoutSeconds` (`:307-312`), `CandidateVerifier.stageTimeoutSeconds` (`:136-140`).
   Each checks `ownsActiveSlot` (stop `NEEDS_INSPECTION`) and `deadline.expired` (stop `DEADLINE_EXCEEDED`),
   then `recordStop`. Five more workers inline `taskPolicy.find(...).isPresent()` + `recordStop`
   (`IntakeWorker:149`, `IntakeResolveWorker:72`, `DecisionGateWorker:58`, `CodingDecisionGateWorker:53`,
   `DevelopContinuationWorker:81`). One `TaskGuard` (or default methods on `TaskAttemptPolicy`):
   `Optional<Stop> stop(executionId, stepId, stage)` and `int capTimeout(executionId, seconds)`.
2. **`Stop(String state, String reason)`** is declared twice (`InitialCodingAttempt:399`, `VerifyWorker:123`)
   and as a bare String in `DeliveryWorker`.
3. **Four git wrappers**: `PublicationGit.run` (`-c core.hooksPath=/dev/null -c credential.helper=`),
   `CandidateFreezer.git` (`:110`, `-c core.autocrlf=false`, 180 s), `FeedbackGit.git/command/authentication`
   (`:109-128`, re-wraps `PublicationGit`), `GitBaseRefResolver.run` (`:48`). One `Git` helper with
   environment and timeout.
4. **`DecisionGateWorker` and `CodingDecisionGateWorker`** are the same 60-line shape (parse, required?,
   task run?, recordStop, `gate.open`) parameterised by the gate spec and the predicate.
5. **`FeedbackState`**: 25 hand-written accessors (`feedback/FeedbackState.java:73-179`) over package-private
   fields; Lombok `@Getter` on the class removes about 100 lines.
6. **Token usage** is a `Map<String, Object>` with string keys produced by `ClaudeCodeProtocol.addUsage/metrics`
   (`:349-383`) and `PiUsage`, extended by `InitialCodingAttempt.withFactoryTokenMetrics` (`:358-367`), and
   re-parsed by `StepUsageSummary.reportedUsage` (`:90-104`), `AttemptCharge.calculate` (`:40-58`),
   `DeveloperTranscriptView.resultFacts` (`:97-102`), `StepUsageSummary.codingTokenTotals` (`:132-151`),
   `AttemptUsageSummary.breakdownCounts` (`:189-195`), `FeedbackUi.populateUsage` (`:57-66`). One
   `TokenUsage` record (input, cacheRead, cacheWrite5m, cacheWrite1h, output, complete) serialised with the
   same JSON names replaces six parsers; `AttemptUsageSummary.Totals`, `ChargeSum`, `TaskUsage`,
   `StepUsageSummary.ReportedUsage` and `DeveloperCostEstimator.Usage` all model "count per category".
7. **SHA-256 hex** helper in four in-scope places: `app/tasks/Digests:17-24`, `candidate/Candidate:23`,
   `decision/CodingDecisionArtifacts:90`, `decision/DecisionArtifacts:128`.

Estimated delta: about -250 main lines. Risk: low to medium; item 1 touches stop wording that
`TaskOutcomeService` and tests read, so wording stays identical.

## 5. Bean wiring

`DevFactoryConfiguration` (357 lines, 14 `@Bean` methods) repeats `ObjectProvider<X>.getIfAvailable(X::none)`
ten times for four optional hooks (`TaskAttemptPolicy` x7, `AttemptPricing` x2, `CodingTranscripts` x2,
`SelectedRepositoryPreflight` x1; `:131-134, 145, 154, 169, 199, 236, 244, 254, 354`). `factory-app` already
component-scans `org.folio.factory` (`FactoryApplication.java:13`), which is how this `@Configuration` is
found. Two options, from smaller to larger:

- Declare the four null-object beans once, each `@ConditionalOnMissingBean`, and inject the interfaces
  directly. The bean methods lose the `ObjectProvider` noise; three `ParameterNumber` suppressions go.
- Make the plain workers `@Component` + `@RequiredArgsConstructor`, keep explicit beans only where there is
  logic (`devCodingRuntime` switch, `feedbackPrExecutor`, the conditional `feedbackPrMonitor`,
  `feedbackInitializer`). The class shrinks to about 100 lines. This does not break the plugin rule in
  AGENTS.md: the descriptor still names worker ids and core still resolves them by id.

Constructors with 10 to 14 dependencies (`FeedbackWorker` 14, `InitialCodingAttempt` 11, `DeliveryWorker` 10,
`DeveloperPreflight` 10, `TaskUiController` 11, `TaskOutcomeService` 8) are the remaining suppressions;
AGENTS.md's own rule applies: parameters that travel together become a record (for example
`DevFlowSettings(repositories, runtime, delivery)` and `CodingServices(coding, docker, freezer)`).

Estimated delta: about -150 to -250 lines. Risk: low (Spring context tests in factory-app fail fast).

## 6. The `tasks` package (factory-app)

From the package audit; the sample claims below were re-checked by hand.

1. `TaskAdmissionService` (327) and `TaskRetryService` (269) are one job: `reserve` (`:254-265`) and
   `saveRetryAttempt` (`:158-169`) save the same six fields; revalidation, conflict handling and Jira
   re-fetch are restated (`selectionRefusal:232-252` vs `retryProblem:171-183`; `admitSelection:200-211`,
   `admitByKey:170-177` vs `reserveAndDispatch:102-111`; `fetch:143-159` vs `freshIssue:115-125`). Keep
   `historyProblem` (`TaskRetryService:186-208`) as the only retry-specific rule. About -140 lines.
2. `TaskRecoveryService` (58) duplicates `TaskDispatcher.dispatchNextOrThrow` (`:71-77`), which already
   refreshes the active attempt; one scheduled class with an `ApplicationReadyEvent` hook. About -45.
3. `TaskInstallation.initial()` and its four `orElseGet` fallbacks (`TaskExecutionLauncher:107`,
   `TaskDiscoveryService:186, 208`, `TaskUiController:334`) never run: `V12__pilot_my_tasks.sql:29` seeds row 1.
4. `TaskOutcomeService.classify` (`:199-200`) repeats what `deliveredOutcome` (`:138-153`) already decided;
   `AttemptOutcome.State.PROCESSING_PAUSED` is never produced; `AttemptOutcome.stage/publicationIdentity/
   workloadInactive` are never read; `markNeedsInspection` always gets `cause == null` (`:301, 313-316`).
5. Test-only API: `TaskRepository.countByJiraInstance`, `TaskAdmissionService.runByKey(String, String)`
   (`:119-121`), the short `TaskCommands` constructors (`:27-37`), `TaskExecutionProperties(Duration)`,
   `TaskRecoveryService.reconcileActive` (public). Unread fields: `TaskView.eligibility, routingReason,
   updatedAt, jiraCreatedAt`, entry `executionStatus, deadlineAt`; `TaskUsage.rateSources`;
   `TaskRunSettings.Snapshot.profileLabel/model/endpointOrigin`; `TaskExecutionContext.inputFingerprint/
   destinationFingerprint/admissionKind` (no reader in the flow module).
6. `TaskPublicationIntent`: `base_branch, base_sha, tree_sha, patch_sha256` are written
   (`PersistedTaskAttemptPolicy:167-170`) and never read; main reads head branch and repository only
   (`TaskOutcomeService:295-296`); the index from `V7:22` serves no query. The reconciliation its javadoc
   promises has no implementation. Operator decision: implement or drop the columns (new migration).
7. Strings where the enums already exist: `TaskView.AttemptHistoryEntry.kind/admittedVia/state` (`TaskQuery:143, 203`)
   drive string switches in `TaskLabels:47-57, 83-92` and `TaskRows:124, 135, 141, 164, 174-179, 243-247`;
   `TaskRetryService.RetryResult.state` is a String compared in `TaskUiController.isLive` (`:301-303`),
   which restates `TaskAttempt.State.LIVE_STATES`; three switches in `TaskLabels` key on display labels
   ("Working", "Queued"); stage and reason are stored as `"stage: reason"` text
   (`PersistedTaskAttemptPolicy:125-128`) and parsed back (`TaskOutcomeService.stageOf:345-353`,
   `TaskLabels:28-29, 157-173`); the repository key is embedded in `routing_reason` with a prefix
   (`TaskRoutingReason:14-27`) and parsed in four places.
8. Repeated helpers: `approved_snapshot` JSON to Map (`PersistedTaskAttemptPolicy:78-85` and
   `TaskPublicationGuard:86-93`); reason cap to 500 in three classes with `MAX_REASON_LENGTH` declared
   twice; `FLOW_ID = "dev-factory"` in `TaskExecutionLauncher:37`, `TaskUiController:55`,
   `DeveloperPreflight:44`; slot release in `releaseLocked:247-252` and `markStopped:277-281`.
9. `TaskQuery` runs the full attempt, usage and audit projection for Available rows that by query
   definition have no attempts, loads the installation once per task instead of once per request, and
   `TaskUiController.taskLabel` (`:325-331`) and `ExecutionUiController.taskRun` (`:151-155`) call the full
   projection to obtain a display key.

Estimated delta: about -400 lines. Risk: medium for item 1 (`TaskRetryIntegrationTest` pins messages),
low elsewhere.

## 7. Presentation and cost cluster (factory-app)

1. **Dead CSS**: 21 `developer-*` / `feedback-totals` classes with 204 rule lines (`static/css/app.css:594-811,
   1341-1373`) are referenced by no template, JS or Java; the 10 task-state `badge-status-*` selectors
   (`:254-273`) are unreachable because task pages use `state-pill`; `.badge-status-UNSUPPORTED` is defined
   twice (`:242, 264`). About -220 lines, no risk.
2. **Work computed and discarded on the Developer run page**: `model.addAttribute("events", ...)`
   (pretty-printed JSON of every audit event, `ExecutionUiController:214-215`) renders only when
   `developer == null` (`execution.html:369-373`); `StepUsageSummary.from` (`:120`) feeds step `tokens` the
   dev grid never reads; `progressSteps` (`:164`) exists for the stepper that renders only without a
   developer view; `artifactGroups` carry `content/createdBy/label/panelId/latest` the dev page ignores
   (`:289-300`). `productStatus` (`DeveloperExecutionView:60-111`) and `build` (`:113-131`) both parse all
   seven artifacts per request (`ExecutionUiController:123, 161`); `productStatus` also runs per row of
   `/executions` with all artifact content loaded (`:226-228`). The audit event list is walked five times
   per page load. Guarding these with the branch the template already has removes the largest per-load cost.
3. **Dead members**: `AttemptUsageSummary.build` x2 (`:35-41`), summary keys `tokens` / `provisional`;
   `DeveloperExecutionView.technicalStepDuration` (`:327-330`), view keys `outcome`, `elapsed`, `elapsedStart`,
   phase row keys `type/attempts/tokens`; `FeedbackUi.duration` (`:189-191`), round card key `round`;
   `DeveloperPricingProperties.Rate` 8-arg constructor; `CodingTranscriptStore.Entry.seq` unread by
   presentation. About -45 lines and one suppression.
4. **`DeveloperPreflight`** (612 lines, 10 dependencies): `checkEfforts` (`:561-567`) can never fail
   (profiles are validated at startup in `RunProfiles:278-282`); `checkPricing` (`:250-258`) and
   `checkRateCard` (`:553-559`) are the same check; `endpoint(String)` (`:286-298`) is the third copy of
   `RunProfiles.origin` (`:290-299`) and `ClaudeCode.requireValidEndpoint` (`DevRuntimeProperties:178-191`);
   `safeAuthentication` (`:468-476`) restates `requireConfigured`; the delivery base mismatch check
   (`:427-429`) is already rejected at startup for catalog entries. `DeveloperPreflightProbe` is never
   cached: one `check()` runs `docker info`, image inspects, TCP connects and up to three GitHub GETs per
   repository, on every GET of `/developer/configuration`, on every trigger and again at selection; push
   permission is probed up to three times per task run. About -30 lines plus a short-lived cache for
   the probe results.
5. **Pricing file gap**: `config/developer-pricing.yaml` is imported only through `docker-compose.yml:154`;
   `application.yaml:3-7` imports repositories and run profiles but not pricing, so `mvn spring-boot:run`
   prices nothing. One line: `optional:file:./config/developer-pricing.yaml`.
6. **Row shapes and label tables**: 1 public record row (`ExecutionRow`) against about 23 `Map` row shapes
   and about 21 label/tone tables across `DeveloperExecutionView`, `DeveloperRuntimeActivity`,
   `DeveloperTranscriptView`, `FeedbackUi`, `TaskRows`, `TaskLabels`, `UiFormat`. Restated relations: step
   id to phase (`DeveloperExecutionView.CURRENT_PHASES:52-57` and `TaskLabels.stageLabel:139-151`, different
   words), failed-step tone (`executionHistory:317` and `UiFormat.eventTone:130-137`), category labels
   (`TaskLabels.categoryLabel:215-223`, hard-coded `<dt>` at `execution.html:117-120`), duration formatting
   (`UiFormat.duration`, `TaskLabels.minutes`, `FeedbackUi.duration`), `parse`/`milliseconds` helpers
   (`DeveloperRuntimeActivity:108-114, 214-216` vs `DeveloperExecutionView:509-515`,
   `DeveloperTranscriptView:106-108`). AGENTS.md asks for records; convert when a page is touched.
7. `ExecutionUiController` (345 lines, suppressed constructor): the transcript route (`:138-155`) and its
   three dependencies belong in their own controller.

Estimated delta: about -450 lines. Risk: low; `UiRenderSmokeTest` covers page text.

## 8. Types instead of strings (flow module)

Persisted names must not change; enums serialised by name keep the JSON exactly as it is today.

- Stop and result states: `"DEVELOPMENT_FAILED"`, `"BLOCKED_ENVIRONMENT"`, `"PAID_START_CONSUMED"`,
  `"NEEDS_DECISION"`, `"REPOSITORY_MISMATCH"`, `"VERIFIED"`, `"VERIFICATION_FAILED"`, `"NOT_RUN"`,
  `"DELIVERED"`, `"DELIVERY_BLOCKED"`, plus `TaskStops.DEADLINE_STATE` / `INSPECTION_STATE`, are string
  literals spread over `InitialCodingAttempt`, `VerifyWorker` (`:49, 62, 117`), `DeliveryWorker`,
  `DevelopContinuationWorker` and read back by `TaskOutcomeService` and the views.
- `IntakeWorker.MAPPED / BLOCKED / UNSUPPORTED` (`:43-45`) and the reason codes (`INVALID_ISSUE_KEY`,
  `NO_REPOSITORY_MAPPING`, ...) travel as frontmatter strings that `IntakeResolveWorker` re-reads through
  `JsonNode.path(...)` (`:67-80`); a typed intake record rendered through the codec removes that plumbing.
- `VerificationReceipt.result` is `"PASS"` / `"FAIL"` (`:40-43, 53`), compared in five places.
- `FeedbackState.failureKind` is a String compared against `Set.of("CODING", "CANDIDATE", ...)` in
  `FeedbackActions.addFailureActions` (`:280-292`).
- `VerifyWorker.execute` builds a 15-key `Map<String, Object>` outcome (`:61-82`); a record with the same
  JSON names.

Estimated delta: roughly neutral in lines, fewer string comparisons. Risk: medium; fixtures with today's
JSON must still deserialise, so the change ships with round-trip tests on existing artifact samples.

## 9. Tests: wall-clock and duplication

Reported Surefire time (current classes): factory-app 219.5 s (55 classes), factory-core 67.2 s (25),
factory-flow-dev-factory 32.8 s (29, 18 skipped), others under 1 s. Total 321 s, excluding JVM starts.

| Cost | Where | Evidence |
|---|---|---|
| ~104 s of 196 s in the 20 Spring classes of factory-app is context startup (~5.2 s each) | all `@SpringBootTest` classes | class time minus method time; `FactoryApplicationTests` 4.6 s for a 0.23 s test |
| ~48 s of 67 s in factory-core is startup (~3.45 s each) | 14 Spring classes | same |
| 35 per-class PostgreSQL containers | every `@Container static PostgreSQLContainer` | no base class, no singleton, `withReuse` absent |
| `reuseForks=false` in both poms | `factory-core/pom.xml:78-80`, `factory-app/pom.xml:165-168` | comments: leaked engine threads / keep-alive connections racing WireMock |
| `@DirtiesContext` on all 20 app classes | dead weight under `reuseForks=false` | 19 distinct (webEnv, properties, mocks) triples, so no two classes could share a context anyway |
| 31 s waiting for the PR monitor | `TaskEndToEndTest:363` awaits up to 90 s for `FeedbackPrMonitor`, whose delay is hard-coded (`FeedbackPrMonitor:40`, `initialDelay = 30000`) | 14 % of factory-app |
| 40 s | `PrFeedbackIntegrationTest`: git init and push per test, one 12-round test | 18 % of factory-app |
| 11.6 s + 4.3 s | `ProcessesTest` (`Thread.sleep(6000)` at `:83`, real `sleep 20` processes), `DevelopReadinessTest` (`Thread.sleep(4000)` at `:431`) | 48 % of dev-factory |
| 5.9 s | `CandidateVerifierTest` rebuilds a git repository in `@BeforeEach` 26 times | |

Duplication: the dev-flow harness (`git`, `createRepository`, `createGitRoot`, `properties`, `stubIssue`,
`awaitPendingReview`, `stopWireMock`) is copied into `TaskEndToEndTest`, `TaskAutonomousFlowIntegrationTest`
and `DeveloperIntakeDecisionTest` (100 to 130 lines each); the `Task` / `TaskAttempt` fixture (`new Task(...)`,
`admit`, `resetTaskState`, `forceState`) is copied into nine `Task*` classes and `UiRenderSmokeTest`
(250 to 300 lines); the six-line Spring header appears 20 times; four classes own a static `WireMockServer`.
About 11 behaviours are asserted both in a standalone test and in `UiRenderSmokeTest` (by design per
AGENTS.md, but worth knowing when one changes).

Plan, in order of payoff: (1) find and fix the thread and connection leaks that `reuseForks=false` papers
over (stop the engine pollers and the PR monitor executor in test contexts, close WireMock per class),
then drop the setting; (2) one shared PostgreSQL container behind a small holder used by every Spring test;
(3) one `@FactoryIntegrationTest` meta-annotation and a test `application.yaml` holding the properties that
are the same in most classes, so the 20 contexts collapse to a handful; (4) make the PR monitor delay a
`fixedDelayString` property that tests set low; (5) a `@BeforeAll` repository in `PrFeedbackIntegrationTest`
and `CandidateVerifierTest`; (6) replace the two real sleeps with an injected clock or shorter waits;
(7) one `TaskFixture` and one `DevFlowHarness` test helper. Expected: factory-app roughly halves, core
drops to about a third, dev-factory to about half. Measure before and after with the Surefire reports.

## 10. Operator decisions

1. **Pi runtime.** `PiProtocol` 246, `PiCodingRuntime` 199, `PiProgress` 171, `PiUsage` 119 (735 main
   lines), `PiCodingRuntimeTest` 485 + `PiCodingHttpContractTest` 74, `docker/Dockerfile`,
   `compose.runtime-pi.yml`, the `Pi` record and legacy fields in `DevRuntimeProperties`, 18 env
   placeholders, 17 compose lines, 22 README lines, branches in `DeveloperPreflight` (5), `TaskRunSettings`,
   `DeveloperRuntimeActivity`, `DeveloperCostEstimator`, `TaskUiController`, and two pricing entries whose
   models have no run profile. The default runtime has been `claude-code` since 2026-10-14; run profiles,
   effort and the run form exist for Claude Code only. Removing Pi is a feature decision; keeping it means
   keeping a second configuration tree. With Pi gone, `CodingRuntime` stays as the test seam but loses
   its overload ladder.
2. **REST read API.** `/api/executions`, `/api/flows`, `/api/hitl/reviews`, `/api/prompts`, `/api/workers`,
   `/api/audit`, `/api/executions/{id}/pr-feedback*` have no template or JS consumer (only
   `/api/dashboard/stats` is called, from `charts.js:367`); `/api/triggers/manual`, `/api/status` and the
   webhooks are documented product surface and stay. About 460 main and 800 test lines. Default: keep.
3. **Pre-0.9.0 history** (section 2, item 7) and the unread `task_publication_intent` columns
   (section 6, item 6): keep if old runs or the promised reconciliation still matter.

## 11. Housekeeping

- Comment tags `OPS-2`, `PERF-1`, `REL-1`, `OBS-1`, `COST-1`, `DATA-1`, `DEP-1` in `application.yaml` and
  `.github/workflows/*.yml` point to no tracker; replace with the plain reason or delete.
- A stale agent worktree `.claude/worktrees/quizzical-banach-c345f0` (detached, gitignored) and 22 stale
  `Pilot*` / `PixelDumpTest` Surefire reports in `factory-app/target` from before the rename.
- README (697 lines) and `doc/extending-the-factory.md` (1,220 lines) document the legacy env vars; update
  the README table and the "Coding runtime profiles" section after sections 2 and 10 land.

## 12. Guardrails for whoever does the work

From AGENTS.md and the code: secrets never reach the database, artifacts, logs, argv or pages (everything
from the CLI goes through `CodingRuntime.redact` / `RunProfiles.redact`); hidden reasoning never reaches a
transcript or page (keep the `HIDDEN_*_SENTINEL` tests and add cases for new output); do not change the step
outputs in `flows/dev-factory.yaml` or `FeedbackArtifacts.VERSION`; migrations are append-only; artifacts
and audit rows are immutable; never add a Checkstyle suppression, delete them as methods shrink; persisted
JSON (artifacts, `task_attempt` columns, `dev_pr_state.json`) must stay readable by the new code; `mvn verify`
is the acceptance test and needs Docker.
