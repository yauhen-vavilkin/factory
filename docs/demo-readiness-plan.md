# Demo readiness plan

Written on 2026-11-15 after an audit of the code, the live instance and the demo console, and
revised after two independent reviews of the plan. This file is the task list for the sessions
that finish the project for a demo. Mark finished work in "Progress" at the end. When the plan
is done, move what is left to `docs/open-questions.md` and delete this file.

## Goal

Show the Developer Flow in a demo without a surprise. No new features. The existing features
must say the truth in every state a person can reach: each stop names its cause and the next
step, and the console looks like a finished product.

The owner cannot test by hand, and a real model run takes 20 to 60 minutes. So states are
proved by scenario tests with a scripted agent and by looking at the demo console. Real runs
are only a smoke check that the owner starts.

There is one working day. The stages are in the order of their value for the demo: whatever is
finished when the time ends must be safe to show. Work that can break the path that works
today was cut or narrowed to wording.

## Acceptance

1. `mvn verify` and `node --test factory-app/src/test/js/*.test.js` pass.
2. Every scenario of tier A in "Scenario list" passes. A scenario drives the real engine,
   workers, task services and pages; only the agent, Docker, GitHub and Jira are faked. No
   scenario writes a stop reason, an attempt state or a result artifact by hand.
3. Every stop of an attempt shows its cause and the next step in plain words on My tasks, the
   task page and the run page. No internal wording, no ids, and no "The run stopped before a
   step could say why" for a cause Factory knows.
4. The demo console has a task for every state this plan fixes, with the text the fixed code
   writes, and a cost on every finished attempt that spent money. Each touched state was
   opened and looked at in the light theme at 1280 px and at 390 px.
5. The Dashboard shows what the tasks of the period cost and how much of the money ended in a
   pull request.
6. The owner ran one real task to a pull request on the final build (stage 5).

## Evidence (what the audit found)

- Build: `mvn verify` green. Live instance: 401 pages answer 200, no ERROR in two days of
  logs. Demo console: 218 pages answer 200.
- Live history: 22 attempts, 8 pull requests. Of $18.99 spent, $15.40 went to attempts without
  a pull request; $11.40 of that died at the 30 minute coding limit (4 attempts).
- One live attempt was lost to a step replay and showed "Paid coding was already started for
  task attempt df739401-…; a replayed or retried engine step cannot start a second initial
  coding invocation". Another showed "Starting build failed before model spend." with no next
  step.
- No test goes from Run to the text of My tasks or the task page through the real workers.
  `TaskEndToEndTest` is the closest (real workers, mocked `CodingRuntime`, no HTML).
  `PrFeedbackIntegrationTest` does read the run page for PR feedback. `TaskLifecycleIntegrationTest`,
  `TaskViewTest`, `UiRenderSmokeTest` and `DemoConsole` write reasons and artifacts by hand, so
  they pass while the real code shows something else. Example: a failed verification is seeded
  as "Verification failed: 2 tests in error", but `VerifyWorker` writes no reason and the
  person would read "The run stopped before a step could say why".
- The Developer Flow pages (My tasks, task page, run page) look calm and consistent. The
  Dashboard is the weak page: twelve tiles, a doughnut that repeats the tiles, two charts that
  show one bar each, and no money. It is a framework page, so it only gets one added block.

## Rules for every stage

- Read `AGENTS.md` first. Its product postulates, page rules, code style, Developer Flow
  invariants and "Done means seen" apply to every change here.
- No new feature, no new package or layer, no redesign. A fix is the smallest change that
  makes its test pass. If an item turns out bigger than its note says, stop, write why under
  "Progress" and go to the next item.
- Scope is the Developer Flow only (owner's decision, 2026-11-15). The engine and the framework
  stay as they are: nothing in `factory-core`, and no change to what the framework pages show
  today (the existing elements of the Dashboard, Executions, Reviews, Artifacts, Audit, Status,
  Workers, Prompts, Flows, the error page). The only addition to a framework page is the
  Developer Flow spending block of stage 3.
- Do not change the step outputs in `flows/dev-factory.yaml`, `FeedbackArtifacts.VERSION`, the
  order of `claimPaidStart`, or the retry rules of `DeliveryWorker`.
- Test first: write the test that states the required behaviour, see it fail for the reason
  the item names, then fix. Page tests assert visible text and button labels, never markup.
- A demo seed never types a reason: it calls the production code that builds the reason, or
  uses its constant, so the demo cannot drift from the code again.
- One session at a time edits console pages (templates, `static/`, page row classes).
- Work in the main checkout on `m1-sprint-pilot`. Never `mvn install`. Do not run two Maven
  builds at once in one checkout; the demo console is the exception (it is a Maven process that
  keeps running in the background and is restarted after a Java change).
- Before stage 0: `git tag demo-baseline` on the current commit, so the owner can go back.
- Gate per item: the fast test loop of the touched module, then one local commit for the item
  (one fix can then be reverted alone). Gate per stage: `mvn verify`; an independent review of
  the stage diff by a fresh agent that gets this file and the diff, not the author's reasoning
  (not needed for stage 0); for a stage that changes a page, a look at each touched state in
  `DemoConsole` with a screenshot. If the browser tool is not available, use headless Chrome
  (`--headless --screenshot`) against `localhost:8090`. No push.
- A review finding is checked against the code before it is applied; a wrong one is rejected
  with one line. At most two review rounds per stage.
- The owner's live instance runs on port 8080 with PostgreSQL on 5432. Do not send it requests,
  do not restart it and do not read `.env`.
- Report per stage: what changed, which tests prove it, which states were looked at, what was
  not verified. Plain English, B2 level, as in the existing commits.

## Stage 0. Baseline (about 45 minutes)

0.1 Commit the two edits already in the working tree (the owner's work): `verification-plan:
java21-unit` for MODROLESKC in `config/developer-repositories.yaml` (build containers have no
Docker, so the old plan failed every MODROLESKC task at the starting build) and the "Could not
read" wording in `CodingProtocol`.

0.2 Raise the limits so a normal task fits: time per step 3600 s, max turns 400, whole attempt
120 m, lease timeout 9000 s (the comment at `application.yaml:95` asks for more than two times
the step limit). Places: `application.yaml` lines 96, 155, 170, 189 and the comments at 153 and
187 to 188; `docker-compose.yml` 96, 106, 108; `.env.example` 30 and 33; the defaults and
comments in `DevRuntimeProperties` (19, 21, 101, 112) and `TaskExecutionProperties` (18 and its
Javadoc); the assertions in `ComposeConfigurationTest`, `CodingRuntimeConfigurationTest` and
`DevRuntimePropertiesTest`; README lines 258, 436 to 437, 615, 618, 637, 643. Known side
effect, accepted: a run that hangs holds the single slot for up to 60 minutes per step.

## Stage 1. Scenario tests with a scripted agent (cap: 90 minutes)

Build on what exists; do not write a new harness.

- Move the stubs of `TaskEndToEndTest` (lines 596 to 657: starting build, coding export,
  verification with a surefire fixture, the mocked `CodingRuntime` that returns a
  `CodingOutcome`) and its WireMock GitHub and Jira stubs into `DevFlowHarness`, so a test can
  say "the agent does X, the checks do Y". Keep `TaskEndToEndTest` green on the moved code.
- Add the synchronous drive of `PrFeedbackIntegrationTest` (lines 144 to 153:
  `claims.claim(100).forEach(engine::advance)` with the poller off), plus direct calls of
  `dispatchNext()` and `TaskOutcomeService.refresh()`, so a scenario is deterministic.
- One new class, one Spring context: `@FactoryIntegrationTest`, profile `task-execution`.
  It starts runs by HTTP as a person does (`POST /tasks/run-selected`, `POST /tasks/{id}/retry`,
  the same route with the form field `answer` for an answer, mark stopped). The agent is the
  mocked `CodingRuntime` returning a `CodingOutcome` with the real failure codes.
- Every scenario ends by reading `/flows/dev-factory`, `/tasks/{id}` and `/executions/{id}` and
  asserts: the state chip, whose turn it is, the cause, the next action, the step labels of the
  run page (a failed check must not read "Verify changes Passed"), and that no page holds
  "could say why", "engine execution ended", a UUID inside a reason, or a Java class name.
- After `publish` the flow runs `pr-follow-up`, which reads the pull request, its issue
  comments, reviews and review comments from GitHub; a delivered scenario needs these stubs.
- For PR feedback add page text assertions to the existing tests of
  `PrFeedbackIntegrationTest`; do not rebuild them.

Stage 1 commits the moved stubs and the scenarios that pass on today's code. A scenario that
fails because of a defect of stage 2 is added there, together with its fix.

### Scenario list

Tier A (stage 1 and stage 2):
1. Agent edits files, checks pass, the report has open points: draft pull request.
2. Agent finishes without changing a file.
3. Agent asks a question; the answer starts attempt 2; attempt 2 opens a pull request.
4. Coding time limit (the failure code the runtime returns).
5. The model endpoint is down or refuses the key (provider failure): the likeliest failure in
   a demo.
6. Starting build fails before any model spend.
7. Verification fails on tests (2 of 300). 8. Verification fails to compile.
9. Verification cannot run (timeout or environment).
10. A step throws three times and the engine gives up (GitHub answers 429 three times at
    publish). Use a test helper that sets `next_run_at` to now between tries; the retry backoff
    is 30, 120 and 300 s.
11. Run end not confirmed (needs inspection) on a delivered run: the pull request is shown.
12. Task that cannot start ("Could not start"), and Run while Factory's preflight fails (the
    queue notice).
13. Retry after a failed state starts a new attempt; a second POST of Run, Retry and Answer
    gives one attempt and no error page.

Unit level, next to the existing tests (not page scenarios): the replay of `implement` after
the paid start (`DevelopReadinessTest`, `AttemptFailureTest`), question parsing
(`CodingProtocolTest`), draft reasons (`PullRequestRendererTest`).

Tier B (only after "If time is left"): whole-attempt deadline, turn limit, output limit, a
ready pull request after a clean report, two queued tasks, intake outcomes (issue not found,
repository choice needed), publication guard refusal, fork behind its base, PR feedback clean-up
blocked and refresh required, a manual push before a round publishes, runs started without a
task (`POST /flows/dev-factory/trigger`).

## Stage 2. What a person reads when a run stops (about 2 hours)

Each item: defect, required behaviour, test, and a demo seed that calls the same code.

2.1 Failed verification has no reason (scenarios 7, 8, 9). `VerifyWorker.outcome` (57 to 80)
writes no `reason`; `TaskOutcomeService.classify` (181 to 199) falls back to `RUN_ENDED +
status`; `AttemptFailure` (20, 124) shows "The run stopped before a step could say why".
Required on the pages: "Checks failed: 2 of 300 tests failed.", "Checks failed: the code does
not compile.", "Checks could not run: <cause>.", each with "No pull request was created" and
the retry form. Build the stored reason in `classify` from `failureCount`, `errorCount`,
`testCount` and `failureKind` of the verification result. Format trap: `stageOf` (319 to 325)
cuts the stored text at the first ": " and `AttemptFailure` (60) prints "<Stage> failed:
<cause>", so store `VERIFICATION_FAILED: 2 of 300 tests failed` and keep the wording in
`AttemptFailure` and `TaskLabels`. Do not change the worker's outputs. Replace the hand-written
`{"state":"VERIFICATION_FAILED","reason":…}` in `TaskLifecycleIntegrationTest` (281) and
`TaskViewTest` (207) by the shape the worker writes.

2.2 A cause without a next step (scenario 6). "Starting build failed before model spend." must
say that the build of the base branch failed before the agent started, that nothing was spent,
and to open the run for the build output and fix the base branch or the check command in
`config/developer-repositories.yaml`.

2.3 The replay text (wording only). `InitialCodingAttempt` 171 to 175 stops a replayed
`implement` step with internal text and the attempt id. Change the text to "Factory restarted
or repeated this step while the agent was coding, so the work of this attempt is lost. Start a
new attempt." and map the old stored text to the same words in `AttemptFailure`. Do not move
`claimPaidStart` and do not add a check before the build: a network failure of the starting
build is replayed on purpose (lines 73 and 157).

2.4 The engine gave up (scenario 10). An execution that ends `FAILED_ESCALATED` after three
tries is classified as "engine execution ended in status FAILED_ESCALATED". Required: "Factory
could not finish <step in words> after 3 tries: <the stored error, redacted, one line>. Start
a new attempt." Take the step label from `UiFormat` and the message from
`execution.getErrorMessage()`.

2.5 The whole-attempt deadline reason ("…queue and human-review waiting are excluded from the
budget") says in plain words that the attempt used its whole time, with the "Limits in
Configuration" link that the time limit reason already has.

2.6 Needs inspection hides the pull request (scenario 11). `AttemptPullRequests.of` line 37
returns none for a state that is not in flight; use `isLive()`.

2.7 One name for one state: a Developer Flow run that stopped with a question is "Asked a
question" in the run list (`RunState.ASKED`) and "Needs your answer" on the task pages. Use the
task pages' words in the label that `RunState` gives; change nothing else on `/executions`.

2.8 Delivery refusals (wording only). "GitHub blocked pull request creation (HTTP 403)" and
"GitHub rejected delivery configuration or destination (HTTP …)" say what to do: check the
token's rights, the repository in `config/developer-repositories.yaml`, or set the fork
variables. Do not change which errors are retried.

Demo seeds in the same commits: failed checks (tests, compile, could not run), starting build
failed, replay, engine gave up, GitHub refused delivery. Update the Javadoc list of states in
`DemoConsole`.

When stage 2 is committed, write under "Progress": "Ready for a live run". The owner may then
rebuild and start one real task while the next stages go on.

## Stage 3. Developer Flow spending on the Dashboard (about 90 minutes)

A report about the Developer Flow's own sessions only: the attempts of its tasks. It says what
was spent in the chosen period and how much of that ended in a pull request. It is one added
block; every existing element of the Dashboard stays as it is. The owner saw a mockup of this
block on 2026-11-15.

Data, no new storage: a new public method on `TaskQuery` returns the attempts whose run started
in the period, each with its task (key, title), state, kind, `publication_identity` and its
cost, by the same path the task page uses (`TaskQuery.attemptRow`, lines 140 to 155:
`auditLog.forExecution` plus `TaskUsage.aggregate`), so the numbers equal the task page. The
join is `task_attempt.execution_id`; the `attemptId` inside audit rows is another id and must
not be used. PR feedback rounds are part of the attempt's execution, so they are in its cost.
Render on the server with row records; nothing goes into the 30 second poll. All money through
`UiFormat.money`.

The block "Developer Flow spending" sits between the tiles and the charts, as rows without a
card, and is shown only when the period has Developer Flow attempts:

1. One line of four figures: Spent · Ended in a pull request · Attempts without a pull request
   · Cost per pull request (spent divided by the tasks that have a pull request). A short note
   counts attempts that have no price ("2 attempts have no price").
2. "Cost per task": one row per task, most expensive first, at most 15 rows and one line for
   the rest: Jira key (link to the task page), title, a bar of two segments on one scale (the
   attempt that opened the pull request; attempts without one), the amount, "3 attempts", and
   the state chip the task has on My tasks. The bar is plain HTML and CSS on the existing
   tokens with a two-item legend; no chart.js. Two colours only.

Remove nothing from the Dashboard and leave `/api/dashboard/stats` and
`DashboardStatsService` as they are. The existing Dashboard assertions must pass unchanged
(`UiRenderSmokeTest` around 149 to 180 pins the tile list: do not add the figures as
`<a class="kpi">`). `DashboardUiControllerTest` builds the controller by constructor, so it
gets the new dependency. Add tests that assert the four figures and the task rows against a
seeded set with a known sum.

Demo seed: usage rows with model and rate and attempts with `run_settings`, so no finished
attempt says "cost unknown"; two models, three efforts, one task that failed twice and then
opened a pull request, one expensive task that only failed.

## Stage 4. Console clean-up (about 45 minutes)

No redesign, and only the Developer Flow pages: My tasks, the task page, the run page, the
Configuration tab. Remove only what explains the obvious or looks broken. Fixed list:

- Task page: remove the sentence "Effort is how long the model thinks at each step: higher is
  slower and costs more." under the run forms; keep "A new attempt starts from a clean checkout
  and adds to the cost."
- Check that the reasons of stage 2 and the costs of stage 3 wrap on My tasks and the task page
  at 1280 px and 390 px and do not push the chip or the buttons.

Then one read-only pass by a fresh agent over the Developer Flow pages of `DemoConsole` in
every seeded state, light theme, 1280 px and 390 px. It lists, with page, text and screenshot: text that explains a control or a link, a
label that repeats its neighbour, broken wrapping, different words for the same thing on two
pages. It changes nothing; the list goes into the stage report for the owner. Do not touch the
source labels (Jira task, Factory, Coding agent, You decide) or any layout.

## Stage 5. Final check (about 1 hour)

- `mvn verify`, the JS tests, one review of the whole diff since `demo-baseline`. The
  `HIDDEN_*_SENTINEL` tests must still pass and cover any new output.
- Walk every touched state in `DemoConsole` at both widths and write the list of states seen.
- README: one quick start (`.env.example` lacks `FACTORY_TASKS_ASSIGNEE`,
  `FACTORY_TASKS_EXECUTION_ENABLED` and the Jira URL that `.env.tasks.example` has); say how to
  start the CodeMie proxy or to pick another default model; fix the places where the README
  names controls the pages no longer have (lines 308, 371 to 375, 442, 545 to 548, 562).
- `docs/open-questions.md`: remove the items this plan closed, correct the note that says text
  after a question line is already ignored (the code fails the attempt), and add everything
  under "Recorded, not done" below plus every new finding.
- Stop and report. The live smoke is the owner's: rebuild the Compose image, run one task with
  `glm-5.3-flash` to a pull request, run one PR feedback round with a push, and compare the
  Dashboard for 30 days with the known history (about $18.99 spent, $3.58 ended in a pull
  request). It recreates the owner's container and opens pull requests in the owner's forks.

## If time is left, in this order

These touch only `factory-flow-dev-factory` and its tests (plus `PrFeedbackIntegrationTest`),
so a second agent may do them in its own worktree while stages 3 and 4 run in the main
checkout. Its first step is `git reset --hard` to the current `m1-sprint-pilot` commit; it
never runs `mvn install` and never edits a console page; its commits are cherry-picked into
the main checkout before stage 5.

A. A question with text after its line fails a paid attempt. `CodingProtocol.answer` (64 to
79) returns `invalidDecision` when the detail holds a newline. Required: parse the marker
line; accept a closing code fence and a pretty-printed JSON object; ignore text after it. Unit
cases in `CodingProtocolTest`.

B. PR feedback after failed checks. `FeedbackActions.addFailureActions` (283 to 289) offers
"Try fixing with the agent" only for CODING, CANDIDATE and CANDIDATE_COMPILE; with another
kind the person can only repeat the same checks or start a new paid round that overwrites the
saved work. Required: offer it for every verification failure kind and say the reason in words
instead of "Independent checks failed: UNKNOWN" (`FeedbackState` 304). Test with a kind that
is not CANDIDATE in `PrFeedbackIntegrationTest`.

C. Dashboard, third part: "Where attempts stopped", one row per cause with the number of
attempts and the money (Time limit, Turn limit, Asked a question, No changes, Checks failed,
Stopped by Factory or the environment, Other). The cause is a small enum computed in Java by
the checks `AttemptFailure` and `TaskStops` already make on the stored reason. Main checkout
only.

D. The patch is not checked for the model secret. At `InitialCodingAttempt` 229 to 231 and
`FeedbackCodingAttempt` 90 to 92, refuse a patch only when an added line (`+`, not `+++`)
holds a configured secret of 16 or more characters; the reason names the file. Tests for a
refusal and for a patch that merely holds a short public word such as `codemie-proxy`.

E. Draft reasons: add "None identified", "No assumptions were needed" and "All links were
readable" to the none forms of `PullRequestRenderer` (34 to 36). Unit tests only; no other
change to the parser.

F. Dashboard, fourth part: "By model and effort" as rows: model, effort, attempts, pull
requests, spent, average per attempt. Main checkout only.

G. Tier B scenarios.

## Recorded, not done

- One failed heartbeat write discards a finished run (`AgentLeaseHeartbeat.renew`, 61 to 75,
  stores any exception as a permanent failure; the task then stays "In progress" for up to two
  hours). It is in `factory-core` and changes lease ownership, so it is not touched before the
  demo.
- Framework pages, left as they are: the Dashboard has a doughnut that repeats its tiles and
  two charts that show one bar each ("Runs per flow", "Connector actions"); long variable names
  break inside a word in the connector cards of Status; the 404 page shows its error in the
  green success toast (`UiExceptionHandler`); the Retry scheduled badge has no colour
  (`UiFormat.eventTone` returns `warning`, `app.css` has no `.badge-warning`); Reviews shows
  raw `APPROVE`, `AMEND`, `REJECT`.
- `DeliveryWorker` retries only 408 and 429; a 403 rate limit after the push ends the run, and
  a 422 for a draft has no fallback.
- The replayed `implement` step runs the whole starting build before it stops.
- A second parser pass on draft reasons: nested items, "(met)", "is met", table rows.
- `DemoConsole` still seeds finished states by hand (now through production code for the
  reasons); running the scenarios at its startup needs task execution on, which would let its
  queued tasks start.
- Saving partial work at a timeout, a Stop button for a working run, resume after a restart, a
  budget stop, model per task, Pi with a model choice, types instead of strings in the flow
  module, test speed, context from other repositories.

## How to run this plan

Give a session this prompt:

    Read docs/demo-readiness-plan.md and AGENTS.md in /Users/yauhen/EBSCO-FOLIO/factory
    (branch m1-sprint-pilot) and carry the plan out stage by stage, in order, without asking
    between stages. Follow "Rules for every stage" exactly: tag demo-baseline first, test
    first, one local commit per item after the fast tests, and per stage mvn verify, an
    independent review by a fresh agent and a look at every touched state in DemoConsole.
    No push. Keep to the time notes: when a stage passes its cap, finish the item in hand,
    record the rest under "Progress" and go on. If a stage cannot pass its gate after two
    review rounds, revert the failing item, write what blocks it under "Progress" and
    continue. After stage 4 start "If time is left". Do not run the live smoke. Final
    report: per stage what changed, which tests prove it, which states you looked at, what
    is not verified.

## Progress

- [x] Stage 0. Baseline. Tag `demo-baseline` is on c595e71. 0.1: the owner's two edits and this
  plan are committed as they were. 0.2: the new limits are 3600 s per step, 400 turns, 120 m per
  attempt and a 9000 s lease. The line numbers in 0.2 were a little off: `DevRuntimeProperties`
  has its defaults at 21 and 108 and its Javadoc at 101. Also changed: `doc/runbook.md` (the
  lease default) and the page tests that read the shipped defaults (`DeveloperConfigurationPageTest`,
  `UiRenderSmokeTest`). `mvn verify` passed. Looked at in `DemoConsole` at 1280 px and 390 px: the
  limits under Configuration (1 h, 2 h, 400; MODROLESKC checks with `mvn -B -ntp test`) and the
  status card of the run in progress on its run page and its task page ("of 1 h", "The whole
  attempt may take 2 h."). A `.env` that still sets the old values keeps them. Seen, not
  changed (for stage 4): at 390 px the "Open run" button of the task page status card reaches
  past the card's right edge.
- [x] Stage 1. Scenario tests. The stubs of `TaskEndToEndTest` are in `DevFlowHarness`:
  `stubCodingRuntime` (the agent returns a `CodingOutcome`), `stubWorkloads` with
  `Workloads.startingBuildReturns`, `agentLeaves` and `checksReturn`, `stubDelivery`,
  `stubPullRequestCreation`, `stubPullRequestRead` (also answers the comment, review and review
  comment lists with `[]`), `stubSearch`, `issue`, `workspace`, `passingReports` and `visibleText`.
  New `TaskScenarioIntegrationTest` (profile `task-execution`, poller off) starts runs by HTTP,
  advances them with `drive`, settles them with `finish` (`refresh`, then `dispatchNext`) and reads
  My tasks, the task page and the run page. Passing today: scenarios 1, 2, 3, 4, 5 and 13 (a second
  Run, Retry and Answer). In all of them Factory's preflight is a mock that passes, because the real
  one refuses any GitHub API other than GitHub.com, and GitHub is WireMock in these tests. Scenario
  12 was taken out after review: it needs the real preflight to fail, and with WireMock GitHub the
  real one fails first at the GitHub.com check, so a mock had to throw a typed reason. It needs its
  own Spring context with the default GitHub API and a mocked `DeveloperPreflightProbe`; that is
  against "one class, one context", so it is left for the owner to decide. Left for stage 2, each
  probed once on this code: 6 shows
  "Starting build failed before model spend." with no next step and "cost unknown" for an attempt
  that spent nothing; 7 and 8 store "engine execution ended in status COMPLETED" and show "The run
  stopped before a step could say why" (the run page reads "Verify changes Failed", which is right);
  9 was not probed and takes the same path; 10 was not probed (it needs the `next_run_at` helper);
  11 (clean-up not confirmed after delivery) shows "Factory could not confirm how the run ended"
  and no pull request on the task page. PR feedback: nine existing tests of
  `PrFeedbackIntegrationTest` now assert the run page text. No page changed, so `DemoConsole` was
  not opened. `mvn verify` passed. Seen, not changed: a second Run shows "MODBAR-204: task already
  has a live admission" and a second Retry "the task already has a live attempt" in the error
  banner; failed PR feedback checks read "Independent checks failed: CANDIDATE" (item B); an
  interrupted round after an engine escalation reads only "Resources cleaned up.".
- [x] Stage 2. What a person reads when a run stops. **Ready for a live run.** Items 2.1 to 2.6
  and 2.8 are done; 2.7 was stopped (below). 2.1 (4abe6f7): `StopReasons.ofResult` builds the
  stored reason from the result `VerifyWorker` writes (counts first, then `failureKind`), so the
  pages read "Checks failed: 2 of 300 tests failed.", "Checks failed: the code does not compile."
  or "Checks could not run: the build environment failed (Docker, network, disk space or time
  limit)."; the worker's outputs did not change, and `TaskLifecycleIntegrationTest` and
  `TaskViewTest` use the worker's shape. 2.2 (3d2fad3, 64cb631): `TaskStops.startingBuildFailedReason`;
  a finished attempt that never reached the agent shows "$0.00" instead of "cost unknown", but only
  when all its usage was counted (64cb631: usage without an attempt id keeps "cost unknown").
  2.3 (d10f954): `TaskStops.REPLAYED_CODING_REASON`, old text mapped in `AttemptFailure`, the order
  of `claimPaidStart` unchanged. 2.4 (a5bfb1c, 1ee0502): "Factory could not finish the step
  \"Create pull request\" after 3 tries: <first line of the engine's error, without credentials or
  ids>. Start a new attempt.", built in `TaskOutcomeService` from the step at the run's cursor, named
  as its card on the run page (`UiFormat.stepInWords`), and the step's real count in the run's
  retry counts. The engine stores an error only when it schedules a retry (`StateManager.scheduleRetry`)
  and never clears it, so the cause is named only when that step failed at least twice; otherwise
  the old "The run stopped before a step could say why" stays (also for `failTerminally`, which
  escalates without retries). The error shown is the one of the try before the last. The run page
  offers "Start attempt 2 on the task page" instead of "Open review" for it. 2.5 (4a6ceb2):
  `TaskStops.deadlineReason` with the "Limits in Configuration" link, old text mapped.
  2.6 (52fff74, 9e2dac6): `isLive()`; the task page shows "Open pull request #N", the attempt keeps
  its "Needs inspection" chip, and the run page offers "Open pull request #N" too, because its card
  now shows the draft reasons of that pull request. 2.8 (c1f1c30): `DeliveryWorker.deliveryRefusedReason` and
  `pullRequestRefusedReason`, retry rules unchanged. 2.7 not done: `RunState.getLabel()` is also
  the label of the Dashboard run tiles (`DashboardUiController` line 92, `DashboardUiControllerTest`
  line 113 expects "Asked a question"), so the change would change a framework page; and the
  `RunState` query gives ASKED also to runs whose question was answered, where "Needs your answer"
  would be false. The owner decides: a label for `/executions` only, or leave it. Scenarios 6 to
  11 pass in `TaskScenarioIntegrationTest`, each in the commit of its fix; 10 uses
  `finishThroughRetries`, which moves `next_run_at` to now between tries. Demo seeds (all through
  the production builders): 80009, 80029, 80030 (checks), 80031 (base branch), 80033 (engine gave
  up), 80020 (deadline), 80035 (GitHub refused the pull request), 80032 (replay), 80034 (needs
  inspection with pull request #34); 80017 and 80018 use the shipped limits. `mvn verify` passed;
  no JS changed. Looked at in `DemoConsole`, light theme, at 1280 px (headless Chrome) and 390 px
  (browser pane): both pages of My tasks, and the task page and the run page of 80009, 80029,
  80030, 80031, 80033, 80020, 80035, 80032, 80034, 80017, 80018 and 80025; 80033 and 80034 again
  after the two follow-up commits. Not done: the independent review of the stage diff by a fresh
  agent (this session could not start one). Seen, not changed: at 390 px the task page of 80034 is
  557 px wide, because 2.6 put a third button ("Open pull request #34") in the row of "Open run"
  and "Mark as stopped…"; the run page of every failed attempt is 405 px wide ("Start attempt 2 on
  the task page" passes the card's edge, also for 80033 since 2.4); My tasks cuts the reason to a
  few characters (all three for the stage 4 wrapping check); every finished demo attempt shows
  "cost unknown", because the seeds' usage has no attempt id and no price (acceptance 4); the run
  page of an attempt that needs inspection has no "Mark as stopped…"; after "Mark as stopped…" on a
  delivered attempt the reason names the branch with an id (`factory/modbar-211-<uuid>`) and the
  known pull request is hidden again; the `stopped()` demo seeds show every step as Passed on the
  run page (fixed for 80035, 80032 and 80020 in review round 2, still true for 80017, 80018 and
  80019); `StopReasons` (tasks.execution) uses `UiFormat` (web), as 2.4 asked.
  Review round 1 (fresh agent): two findings. Fixed: 2.5 worked only for a deadline at the
  implement step; the guard stores "verify: …" or "publish: …", so the pages read "Verification
  failed: The attempt used all of its time …" without the Limits link (e3e97d8: `AttemptFailure`
  says the deadline alone, also in the old words, and seed 80020 now stores the shape the guard
  writes); the stage word "candidate freeze" in that reason is now "saving the agent's changes"
  (7fa94f8). Deferred to the stage 4 wrapping check, as recorded above: the 557 px task page of
  80034 at 390 px. Looked at again after the fix: 80020 on My tasks, its task page and its run
  page, light theme, 1280 px and 390 px. `mvn verify` passed.
  Review round 2 (fresh agent): one finding, fixed (0ca444a). The seeds 80035, 80032 and 80020 had
  no stage artifacts, so their run pages read every step as Passed. They now store what the workers
  write, built from the production constants: 80035 the blocked delivery (its reason now comes from
  `StopReasons.ofResult`, same text), 80032 the candidate of the repeated coding step, 80020 the
  verify receipt and result of the deadline and a delivery that did not run. Looked at in
  `DemoConsole`, light theme, 1280 px and 390 px: the run page of 80035 reads "Create pull request
  Failed" after three Passed steps, 80032 "Implement changes Failed" with Verify and Create "Not
  started", and 80020 Prepare and Implement Passed with Verify and Create "Not started" (no card
  reads Failed for a guard stop at verify: that is how `RunSteps` shows a step that did not run).
  Their task pages and their rows on `/executions` ("Failed") did not change. `mvn verify` passed.
  Seen, not changed: seed 80019 stores `DEVELOPMENT_FAILED:` for a cause the code stores as
  `BLOCKED_ENVIRONMENT:` (`InitialCodingAttempt.findEnvironmentProblem`). Not done: an independent
  review of this fix (last round).
- [x] Stage 3. Dashboard spending. The block (fe412df): `TaskQuery.startedSince` takes the attempts whose run
  started in the period (`TaskAttemptRepository.findRunStartedSince`: `task_attempt.execution_id` joined to
  `pipeline_execution.created_at`, the window of the run tiles) and projects their tasks as the task page does, so
  each amount is the task page's. `SpendingRows` (tasks.presentation) builds the rows; the controller has one new
  dependency. Figures: Spent, Ended in a pull request, Attempts without a pull request (both money, so they add up to
  Spent), Cost per pull request (Spent divided by the tasks with a pull request in the period, "—" without one), and
  "N attempts have no price" for the attempts whose cost the task page shows as unknown. "Cost per task": most
  expensive first, at most 15 rows, then "N more tasks: $X"; the bar is plain CSS in two colours with a legend, and its
  label names both parts for screen readers. The block sits between the tiles and the charts and is left out when no
  attempt started in the period. One choice against the plan's words: the plan says "Jira key (link to the task
  page)", but AGENTS.md says the Jira key opens Jira on every page, so a row works like a row of Started tasks: the
  title (the whole row) opens the task page and the key opens Jira. Nothing was removed; `/api/dashboard/stats`,
  `DashboardStatsService` and the existing Dashboard assertions did not change. Tests: `UiRenderSmokeTest`
  (`theDashboardSaysWhatTheDeveloperFlowSpentAndHowMuchEndedInAPullRequest` with a known sum, a row amount equal to
  its task page and the 30 day period; `theDashboardShowsNoDeveloperFlowSpendingWhenNoAttemptStartedInThePeriod`;
  `costPerTaskShowsTheFifteenMostExpensiveTasksAndOneLineForTheRest`) and `DashboardUiControllerTest` (the period and
  the missing block). The demo seed (280524e): every run that reached the agent records its usage as the coding step
  does (`STEP_COMPLETED` with attempt id, model and the price from `AttemptRateSnapshot`; the running task live
  `coding_usage`), and its attempt has `run_settings`: glm-5.3 max, glm-5.3-flash high, glm-5.3 low. No task page or
  My tasks row says "cost unknown" any more; 80031 still shows $0.00; 80021 is the task that failed twice and then
  opened a pull request; 80017 (time limit, $5.51) is the expensive task that only failed. `mvn verify` passed; no
  JS changed. Looked at in
  `DemoConsole`, light theme: the Dashboard (14 days) at 1280 px (headless Chrome) and 390 px (browser pane, page 390
  px wide), My tasks and the task page of 80021 at both widths, and the step cards of the run pages of 80005, 80010,
  80017, 80020, 80021, 80032, 80033, 80034 and 80035 (the same as after stage 2; they now also show a Usage section).
  The run pages of 80021 and 80032 with their new Usage section at 1280 px and 390 px: both stay 390 px wide.
  Not seen: the Dashboard without the block, since every seed starts now (covered by the test). Not done: the
  independent review of the stage diff by a fresh agent (this session cannot start one); the new tests were written
  before the code but first run after it. Seen, not changed (for stage 4): at 390 px the task page of 80021 is 486 px
  wide: the row of its status card holds the amount ("$2.32 spent in 3 attempts", which stage 3's seed now gives) and
  the two buttons, and "Open run" ends at 486 px, the same issue as 80034; a running attempt's amount on the
  Dashboard has no "so far".
- [x] Stage 4. Console clean-up, the fixed list (the read-only pass by a fresh agent is not part of this entry).
  Effort sentence (e3eed95): both run forms of the task page (Start attempt N and the answer form) now end with "A new
  attempt starts from a clean checkout and adds to the cost." only. The effort text stays as the hover title of the
  Effort choice on My tasks, which the plan did not name. Test: `UiRenderSmokeTest`
  (`aFailedTaskPageStartsTheNextAttemptFromTheConfiguredModelAndAnEmptyTextField` and
  `taskPageShowsTheAgentsQuestionAndStartsTheNextAttemptWithTheAnswer` assert the note and that the effort sentence is
  gone; the first failed on the old template). Wrapping (6f6a707), CSS and one
  template only: the side of the status card (amount, clock and buttons) could not shrink, so at 390 px 12 task pages
  were 456 to 636 px wide (80034 with three buttons, 80021, every needs-inspection and pull request task). It now wraps
  inside the card; the run page uses the same card, so its "Start attempt 2 on the task page" now sits under the amount.
  The queue notice of a queued task ("Set FACTORY_TASKS_EXECUTION_ENABLED=true ...") breaks the long name instead of
  making the page 483 px wide. On My tasks the reason of a started task (its first sentence) had to share one cut line
  with the key and the age; it now wraps to at most two lines next to them, or under them on a phone, and the chip and
  the amount stay in their columns. At 1280 px the card looks as before. Checked by measuring every Developer Flow page
  of `DemoConsole` in a 390 px and a 1280 px frame (both pages of My tasks, 29 task pages, 29 run pages): none is wider
  than the window. Looked at, light theme, at 1280 px (headless Chrome) and 390 px (browser pane): both pages of My
  tasks; the task pages of 80034, 80021, 80027 (queued), 80029 (failed, retry form) and 80006 (answer form); the run
  page of 80029. Page tests and `mvn verify` passed; the JS tests passed (no JS changed). Seen, not changed: at 390 px
  the reason of 80033 ("Factory could not finish the step ... after 3 tries: 429 ...") is longer than two lines and ends
  with an ellipsis on My tasks (the row's title and the task page have it all); a reason on its own line under the key
  starts 10 px in; the Configuration tab's repository table is wider than its box at 390 px (456 against 350 px; the
  page itself stays 390 px); the Dashboard row of a running attempt still shows its amount without "so far" (handed
  over by stage 3, outside the fixed list). Headless Chrome with `--window-size=390` lays the page out wider than 390 px
  and cuts the right edge, so a 390 px look needs the browser pane or a 390 px frame. Not done: an independent review
  of the stage diff by a fresh agent (this session cannot start one).
  Review round 1 (fresh agent): one finding, fixed (e476370). At 390 px the turn tag "Nothing needed from you" of the
  status card passed the card's right edge on the task pages of 80007, 80011, 80012, 80013 and 80021 and on their 7
  run pages: the tag does not wrap and the text column next to the 150 px chip column is narrower than it. Page width
  did not show it (the text ended at 387 px inside a 390 px window). The tag now wraps inside the status card only,
  with its square on the first line; the other source tags and the chip column did not change. Checked by measuring
  the text edges against the status card, the rows and the answer section on both pages of My tasks, 29 task pages
  and 29 run pages in a 390 px and a 1280 px frame: no text passes its box and no page is wider than the window; the
  tag takes two lines only at 390 px. At 390 px "Waiting in the queue" on the queued task pages (80004, 80027) now
  also takes two lines: it was 0.2 px wider than the column. "Your turn" and "Factory is working" stay on one line.
  Looked at, light theme: the task pages of 80021, 80011 and 80027 and the run page of 80011 at 390 px (a 390 px frame
  in the browser pane), the one-line "Your turn" tag of 80006 at 390 px, and the task pages of 80021 and 80011 at
  1280 px (headless Chrome), where the card looks as before. Page tests and `mvn verify`
  passed. One review round is left for this stage.
- [x] Stage 5. Final check, all but the live smoke (the owner's, below). README (3d3cd1b): one quick start,
  the My tasks quick start, from `.env.tasks.example`, which already has the folio-org Jira, the assignee and
  `FACTORY_TASKS_EXECUTION_ENABLED=true`; the Compose section points to it, and `.env.example` is named as the
  list of further settings; the header of `.env.example` says the same (536f7bd, comments only;
  `ComposeConfigurationTest` passed and is unchanged). The quick start
  says that the default model needs a CodeMie proxy on port 4001 that Factory does not start, and how to pick
  another default model (`FACTORY_ZAI_API_KEY` and `FACTORY_DEV_FACTORY_DEFAULT_MODEL=glm-5.3-flash`); no
  command to start the proxy is given, because nothing in the repository or workspace says how. Controls named
  as the pages have them: Add by key and Add, Remove from list, each row's status in Jira (there is no status
  chip filter), the Model choice next to Run under the list, the Start attempt N form with "What should the
  agent do differently?" (it starts from the default model, not from the previous attempt), the "cannot run
  here" line instead of a red chip, a new attempt joining the queue, Failed after Mark as stopped, and the
  Claude Code profile that now holds only the image and the turn limit. Line 308 of the plan's list names no
  control (a `docker compose` command) and was left as it is. `docs/open-questions.md` (338a4e1, e726964):
  the deadline wording item is gone (2.5); the note on the question parser now says what item A does
  (the first JSON value after the marker, text after it ignored): it was not "corrected" to "the attempt
  fails", because A made it true; the patch check item says what D covers and what not; the limits read 400
  turns, 60 minutes and "of 1 h"; the `/executions` label item has the 2.7 owner decision; "Recorded, not done"
  and every "seen, not changed" finding of the stages are in a new section, plus two new findings: at 390 px the
  "In progress" strip of My tasks has no room for the task title, and the strip shows only on the page that
  lists the running task (`RunRows.strip` reads the current page). Not fixed: they are outside the stage 4 list.
  Gate: `mvn -B -ntp verify` passed (dev-factory 416 tests, 18 skipped; factory-app 688), the JS tests passed
  (17), and the `HIDDEN_*_SENTINEL` tests ran and passed (`UiRenderSmokeTest`, `ClaudeCodeCodingRuntimeTest`,
  `PiCodingRuntimeTest`, `DeveloperExecutionViewTest`). After the stage review, `ClaudeCodeCodingRuntimeTest` and
  `PiCodingRuntimeTest` each have a sentinel case for item A's split: thinking blocks hold
  `HIDDEN_REASONING_SENTINEL`, then the final text has a report, the marker, pretty-printed JSON and text after
  it; the outcome is a question, and the report, the question, the transcript and the activity do not hold the
  sentinel. Both are guard tests and passed on their first run. No sentinel case for the first line of the
  engine's error (2.4): it is Factory's own exception text, not model output, and `StopReasonsTest` already pins
  that only its first line reaches the reason. The other new stop reasons are built from Factory's words, counts,
  an HTTP status or a file name.
  Walk in `DemoConsole`, light theme. Measured, every page in a 390 px and a 1280 px frame (both pages of My
  tasks, the Dashboard, Configuration, 31 task pages, 31 run pages): no page is wider than its window, no text
  passes its status card, row or section except the "In progress" strip above, and no page shows "could say
  why", "engine execution ended", "Exception", a Java package, "cost unknown" or an id outside a closed
  disclosure. Looked at, 1280 px (headless Chrome): both pages of My tasks, the Dashboard with its four
  spending parts, the task pages of 80033 and 80034, the run page of 80016; 390 px (a 390 px frame in the
  browser pane): the "In progress" strip on page 2 of My tasks, the 80036 row of My tasks, the status cards of
  80033 and 80034, the Dashboard figures. Not done: the one review of the whole diff since `demo-baseline` by a
  fresh agent, which stage 5 asks for (this session cannot start one, and the stage 5 review read only the stage 5
  commits). It is also the only review that would cover the diffs whose own fresh-agent review is recorded above
  as not done: stage 3, the integration of A, B, D and E with its seeds, C and F, and the last fix of stage 2.
  Also not done: the stage 4 read-only pass by a fresh agent, G, 2.7 and scenario 12 (owner decisions above).
  This file is kept until the owner has run the live smoke and the whole-diff review has run.
  Live smoke, for the owner: (1) in `.env`, remove or raise `FACTORY_CLAUDE_CODE_MAX_TURNS=200` and
  `FACTORY_DEV_FACTORY_TIMEOUT_SECONDS=1800` and any explicit `FACTORY_TASKS_EXECUTION_DEADLINE` or
  `FACTORY_ENGINE_LEASE_TIMEOUT_SECONDS`, or the old limits win; check that `FACTORY_ZAI_API_KEY` is set.
  (2) On this branch: `docker compose up -d --build`, `docker compose ps -a`, `curl --fail
  localhost:8080/actuator/health`. (3) On the Configuration tab check the limits (1 h, 2 h, 400), that
  glm-5.3-flash is ready to run, and Check one task with the chosen key. (4) On My tasks set the Model to
  glm-5.3-flash, select the task and press Run; wait 20 to 60 minutes for the pull request. (5) Leave a review
  comment on the pull request; on the run page press Check PR comments, then Fix 1 comment, and check that the
  round pushes a commit. (6) Open the Dashboard for 30 days: about $18.99 spent and $3.58 ended in a pull request,
  each higher by the smoke's own cost. (7) If all is well and the whole-diff review has run, with its findings
  fixed or moved to `docs/open-questions.md`, delete this file; what is left is in `docs/open-questions.md`.
- [ ] If time is left: A B C D E F G. A, B, C, D, E and F are done; G was not started. A second agent did A, B, E
  and D in its own worktree; its commits were cherry-picked onto `m1-sprint-pilot` without a conflict, and the demo
  seeds were added in the main checkout. A (cc50f01): `CodingProtocol.answer` reads the first JSON value after the
  marker line, so a pretty-printed decision, one on the next line, a closing code fence and text after it are
  accepted; a fence that opens the decision is left out of the report, a code block the report closes stays. Both
  runtimes use it. `PiCodingRuntimeTest` asserted the old rejection of trailing text and now asserts the decision. B
  (c80e553): every `VerificationFailure` except NONE (plus CODING) offers "Try fixing with the agent";
  `FeedbackActionsTest` asserted that ENVIRONMENT does not and now asserts the plan's rule. The reason comes from the
  new `VerificationReceipt.failureReason()`: "Checks failed: some tests failed.", "Checks failed: the code does not
  compile.", "Checks could not run: …" or "Checks failed: the build failed with exit code N.". It has no test count,
  because the round prints "Checks failed: 2 of 300 tests failed." right under it. New scenario in
  `PrFeedbackIntegrationTest` with ENVIRONMENT checks; the agent fix then reaches READY. E (5a7f31f): "None
  identified", "No assumptions were needed" and "All links were readable" are none forms. D (b789d32, 47d0603,
  3e3df49): `CodingRuntime.holdsSecret` (Claude Code: every model secret; Pi: its API key) with one rule,
  `CodingRuntime.holdsAny`, for secrets of 16 or more characters; `Candidate.findFileAdding` reads only the lines
  hunks add. The first attempt stops with `TaskStops.secretInChangesReason(file)` and keeps its spend; a PR feedback
  round reuses the "Latest edits could not be saved" path with `TaskStops.secretInChangesCause(file)`, without "Start
  a new attempt.", because that page offers its own buttons under the reason (the finding of the worktree's review
  round, fixed in 3e3df49). Demo seeds: 80016 (7f9373e) now stores the receipt the verifier returns (1 of 1 tests
  failed, CANDIDATE) and its `failureReason()` instead of the typed "Independent checks failed: 1 test failed." with
  the commit-preparation kind; 80036 (a first attempt refused for a model key) and 80037 (a PR feedback round refused
  for a model key) are new (a452aad). For 80037 the start of the round's reason is now the constant
  `FeedbackState.EDITS_NOT_SAVED_START`, so the seed does not type it. `mvn verify` passed on the integrated branch;
  no JS changed. Looked at in `DemoConsole`, light theme, at 1280 px and 390 px (browser pane): the first page of My
  tasks, and the task page and the run page of 80016, 80036 and 80037. The run page of 80016 reads "Checks failed:
  some tests failed." under PR feedback and in the round, then "Checks failed: 1 of 1 tests failed." and the test
  output, with "Verify my manual changes", "Try fixing with the agent" and "Run checks again"; 80037 reads its reason
  with "Verify my manual changes" and "Try fixing with the agent"; 80036 reads "Attempt 1 failed", the reason, "No
  pull request was created." and $0.55, and its run page "Implement changes Failed" with Verify and Create "Not
  started". Every one of these pages, and the second page of My tasks (measured, not looked at), stays 390 px wide.
  Not done: an independent review of the integration (the cherry-picks and the seeds) by a fresh agent; no scenario
  drives a refusal for a model key through the pages (unit tests and the seeds only). Seen, not changed: at 390 px My
  tasks cuts the reason of 80036 after two lines, as for 80033; the round of failed checks without test reports reads
  "Checks failed." under "Checks could not run: …" (`FeedbackRows.checks`); `StopReasons.checks` and
  `VerificationReceipt.failureReason` say the same causes in two places; feedback states stored before B keep
  "Independent checks failed: …"; the INTERRUPTED PR feedback reason says "Start a new attempt explicitly." although
  its button is "Try fixing with the agent". For stage 5: B changed a deliberate rule (failed environment checks now
  offer the agent fix), and after A the note in `docs/open-questions.md` that text after a question line is ignored is
  true for both runtimes, so it must not be "corrected".
  C and F were done in the main checkout after the integration. C (211d0be): the spending block has a third part,
  "Where attempts stopped", one row per cause with its attempts and money, the most expensive cause first, causes
  without an attempt left out, and the part left out when no attempt stopped. The cause is the enum `StopCause`
  (tasks.presentation, 70 lines): `StopCause.of(state, storedReason)` reads the stored reason with the checks the
  pages already make, so older wordings count under the same cause as on the task page. Counted: every attempt of the
  period without a pull request that is neither running nor withdrawn. Choices to know: checks that could not run,
  the starting build, a repeated coding step, the engine giving up, a shared outage (`shared: ` or "check Developer
  Flow configuration"), `BLOCKED_ENVIRONMENT`, `DELIVERY_BLOCKED`, a run that ended without a reason and "Needs
  inspection" count as "Stopped by Factory or the environment"; a stop you marked yourself, a patch refused for a
  model key and a command that printed too much count as "Other". Running attempts are not counted, so while an
  attempt runs the causes add up to less than "Attempts without a pull request". Small production changes for it:
  the constants `StopReasons.ENGINE_GAVE_UP_START` and `TaskOutcomeService.SHARED` (the literals they replace are
  unchanged) and two package constants in `AttemptFailure` for "Checks failed" and "Checks could not run". F
  (9f16acd): a fourth part, "By model and effort", one row per model and effort in the words of the task page
  ("glm-5.3 · max"), with attempts, pull requests, "$0.80 per attempt" and the money, the most spent first; an
  attempt without run settings counts as "Model not recorded". Both parts are rows with thin lines, no bar, no
  card; the amount stands in the amount column of "Cost per task" and stays on the line of its row on a phone,
  where the facts wrap as a whole. Tests: `StopCauseTest` (every cause, built from the production builders, plus
  older wordings) and `UiRenderSmokeTest`
  (`theDashboardSaysWhereTheAttemptsWithoutAPullRequestStopped` with a known sum, a pull request and a running
  attempt that do not count; `theDashboardLeavesOutWhereAttemptsStoppedWhenNoneStopped`;
  `theDashboardSaysWhatEachModelAndEffortCostAndHowManyPullRequestsItOpened`). No new demo seed: the seeds already
  hold every cause. `mvn verify` passed (factory-app 688 tests); no JS changed. Looked at in `DemoConsole`, light
  theme: the Dashboard (14 days) at 1280 px (headless Chrome) and 390 px (a 390 px frame in the browser pane), both
  new parts. It reads: Stopped by Factory or the environment 8 attempts $8.51, Time limit 2 $6.06, Checks failed 3
  $3.92, Other 2 $2.20, Turn limit 1 $0.25, Asked a question 1 $0.12, No changes 1 $0.12; glm-5.3 · max 10 attempts 4
  pull requests $17.64, glm-5.3 · low 9 and 3 $12.67, glm-5.3-flash · high 10 and 4 $1.40, Model not recorded 2 and 0
  $0.00. The page stays 390 px wide and no text of the two parts passes its row. Not seen: the Dashboard with no
  stopped attempt (covered by the test); a group whose attempts all have no price ("cost unknown", not covered by a
  test). The new tests were written before the code but first run after it. The average per attempt divides by every
  attempt of the group, priced or not. F builds on C (it renames C's row record and CSS class), so reverting C needs F
  reverted first. Not done: an independent review of C and F by a fresh agent (this session cannot start one).
