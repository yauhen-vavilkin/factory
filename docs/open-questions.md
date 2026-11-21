# Open questions

Known gaps and ideas that are outside the current scope. Each item says where it came
from. When you pick one up, move it into a plan; when it is done or dropped, delete it here.

## Coding runtime tuning (later phase)

We tune the agent setup after the Developer Flow UI and UX problems are fixed.
Source: manual run review, 2026-10-27 (commit c068793).

- **Tools, MCP servers, skills.** The agent has `Bash, Read, Edit, Write, Glob, Grep,
  WebFetch`. No MCP servers, no skills. Try what helps on real tasks.
- **Shared instructions for all tasks.** A global instructions file in `config/` was
  discussed and postponed. Repository instructions come from the repo's own
  `CLAUDE.md` / `AGENTS.md`.
- **Loading `CLAUDE.md` / `AGENTS.md` by the CLI.** `--bare` is off, but with
  `--setting-sources ""` CLI 2.1.287 does not load them (checked 2026-10-27 against a
  local capture server: 0 requests with the file text; with `--setting-sources project`
  all 4 requests had it). The project source would also load the repository's
  `.claude/settings.json` (env such as `ANTHROPIC_BASE_URL`, hooks, `apiKeyHelper`), which
  could send the secret elsewhere, so it stays off. So item 12 of the developer flow fixes
  (the agent follows the repository's own instructions; spec in commit 82e0d0d) is met only
  partly: the CLI does not load the files, and a prompt line asks the agent to read and
  follow them, which the agent can skip (in a run of MGRAPPS-120 with a non-Anthropic model on
  2026-10-28 it read `AGENTS.md` as its first step). The prompt is shared, so Pi prompts got this line
  too (and, as the spec asks, the "Factory's rules win" line and the operator instructions
  block). Option: Factory reads `CLAUDE.md`, or `AGENTS.md` when there is none, from the
  frozen workspace and passes it in itself (`--append-system-prompt` or a titled prompt
  block, with a size cap). Operator decision.
- **Reading Confluence pages.** The ADR-0013 wiki page is 2 MB of HTML; WebFetch cuts it
  before the content. The REST URL `/wiki/rest/api/content/<id>?expand=body.storage` is
  34 KB and works with both WebFetch and `curl`. Decide whether to tell the agent this.
- **Effort per model.** A non-Anthropic model that Factory ran accepts `low`, `high`, `max` (its
  provider's default and advice for code: `max`); Claude Code sends `high` when no `--effort` is
  given. Measure `high` against `max` on real coding tasks. The levels of the cheaper model of the
  same provider are not documented; measure them.
- **Turn and time limits.** With a non-Anthropic model, 40 turns ran out during research
  (MGRAPPS-120).
  A run with 100 turns wrote the file after 17 minutes.
- **Task wording.** MGRAPPS-120 asks for values from the "bugfest environment baseline",
  which the agent cannot reach, so it searches other repositories for a long time. The
  same task links ADR-0013 twice to `/browse/ADR-0013`, an empty Jira page.

- **Resume after a Factory restart.** A coding attempt that is running when Factory restarts
  is lost; only the transcript written so far survives. Decide whether attempts should
  continue or restart on their own.

## Seen in live runs, not fixed

Source: runs of MGRAPPS-120 with a non-Anthropic model on 2026-10-27.

- `task_attempt.state` stays `QUEUED` while the attempt runs: no code sets `RUNNING`.
  The UI shows "Working" from the execution instead.
- After an attempt stops, the execution page still shows "Verify changes" and
  "Create pull request" as "Waiting", while the engine status is `COMPLETED`.
- `dev_coding_outcome.json` keeps the CLI's own cost estimate, which prices unknown models
  at Opus rates ($0.84 where the real cost with the model of the run is $0.32). The UI uses Factory's rate card.
- The legacy `FACTORY_MODEL_*` and `FACTORY_DEV_FACTORY_CODING_IMAGE` settings were removed on
  2026-10-31 (Developer Flow cleanup, phase 1). An `.env` that still sets them gets no warning. With
  the Pi runtime and only legacy values, Pi is "not configured" and preflight says so. With a mix
  of `FACTORY_PI_*` values and a leftover `FACTORY_MODEL_BASE_URL`, the Pi base URL is now empty and
  Pi uses its provider's built-in endpoint with the configured key.
  The `FACTORY_PILOT_*` names were renamed to `FACTORY_TASKS_*` on 2026-10-30. An `.env` that still
  uses the old names gets no warning either: My tasks stays off and Scan Jira finds nobody. The
  README "Configuration" section lists every rename.
- The same cleanup removed `factory.dev-factory.delivery.targets` and the startup check that
  refused an old `authorized` delivery key. A private catalog that still has either is now ignored
  without a message: pull requests go to the entry's `delivery.repository`.
- `docker-compose.yml` passes environment variables by an explicit list, so a new secret
  variable must be added there too. This includes the `secret-env` variable of every new model
  in `config/developer-models.yaml`.
- When Factory cannot prove that an attempt's containers are gone, `TaskOutcomeService.hold`
  holds the attempt for inspection but stores no reason. The dispatcher logs the cause ("Reconciled
  active task attempt <id> to NEEDS_INSPECTION: workload inactivity could not be confirmed: docker
  ps failed"), but the task page and the `task_attempt` row have none: the page says only that
  Factory could not confirm how the run ended. The parameter that was meant to store it was always
  null and was removed in the cleanup (phase 5).
- `/executions` runs one `ArtifactStore.getLatest` query per artifact name for each Developer Flow
  row (3 for a delivered run, up to 8 for an unusual one), where it ran one `allForExecution`
  before (`ExecutionListPage`, through `DeveloperExecutionView.listFacts`). A batched "latest by names"
  query would belong in `factory-core`, which the cleanup did not touch.
- `TaskOutcomeService.classify` maps any `DELIVERED` delivery to `NEEDS_INSPECTION` ("the
  candidate was delivered without a known pull-request identity"), because `deliveredOutcome`
  normally releases a delivery with a pull request first. An escalated run (`FAILED_ESCALATED` is
  terminal but can be resumed) can be approved and publish while `proveInactive` runs: then
  `deliveredOutcome` read the delivery before it was written and `classify` reads it after, so the
  attempt is held for one dispatch poll. The next refresh releases it as `PR_READY` through
  `deliveredOutcome`.
- Types instead of strings in the flow module (code audit of 2026-10-30, cleanup phase 7) were not
  started: stop and result states, `IntakeWorker` states and reason codes, `VerificationReceipt.result`,
  `FeedbackState.failureKind` and the `VerifyWorker` outcome map are still strings. Each change
  needs a round-trip test that keeps today's persisted JSON byte for byte.

## Left after the test-suite speed pass

Source: test-suite speed pass, 2026-10-31.

- factory-app starts 16 Spring contexts, not the five the speed prompt aimed for: 14 classes need
  their own Jira instance, repository catalog or mocked beans. Sharing more would mean new
  fixture values or mocks in tests that use the real beans.
- factory-core was out of scope: 14 Spring classes, about 48 s of context startup and its own
  `reuseForks=false`.

## Left after the Developer Flow fixes

Source: reviews of the developer flow fixes (spec in commit 82e0d0d), 2026-10-28.

- An empty Acceptance criteria section, or one that says only "None", opens a ready PR. A
  possible draft reason: "The agent listed no acceptance criteria."
- GitHub answers HTTP 422 when a draft PR is asked for in a repository without draft support.
  The delivery then ends as blocked, with no fallback to a ready PR. `DeliveryWorker` retries only
  408 and 429, so a 403 rate limit after the push also ends the run.
- Factory never reads the draft state back from GitHub. After "Ready for review" on GitHub
  the UI still shows "Draft PR".
- `/webjars` URLs (chart.js on the dashboard) are answered with `no-store`, not a long
  `Cache-Control`: `StaticResourceConfiguration` versions only `/css` and `/js`.
- The console JS tests (`factory-app/src/test/js/poll.test.js`, `time.test.js`) are not run by
  Maven or CI.
- PR feedback round summaries now use the four report headings, and the PR feedback panel on
  the run page shows them flattened into one line.
- Transcript and activity writes run on the CLI output reader thread. A slow database can
  stall the pipe; after 2 minutes the attempt fails as `OUTPUT_UNREAD`. Up to 19 transcript
  entries wait in memory and are lost if Factory stops.
- The transcript page renders every entry of every run in one response.

## Left after the models file and run form (2026-11-03)

- **Model per task.** Effort is chosen per task; the model is one per run. A model on each
  row was discussed and left out to keep the table readable.
- **Pi with a model choice.** Pi still takes its model and reasoning level from
  `FACTORY_PI_*`. The models file already accepts the Pi API types; Pi uses it only for
  prices and redaction.
- **Budget hard stop.** Max turns is now a hidden gate (400). A stop by spent money would
  protect better against a run that goes wrong. Needs a live cost from the stream.
- **Context from other repositories.** The agent sees only the task's repository. Some tasks
  need code from other modules.
- **Gemini through Claude Code.** CodeMie's LiteLLM gateway documents `/v1/messages` for
  non-Anthropic models, so a Gemini entry with `api: anthropic-messages` may work with
  Claude Code. Not tested: it needs the proxy up and a paid run with a tool call.
- **Re-run by key after a pull request.** "Add to run" by key could re-run a task that
  already had a pull request. Adding by key now puts the task in Available, and a task that
  already ran is sent to Retry, which refuses a task whose pull request is open.
- **Haiku 5.5 long prompts.** Anthropic prices Haiku 5.5 prompts over 100K tokens higher. An
  entry has one price, so such runs are estimated too low.
- **Entries that never ran.** CodeMie Opus 5.5, CodeMie Haiku 5.5 and the Anthropic-direct
  Sonnet 5.5 entry have not run a task yet (no paid runs). "Ready" on the run form only means
  that the secret is set.
- **Coding timeout.** A run of MGRAPPS-21 with a non-Anthropic model on 2026-11-03 (effort high) opened its draft
  PR after 29 minutes, one minute before the 30-minute coding timeout
  (`FACTORY_DEV_FACTORY_TIMEOUT_SECONDS`). The default is 60 minutes since 2026-11-15, and a
  whole attempt may take 120 minutes; whether that is enough for a normal task is not measured.
- **Review findings left.** From the review of af08eb6 on 2026-11-03:
  - Claude Code always gets `--effort`, also for a gateway model whose provider may ignore or
    refuse it. Check with a real CodeMie run before changing it.
  - `TaskUiController`, `TaskListController` and `TaskPageController` each define `FLOW_PAGE`.

## Left out of the demo UX work of 2026-11-10

Each item is a cut taken to keep a page as on its board (the boards were mockups, removed from the
repository on 2026-11-11; git history has them under `docs/superpowers`).

- **My tasks.** The fix steps dialog of a task that cannot run is gone; Cannot run here lists one row
  per task with a short cause chip (the whole sentence on hover) and Set up repositories, and the task
  page still has the stored reason. "cost may be higher" and "cost
  unknown" are no longer printed in Runs: the cost carries the explanation as its tooltip. The last scan
  time is the tooltip of Scan Jira. A task added by key keeps a small Remove from list button on its
  Ready to run row, which board 1 does not show; under Cannot run here it has none (recorded below).
  The in-progress strip shows the first running task only.
- **The lost question.** The parser (`CodingProtocol.answer`, both runtimes) reads the first JSON value
  after the first line that starts with `FACTORY_DECISION_REQUIRED:`, so a pretty-printed decision, one on
  the next line and a closing code fence are accepted, and any text after the JSON is ignored. The prompt
  still asks for a one-line answer; it was not changed.
  "Needs your answer" is read from the stop reason of the attempt (`TaskStops.asksForAnswer`), not from a
  stored state, so it needs no migration.
- **Task page.** The result chip of an attempt says "Failed", not a kind such as "Build failed" (board
  4): the stop reasons have no short kind yet; the status card shows the full reason under Reason, and
  Runs shows its first sentence under each run. "Cost and usage" moved into Technical details. The
  total time and the models of all attempts are no longer in the status card. The answer goes into the
  next attempt as its instructions: the answer first, then the question, then the agent's text before
  the question, cut to fit 8000 characters. The agent's evidence is shown on the page but not passed
  on. A task page with a pull request, or one that waits, is not on a board: it keeps "Open pull
  request" and "PR feedback" or "Open live run" in the status card.
- **Run page while it works.** Activity stays oldest first with the last 20 rows, so "Show 35 earlier"
  and the newest-first order of board 2 are not built. A tool call shows one row once it ends ("Read Foo.java · took 6 ms"); Pi reports no duration, so
  the time between start and end is used. Calls are paired only when the match is certain: when two
  calls of the same tool overlap and the end names no file or command, the end keeps its own row, as
  before. A call still running says "Running …", not "running now".
- **Run page with a pull request.** "checked N min ago" next to Check PR comments is not shown: the
  stored comments snapshot has no read time. The page head does not say "run 1" with its model and
  effort, and the note under Fix says "Starts a new round", without the round number and model. Each
  round row keeps a closed "Round details" disclosure (selected comments, check output, activity),
  which board 5 does not show. Comments that an earlier round addressed are no longer listed under New
  PR comments. The feedback controls and `DeveloperExecutionView` still build `Map` rows.
- **Factory's comment on the pull request.** Factory posts one comment after a round that pushed a
  commit, and keeps its URL in a `CONNECTOR_ACTION` audit event keyed by round; a repeated round step
  that finds that event does not post again. A crash between the GitHub call and the audit write posts
  the comment twice when the step runs again. The comment says "Factory round N" with the stored round
  number, which also counts manual verification rounds, so it can differ from "Round N" on the page.
  Reading PR comments leaves out comments that start with Factory's marker.

## Seen in the live check of the demo screens (2026-11-10, evening)

Runs: MGRAPPS-64 attempts 2 and 3 (`7631be89`, `48f6594f`), PR feedback round 2 on `79246e67`.
The question flow, the in-progress strip and Factory's comment on the pull request work.

- **The cost on the run page stayed at "$0.05 so far" for minutes.**
- **After the answer the agent worked in another repository.** The first option of the
  MGRAPPS-64 question was "retarget the task to mgr-tenant-entitlements"; the agent cloned
  that repository into /tmp although the request forbids a repository switch, and the attempt
  failed with "Command exceeded output limit". MGRAPPS-64 is not a task for the demo. Whether
  a run after an answer reaches a pull request is not verified.

## Left after the console polish of My tasks and the task page (2026-11-11)

- **"Command exceeded output limit" has more than one cause, and the page cannot say which.**
  `Processes.run` stops a command that prints more than its limit: 2 MB for Factory's own commands
  (git, including the candidate diff in `CandidateFreezer`, `docker cp`, the starting build) and
  16 MB for the Pi event stream, which carries the agent's tool output. The Claude Code CLI runs
  through `Processes.stream`, which has no total limit, so its tool output never hits it. The fix
  list's sentence blamed "a command run by the agent" and suggested `| tail -n 100`, which does not
  help with Claude Code; `TaskStops.OUTPUT_LIMIT_REASON` now names no culprit and asks for narrower
  instructions, a small change and short command output. Which command printed too much in
  MGRAPPS-64, and whether 2 MB fits a large diff, is not checked.

## Left after the console polish of the run page (2026-11-11)

- **Pricing in Technical details is not a link.** The rate cards name a source and a date
  (a host name and `(2026-09-19)`) but no URL, so the page writes them as text. A link needs a `url`
  per rate in `config/developer-models.yaml`.
- **Artifacts (N) cannot link to a filtered list.** `/artifacts` has no `executionId` filter, so
  the run page lists the versions of each artifact in a disclosure instead of linking out.
- **Activity rows at phone width are not checked.** The time, verb, target and result columns of
  Activity were checked at desktop widths only; a long target may need its own line below 480 px.
- **Placeholder targets look like code.** An Activity row whose event names no file shows a
  placeholder such as "the project" in the code style of a real path. A plain style for those
  needs the activity rows to say which targets are real.

## Left after the console polish of Configuration, Definition and the framework pages (2026-11-11)

- **Run forms do not mark an unreachable endpoint.** The fix list wanted `(endpoint not reachable)`
  in the run forms and such a model under Cannot run. The TCP probe takes up to 3 s per closed
  address, so My tasks and the task page do not run it; only the Configuration tab probes, and it
  keeps the model under Ready to run with the warning, because the run forms still offer it. A
  cached probe result could reach the forms later.
- **Not in this work** (fix list of 2026-11-11), each still as the owner found it:
  - Audit page.
  - Artifacts pages.
  - Prompts pages.
  - Pull request text and commit titles.

## Left after the second console audit (2026-11-11)

- **The run list lags the task page for up to 30 s after a pull request opens.** `/executions` and the Dashboard read the attempt (`RunState`), which the dispatcher closes a little later; the task page already shows the pull request.
- **Runs without a task attempt are classified by a LIKE on artifact JSON.** `RunState.SQL` matches `"state":"DELIVERED"` and `"status":"NEEDS_DECISION"` in compact JSON; a stored state would make it exact.
- **Activity rows stored before the first-line fix keep their joined heredoc target.** Only new runs store the first line of a shell command.
- **`DeveloperExecutionView.ListFacts.productStatus` is read only by tests.** The run list now takes its state from `RunState`.
- **The run form's model refusal still names the config API word.** `ModelCatalog.problem` says "uses api openai-completions"; only the Configuration tab says the API in words.

## Left after the Dashboard and list polish (2026-11-11)

- **Connector names are ids.** Status and the Dashboard's "Connector actions" chart show `github`, `jira`, `testrail`. `ConnectorHealth` has no display name, and the audit rows the chart counts store the id; a name on each connector would serve both.
- **Reviews still shows the raw decision.** The Decision column says `APPROVE`, `AMEND` or `REJECT`, and the flash message after a decision says "Decision APPROVE recorded for review <uuid>".
- **Workers "Used by" names flows and steps by their ids** (`dev-factory · read-task`), while Executions, Reviews and Artifacts now use the flow name.
- **The "Runs started per day" chart labels its days as ISO dates** (`2026-11-01`), not as the short dates of the lists.

## Left after the live-run polish (2026-11-11)

- **The step time can read past its limit.** The run card counts Implement changes from the step's start against "of 1 h", but the time per step applies to the first build and to the coding separately (`InitialCodingAttempt`), so a long first build can show, for example, "70 min of 1 h" without a stop.
- **/executions says "Asked a question" where the task says "Needs your answer".** Both name the same stop: the run list reads the run's state (`RunState.ASKED`), the task pages read the attempt's stop reason. Giving `/executions` the words of the task pages was stopped: `RunState.getLabel()` is also the label of the Dashboard run tiles (a framework page, `DashboardUiController`), and `RunState` gives ASKED also to runs whose question was already answered, where "Needs your answer" would be false. Owner decision: a label for `/executions` only, or leave it.

## Found by the cleanup audit (2026-11-11), not fixed

The cleanup changed no behavior, so these were recorded instead. None was reproduced in a run.

- **The candidate patch is checked only for configured model secrets.** Since 2026-11-15 a first
  attempt and a PR feedback round refuse a patch whose added lines hold a configured model secret of 16
  or more characters (`CodingRuntime.holdsSecret`, `Candidate.findFileAdding`). A shorter secret, an
  encoded one, or one in a binary patch still passes. The coding container holds the model secret in a
  file (`/tmp/factory-claude/environment`, `/tmp/factory-pi/models.json`) and the agent has a shell;
  whether the agent can read that file is not checked.
- **An escalation of a Developer Flow step leaves no trace in audit and metrics.**
  `ResumableStepTransactions.fail` writes no `ESCALATED` audit event and calls neither
  `stepEscalated()` nor `stepRetryScheduled()`, and its `RETRY_SCHEDULED` event has no
  `backoffSeconds`. `ExecutionEngine` does all of these for a normal step.
- **GitHub without credentials breaks delivery with the wrong error.** `UnconfiguredConnectors.GitHub`
  has no `findPullRequests`, so `DeliveryWorker` gets `UnsupportedOperationException`, not
  `ConnectorNotConfiguredException`.
- **Null content.** `ArtifactStore.put` guards the size of a null content but then hashes it and
  fails; `JiraRestConnector.getIssue` does not guard an empty response body.
- **A bad repository answer loses its error prefix.** `DevArtifactAmendmentValidator.validateRepositoryAnswer`
  catches `ArtifactFormatException`, but `DecisionArtifacts.parseAnswer` throws
  `IllegalArgumentException`: the amendment is still refused, without "Amended decision answer is not
  valid:".
- **An amendment can write any artifact name.** `HitlDecisionService` does not limit AMEND to the
  gate's `reviewed_artifacts`. Maybe intended.
- **The 404 page shows its error in the green toast.** `UiExceptionHandler` puts the text in `message`,
  which the layout renders as a success alert that goes away by itself.
- **The Retry scheduled badge has no colour.** `UiFormat.eventTone` returns `warning`, and `app.css`
  has no `.badge-warning`.
- **`btn-sm`, `input-sm` and `select-sm` are classes without a rule.** Their rules repeated `.btn`,
  `.input` and `.select` and were removed; the class names are still in about 30 template places and
  in `TaskDetail` and `FeedbackUi`. Removing them changes markup that tests pin, so it was left.
- **`ExecutionEngineTest` depends on the real 333 ms heartbeat.** An injectable scheduler would make
  its lease-loss tests independent of timing; that is a production change.
- **Pull request and commit text** (from the demo UX findings of 2026-11-10): Factory posts no reply
  per review comment; the commit author is `Factory <factory@localhost>` and the title "Address PR
  feedback (round N)" has no task key; "Changed files" repeats "Changes" in the PR text; a file and
  line in a PR feedback comment is not a link.

## Left after the demo preparation (2026-11-15)

Source: the plan that prepared the demo on 2026-11-15 (git history has it at 63e8ecc), its "Recorded,
not done" list and what its stages saw but did not change.

Engine and framework pages, outside that plan's scope:

- **One failed heartbeat write discards a finished run.** `AgentLeaseHeartbeat.renew` stores any
  exception as a permanent failure; the task then stays "In progress" for up to two hours. It is in
  `factory-core` and changes lease ownership.
- **Dashboard charts.** The doughnut repeats the tiles; "Runs per flow" and "Connector actions" show
  one bar each. Status breaks long variable names inside a word in its connector cards.
- **The engine-gave-up reason names the error of the try before the last.** The engine stores an error
  only when it schedules a retry (`StateManager.scheduleRetry`), so the cause is named only when the
  step failed at least twice; a run that `failTerminally` escalates still reads "The run stopped before
  a step could say why".

Developer Flow:

- **The replayed `implement` step runs the whole starting build before it stops.**
- **Draft reasons, a second parser pass:** nested items, "(met)", "is met", table rows.
- **Partial work at a timeout is lost, and a working run has no Stop button.** A run that hangs holds
  the single slot for up to 60 minutes per step.
- **An `.env` with the old limits keeps them.** `FACTORY_CLAUDE_CODE_MAX_TURNS=200` and
  `FACTORY_DEV_FACTORY_TIMEOUT_SECONDS=1800` from an older `.env.example` win over the new defaults.
- **Internal words that are left:** a second Run shows "MODBAR-204: task already has a live
  admission" and a second Retry "the task already has a live run" in the error banner; the guard's
  lost-slot stop at verify or publish reads "Verification failed: the run no longer owns the
  installation's active slot; …". The queue notice can name an attempt record by its id
  ("Task attempt … is not dispatchable").
- **PR feedback wording.** An interrupted round after an engine escalation reads only "Resources
  cleaned up.". The INTERRUPTED reason says "Start the coding again explicitly." although its button is
  "Try fixing with the agent". A round that the time or turn limit stopped shows the sentence of a task
  run, "Start a new run with …", although a task with a pull request cannot start a new run
  (`FeedbackState.coded` takes the message of `TaskStops` as it is). A feedback state and a
  transcript stored before the pages said "run" keep the word "attempt": they are shown as stored,
  not through `AttemptFailure.reason`. A round whose checks failed without test reports reads "Checks failed."
  under "Checks could not run: …" (`FeedbackRows.checks`). `StopReasons.checks` and
  `VerificationReceipt.failureReason` word the same causes in two places.
- **Failed environment checks now offer "Try fixing with the agent".** Since 2026-11-15 every kind of
  failed check offers it, which changed a deliberate rule; check in a live round that the agent fix
  helps there.
- **Old stored reasons keep old words:** "candidate freeze" in a deadline reason, and "Independent
  checks failed: <KIND>" in PR feedback states stored before that change.
- **Needs inspection.** Its run page has no "Close as failed…". After "Close as failed…" on a delivered
  attempt the reason names the branch with an id (`factory/modbar-211-<uuid>`) and the known pull
  request is hidden again.
- **Labels.** On the task page an answered attempt's row reads "Asked a question", while My tasks said
  "Needs your answer" before the answer. The effort sentence is still the hover title of the Effort
  choice on My tasks.
- **Statistics tab, choices to confirm.** It counts the runs that started in the period and their
  tasks; a task's bucket is where the task stands now, so a task whose earlier run started before the
  period still counts its later pull request. "Per task with a pull request" and "Per merged task"
  divide the spend of the period's runs of those tasks, and the money of a running run counts as far as
  it is known. "By model and effort" divides only by the runs with a price. The bucket of a task reads
  its PR feedback, a few database reads per task with a pull request on every load of the tab.
- **Phone width (390 px).** My tasks cuts a long reason after two lines (80033, 80036 in the demo
  console); a reason on its own line under the key starts 10 px in; the repository table of the
  Configuration tab is wider than its box (456 against 350 px). The "In progress" strip has no room
  for the task title (it shrinks to nothing) and cuts "codemie-sonnet-5.5 · max · 14 min".
- **The "In progress" strip shows only on the page of My tasks that lists the running task.**
  `RunRows.strip` reads the rows of the current page, so with two pages of Started tasks the strip can
  be missing from page 1 (seen in the demo console, where MODBAR-805 is on page 2).
- **`StopReasons` (tasks.execution) imports `UiFormat` (web)** for the step name of the engine-gave-up
  reason.
- **The run page of an older attempt names a merged or closed pull request by its old chip.** Its line
  "Run 1 of 2. Latest: run 2, PR #12." comes from `RunContext.standing`, which does not know
  where PR feedback stands; the task page and the run page of attempt 2 say "Merged PR #12".

Tests and the demo console:

- **No page test for "Could not start".** `TaskScenarioIntegrationTest` numbers its scenarios 1 to 11
  and 13; the missing scenario 12 is a task that cannot start, and the queue notice while Factory's
  preflight fails. It needs its own Spring context with the default GitHub API and a mocked
  `DeveloperPreflightProbe`, while all scenarios share the one context of that class. Owner decision.
- **No scenario drives a patch refused for a model key through the pages**; unit tests and demo seeds
  cover it. These scenarios were not written either: the time limit of a whole run, the turn limit, the
  output limit, a ready pull request after a clean report, two queued tasks, intake outcomes (issue not
  found, repository choice needed), a refusal of the publication guard, a fork behind its base, PR
  feedback clean-up blocked and refresh required, a manual push before a round publishes, and runs
  started without a task (`POST /flows/dev-factory/trigger`).
- **`DemoConsole` still seeds finished states by hand** (the reasons through production code). Running
  the scenarios at its startup needs task execution on, which would let its queued tasks start. Seeds
  80017, 80018 and 80019 show every step as Passed on the run page; 80019 stores `DEVELOPMENT_FAILED:`
  for a cause the code stores as `BLOCKED_ENVIRONMENT:`; the delivery of 80020 has no reason, because
  it is a private literal in `DeliveryWorker.notVerified`; every seeded PR feedback round shows $0.00
  for the round. A By model and effort row of the Statistics tab whose runs all have no price ("cost
  unknown") is not covered by a test. The task page turn of a held run (one that needs inspection) while
  a PR feedback round works is covered by a test
  (`TaskUiControllerTest.aRunThatNeedsInspectionIsYourTurnWhileAPrFeedbackRoundWorks`) but has no demo
  seed, so it was never seen on a page.

## Left after the review of the whole pull request (2026-11-15)

Found by the review of everything since `main` before the merge; fixed findings are in the commits of that day.

- **A double click on Run or Start run shows a refusal for work that was queued.** Every POST makes a
  new request id (`"ui-" + UUID.randomUUID()` in `TaskUiController` and `TaskPageController`), so the second
  click is refused with "task already has a live admission". A request id rendered into the form, and a
  lookup by it right after `lockById` in `TaskAdmissionService`, would make the second click a no-op.
- **A scan stores the Jira key, id and project key without checking them.** One field over the 64
  characters of its column fails the whole scan, and a stored key that does not match `IssueSnapshot.ISSUE_KEY`
  fails `DeveloperPreflight.requireReady` with a plain `IllegalArgumentException`, which the dispatcher
  retries on every poll. Jira enforces its key format, so this needs a broken or hostile Jira.
- **A withdrawn task loses its repository cause on My tasks.** `TaskRows.availableRow` reads the routing
  problem only for a task in state `UNSUPPORTED`. A task whose attempts were all withdrawn and whose
  project then lost its repository, or got several, is `WITHDRAWN`: it shows under Cannot run here as
  "Cannot run" with "No reason was recorded.", and Set up repositories is not offered. Found in
  review, not reproduced.

## Left after the review of the last three commits (2026-11-20)

The owner left the live smoke out before the merge. The gaps after it were found by the review of
531a84d, 5be8c49 and 63e8ecc and by the check of its fixes, and are older than those commits; the fixed
findings are in the commits of that day.

- **The live smoke was not run before the merge.** No real model, Jira or GitHub ran on the final
  code; the pages were checked in the demo console instead. To run it:
  1. In `.env`, remove or raise `FACTORY_CLAUDE_CODE_MAX_TURNS=200` and
     `FACTORY_DEV_FACTORY_TIMEOUT_SECONDS=1800` and any explicit `FACTORY_TASKS_EXECUTION_DEADLINE` or
     `FACTORY_ENGINE_LEASE_TIMEOUT_SECONDS`, or the old limits win; check that the secret of the model you run is set.
  2. On the merged code: `docker compose up -d --build`, `docker compose ps -a`,
     `curl --fail localhost:8080/actuator/health`.
  3. On the Configuration tab check the limits (1 h, 2 h, 400), that the model you chose is ready to run, and
     Check one task with the chosen key.
  4. On My tasks set the Model to the model you chose, select the task and press Run; wait 20 to 60 minutes
     for the pull request.
  5. Leave a review comment on the pull request; on the run page press Check PR comments, then Fix 1
     comment, and check that the round pushes a commit.
  6. Before step 4, note the Developer Flow spent line of the Dashboard for 30 days. After the smoke,
     open it again: the line is higher by the smoke's cost, and on the Statistics tab the smoke's task
     and run are counted.
- **A task added by key that cannot run stays under Cannot run here.** Add by key takes a task from a
  project with no repository on purpose (`TaskAdmissionService.addByKey`), so the task is `INELIGIBLE`
  and shows only under Cannot run here. `CannotRunRow` has no task id and no `addedByKey`, so that row
  has no Remove from list, and a scan never drops a task added by key
  (`TaskDiscoveryService.applyCompleteSync`). The row stays until its project gets exactly one
  repository. A fix: carry `taskId` and `addedByKey` in `CannotRunRow` and give that row its own Remove
  from list form, the POST to `/tasks/{id}/remove-from-list` that Ready to run uses.
- **"spent in N runs" counts a run that has not started.** `TaskRows.spentNote` counts every attempt
  of the task, so a task with two finished runs and a queued third reads "$0.06 spent in 3 runs" on the
  status card of the task page (MODBAR-838 in the demo console); the Total row of Runs says "3 runs" in
  the same way (`TaskSpending.total`). A fix counts only the attempts that started (`startedAt`), as
  `TaskSpending.of` already does; the Total label counts the rows above it, so it may stay as it is.
- **The run page says "Some usage was not reported" for a run on a model without a price.**
  `RunUsage.attempts`, the path of every current run, gives the note `INCOMPLETE` ("Some usage was not
  reported, so the real cost may be higher.") to every partial charge, and a charge without a price is
  partial. `RunUsage.steps`, the path of runs of older flow versions, says `NO_PRICE` ("No price is set
  for this model, so the cost is not known.") for the same case. Seen on the run page of MODBAR-838 run 2
  in the demo console. A fix would tell the two cases apart in `RunUsage.attempts`, as `RunUsage.steps`
  does.

## Left after merging GitLab main into m1-sprint-pilot (2026-11-20)

- **Runs started before the yarn lockfile change cannot continue.** The UI-repository work on GitLab
  main added `dev_lockfile.json` to the inputs of `continue-implementation`, `verify` and
  `pr-follow-up` in `flows/dev-factory.yaml`. `ExecutionEngine.buildContext` fails a step whose input
  artifact does not exist, so an execution that ran `implement` before this change fails those steps
  after the update, and PR feedback stops on its open pull request. The engine fails the step before
  the worker runs, so the worker cannot treat the input as optional. A fix: a one-time Flyway data
  migration that writes a `PinnedLockfile.none(...)` `dev_lockfile.json` for open dev-factory
  executions. Optional step inputs in the engine would change `factory-core` for one flow. Until then,
  finish or close running tasks and merge or close their pull requests before the update.
- **The Docker tests of the yarn path were run after the merge, on 2026-11-20.** `ClaudeCodeDockerTest`,
  `DockerWorkloadsTest`, `ImageStagingDockerTest`, `FeedbackDockerTest` and `PinnedLockfileDockerTest`
  need `FACTORY_DOCKER_TEST=true` and the `-node22` images. On macOS with Docker Desktop 56 gated tests
  pass and two fail, on the merged code and on GitLab main alike; see the next entry.
- **Two Docker tests of the yarn path fail on macOS with Docker Desktop.**
  `DockerWorkloadsTest#yarnTrustedCachePersistsButSeededWorkloadCannotModifyIt` fails at its first check
  with exit 2: the grep for the `@folio` registry line in `$HOME/.yarnrc` inside the trusted container
  fails, and 2 is grep's code for a file it cannot read.
  `PinnedLockfileDockerTest#fixtureWithoutALockfileVerifiesWithThePinnedVersionAfterANewerSnapshotIsPublished`
  fails because yarn asks registry.yarnpkg.com instead of the throwaway registry. The cause is not yet
  known. Both fit a `.yarnrc` that the workload's start command (`DockerWorkloads.startupCommand`) did
  not write or yarn did not read, so `@folio/*` packages go to yarn's default registry; that may mean a
  ui-* run on a Mac does not use the configured registry. A fix starts with finding why the file is
  missing there, for example the owner of `/tmp/factory-home` for the workload user, or `docker start`
  returning before the start command has written the file.
- **The run page keeps "Retrying after Workspace dependency install …" after the retry worked.**
  `DeveloperExecutionView.retryErrorSuperseded` hides an old retry error only when it starts with
  "Starting build hit a network/download failure". A registry failure of the yarn install in the coding
  container (`InitialCodingAttempt`) throws `StartingBuildNetworkException` with "Workspace dependency
  install hit a network/download failure …", and `StateManager.scheduleRetry` stores it in
  `errorMessage`, which nothing clears. After a retry that works, `RunStatusCard` shows "Retrying after …"
  through coding, verification and publish. GitLab main had the same gap, shown as "Previous attempt".
  A fix: keep both prefixes as shared constants, and count the install error as superseded once the
  runtime was observed or the cursor has moved past the step that failed; the fixed
  `getCurrentStepIndex() > 3` would hide it while `continue-implementation` (step 5) still retries.
- **A PR feedback round whose dependency install fails leaves a cost that is never known.**
  `FeedbackCodingAttempt.run` calls `recordStart` (the rate snapshot and the `coding_starting` event)
  before `PreparedWorkspace.prepare`. When the install fails, `prepareAndCode` returns `WORKSPACE_INSTALL`
  without a model call or token counts, so `AttemptUsageSummary` keeps an attempt with no usage. The round
  row shows "—" instead of $0.00, and the task page says the cost may be higher, for good, because the
  audit is append-only. GitLab main has the same order. A fix: move `recordStart` into `prepareAndCode`,
  after a successful install and right before `code(...)`, as `InitialCodingAttempt` does, and check in
  `FeedbackCodingAttemptTest` that a failed install emits no `coding_starting`.
- **"Try fixing with the agent" can fail every time after checks that failed in the dependency
  install.** `FeedbackActions.isCodingOrChecksFailure` offers `RETRY_CODE` after any failed checks,
  `INSUFFICIENT_EVIDENCE` included. When the round's own candidate breaks `yarn install` (a range yarn
  cannot resolve, or `package.json` changed without the tracked `yarn.lock`), verification gets no Jest
  report, and the retry runs the same install first: `FeedbackCodingAttempt.prepareAndCode` returns
  `WORKSPACE_INSTALL` before the agent starts. The candidate does not change, so every press fails the
  same way, with no model spend; a new round from the PR head or a manual push drops the round's edits.
  A fix belongs in `prepareAndCode`: when the candidate's `package.json` or `yarn.lock` differs from the
  PR head, start the agent with the install error so it can repair its change, and stop before the model
  only when the PR head itself does not install.
- **Run activity shows the yarn install and image events by their raw names.** `PreparedWorkspace` emits
  `workspace_install_started` and `workspace_install_completed`, and `ImageStagingRuntime` emits
  `attachments_staged`. `DeveloperRuntimeActivity.eventText` has no case for them, so `genericText` shows
  "workspace install completed · 1" or a bare "attachments staged", and `failedEvent`, which knows only
  `provider_error` and `auto_retry_end`, gives a failed install the normal tone. The run still stops with
  the install reason on the task and run pages. GitLab main showed the same text. A fix: labels for the
  three events in `DeveloperRuntimeActivity`, and the failed tone for a non-zero `exitCode`.
- **The preflight JSON reports only Maven runtime facts.** `DeveloperPreflight.effectiveRuntime` puts
  `mavenCacheVolume` into the runtime map but not the yarn cache volume, the `@folio` registry, the install
  command or the seed timeout, and `effectiveRepositories` has no toolchain. Only
  `/api/developer/preflight` returns these maps; the Configuration tab does not show them. A fix would add
  the yarn facts to the runtime map and the toolchain to each repository.
- **The Configuration tab shows only the plan command as the checks of a yarn repository.**
  `DeveloperRunSetup.checks` prints the plan's tokens and the build image, for example "yarn test
  --maxWorkers=2 in node:22.22.0-bookworm". The starting build and the checks run the configured yarn install
  first (`DevRuntimeProperties.verificationCommand` puts `installCommand` in front of a YARN plan), so the tab
  leaves out the step that most yarn failures come from, `--frozen-lockfile` included, and a developer who reads
  it cannot explain a lockfile error. A fix: when `installCommand(plan)` is not empty, show "<install> then
  <plan> in <image>" from the joined tokens, not the `sh -c` form.
- **A run that waits for an answer from before the pinned lockfile cannot continue.** `dev_lockfile.json` is
  an input of `continue-implementation` and `verify`, and the engine does not start a step whose input
  artifact is missing. A run that started on code before the pinned lockfile and still waits for an answer to
  the agent's question fails at `continue-implementation` after the update; start a new run for it. PR feedback
  is not affected: `FeedbackActions.addMissingLockfile` adds the artifact of a run that pinned nothing before
  it resumes such a run.
