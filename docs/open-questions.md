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
  follow them, which the agent can skip (in the GLM-5.3 run of MGRAPPS-120 on 2026-10-28 it
  read `AGENTS.md` as its first step). The prompt is shared, so Pi prompts got this line
  too (and, as the spec asks, the "Factory's rules win" line and the operator instructions
  block). Option: Factory reads `CLAUDE.md`, or `AGENTS.md` when there is none, from the
  frozen workspace and passes it in itself (`--append-system-prompt` or a titled prompt
  block, with a size cap). Operator decision.
- **Reading Confluence pages.** The ADR-0013 wiki page is 2 MB of HTML; WebFetch cuts it
  before the content. The REST URL `/wiki/rest/api/content/<id>?expand=body.storage` is
  34 KB and works with both WebFetch and `curl`. Decide whether to tell the agent this.
- **Effort per model.** GLM-5.3 accepts `low`, `high`, `max` (z.ai default and advice for
  code: `max`); Claude Code sends `high` when no `--effort` is given. Measure `high` against
  `max` on real coding tasks. Levels for `glm-5.3-flash` are not documented; measure them.
- **Turn and time limits.** On GLM-5.3, 40 turns ran out during research (MGRAPPS-120).
  A run with 100 turns wrote the file after 17 minutes.
- **Task wording.** MGRAPPS-120 asks for values from the "bugfest environment baseline",
  which the agent cannot reach, so it searches other repositories for a long time. The
  same task links ADR-0013 twice to `/browse/ADR-0013`, an empty Jira page.

- **Resume after a Factory restart.** A coding attempt that is running when Factory restarts
  is lost; only the transcript written so far survives. Decide whether attempts should
  continue or restart on their own.

## Seen in live runs, not fixed

Source: GLM runs of MGRAPPS-120 on 2026-10-27.

- `task_attempt.state` stays `QUEUED` while the attempt runs: no code sets `RUNNING`.
  The UI shows "Working" from the execution instead.
- After an attempt stops, the execution page still shows "Verify changes" and
  "Create pull request" as "Waiting", while the engine status is `COMPLETED`.
- `dev_coding_outcome.json` keeps the CLI's own cost estimate, which prices unknown models
  at Opus rates ($0.84 where the real GLM cost is $0.32). The UI uses Factory's rate card.
- The legacy `FACTORY_MODEL_*` and `FACTORY_DEV_FACTORY_CODING_IMAGE` settings were removed on
  2026-10-31 (Developer Flow cleanup, phase 1). An `.env` that still sets them gets no warning. With
  the Pi runtime and only legacy values, Pi is "not configured" and preflight says so. With a mix
  of `FACTORY_PI_*` values and a leftover `FACTORY_MODEL_BASE_URL`, the Pi base URL is now empty and
  Pi uses its provider's built-in endpoint with the configured key. The local `.env` still has
  `FACTORY_MODEL_*` and `FACTORY_DEV_FACTORY_DELIVERY_*` lines (runtime `claude-code`); delete them.
  Its `FACTORY_PILOT_*` names were renamed to `FACTORY_TASKS_*` on 2026-11-03. An `.env` that still
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
  before (`ExecutionUiController.executionRow`, cleanup phase 6). A batched "latest by names"
  query would belong in `factory-core`, which the cleanup did not touch.
- `TaskOutcomeService.classify` maps any `DELIVERED` delivery to `NEEDS_INSPECTION` ("the
  candidate was delivered without a known pull-request identity"), because `deliveredOutcome`
  normally releases a delivery with a pull request first. An escalated run (`FAILED_ESCALATED` is
  terminal but can be resumed) can be approved and publish while `proveInactive` runs: then
  `deliveredOutcome` read the delivery before it was written and `classify` reads it after, so the
  attempt is held for one dispatch poll. The next refresh releases it as `PR_READY` through
  `deliveredOutcome`.
- Types instead of strings in the flow module (audit section 8 of
  `docs/superpowers/specs/2026-10-30-dev-flow-code-audit.md`, cleanup phase 7) were not started:
  stop and result states, `IntakeWorker` states and reason codes, `VerificationReceipt.result`,
  `FeedbackState.failureKind` and the `VerifyWorker` outcome map are still strings. Each change
  needs a round-trip test that keeps today's persisted JSON byte for byte.

## Left after the test-suite speed pass

Source: test-suite speed pass, 2026-10-31 (audit section 9 in
`docs/superpowers/specs/2026-10-30-dev-flow-code-audit.md`).

- factory-app starts 16 Spring contexts, not the five the speed prompt aimed for: 14 classes need
  their own Jira instance, repository catalog or mocked beans. Sharing more would mean new
  fixture values or mocks in tests that use the real beans.
- factory-core was out of scope: 14 Spring classes, about 48 s of context startup and its own
  `reuseForks=false`.

## Left after the Developer Flow fixes

Source: reviews of the developer flow fixes (spec in commit 82e0d0d), 2026-10-28.

- An empty Acceptance criteria section, or one that says only "None", opens a ready PR. A
  possible draft reason: "The agent listed no acceptance criteria."
- `FACTORY_CLAUDE_CODE_MAX_TURNS` outside 1 to 500 now stops Factory at startup with a plain
  message. Before the fixes there was no upper limit.
- GitHub answers HTTP 422 when a draft PR is asked for in a repository without draft support.
  The delivery then ends as blocked, with no fallback to a ready PR.
- Factory never reads the draft state back from GitHub. After "Ready for review" on GitHub
  the UI still shows "Draft PR".
- Hashed `/webjars` URLs (chart.js on the dashboard) get no long `Cache-Control`.
- `factory-app/src/test/js/poll.test.js` is not run by Maven or CI.
- PR feedback round summaries now use the four report headings, and the PR feedback panel on
  the run page shows them flattened into one line.
- Transcript and activity writes run on the CLI output reader thread. A slow database can
  stall the pipe; after 2 minutes the attempt fails as `OUTPUT_UNREAD`. Up to 19 transcript
  entries wait in memory and are lost if Factory stops.
- The transcript page renders every entry of every run in one response.

## Left after the models file and run form (2026-11-03)

Spec: `docs/superpowers/specs/2026-11-03-models-config-and-run-form.md`.

- **Model per task.** Effort is chosen per task; the model is one per run. A model on each
  row was discussed and left out to keep the table readable.
- **Pi with a model choice.** Pi still takes its model and reasoning level from
  `FACTORY_PI_*`. The models file already accepts the Pi API types; Pi uses it only for
  prices and redaction.
- **Budget hard stop.** Max turns is now a hidden gate (200). A stop by spent money would
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
- **Coding timeout.** The GLM-5.3 run of MGRAPPS-21 on 2026-11-03 (effort high) opened its draft
  PR after 29 minutes, one minute before the 30-minute coding timeout
  (`FACTORY_DEV_FACTORY_TIMEOUT_SECONDS`).
- **Review findings left.** From the review of af08eb6 on 2026-11-03:
  - Claude Code always gets `--effort`, also for a gateway model whose provider may ignore or
    refuse it. Check with a real CodeMie run before changing it.
  - `flow-my-tasks.html` compares `row.problem().name()` with the string `'NO_REPOSITORY'`; a
    boolean on the row would keep enum names out of the template.
  - `TaskUiController` and `TaskListController` both define `FLOW_PAGE`.

## Seen in the live run of 2026-11-10

- **The agent's question is lost.** The GLM-5.3 run of MGRAPPS-64 (execution `70b3baee`) ended
  with a `FACTORY_DECISION_REQUIRED` block: the work of the ticket is no longer in
  mgr-applications, with a question and three options. Factory showed only "Development
  stopped: Candidate contains no changes" and the step "Clarify implementation" as completed
  in 0 s. The question is on no page and in no artifact, only at the end of the transcript,
  and the only action offered is a blind Retry. Not investigated in the code yet: the
  no-changes check seems to win over the decision request.

## Left out of the demo UX work of 2026-11-10

Spec: `docs/superpowers/specs/2026-11-10-demo-ux-requirements.md`. Each item is a cut taken to keep a
page as on its board.

- **My tasks.** The fix steps dialog of a task that cannot run is gone; "N cannot run here" shows the
  reason in one line and the task page still has the stored reason. "cost may be higher" and "cost
  unknown" are no longer printed in Runs: the cost carries the explanation as its tooltip. The last scan
  time is the tooltip of Scan Jira. A task added by key keeps a small Remove button, which board 1 does
  not show. The in-progress strip shows the first running task only.
- **The lost question.** The parser takes the first line that starts with `FACTORY_DECISION_REQUIRED:`
  and ignores any text after that line. The prompt still asks for a one-line answer; it was not changed.
  "Needs your answer" is read from the stop reason of the attempt (`TaskStops.asksForAnswer`), not from a
  stored state, so it needs no migration.
- **Task page.** The result chip of an attempt says "Failed", not a kind such as "Build failed" (board
  4): the stop reasons have no short kind yet; the full reason is the chip's tooltip and the status
  card's headline. "Cost and usage" moved into Technical details. The total time and the models of all
  attempts are no longer in the status card. The answer goes into the next attempt as its instructions:
  the answer first, then the question, then the agent's text before the question, cut to fit 8000
  characters. The agent's evidence is shown on the page but not passed on. A task page with a pull
  request, or one that waits, is not on a board: it keeps "Open pull request" and "PR feedback" or
  "Open live run" in the status card.
- **Run page while it works.** Activity stays oldest first with the last 20 rows, so "Show 35 earlier"
  and the newest-first order of board 2 are not built; the time limit ("of 30") is not in the status
  card. A tool call shows one row once it ends ("Read Foo.java · took 6 ms"); Pi reports no duration, so
  the time between start and end is used. Calls are paired only when the match is certain: when two
  calls of the same tool overlap and the end names no file or command, the end keeps its own row, as
  before. A call still running says "Running …", not "running now".
- **Run page with a pull request.** "checked N min ago" next to Check PR comments is not shown: the
  stored comments snapshot has no read time. The page head does not say "attempt 1" with its model and
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
  (`docs.z.ai (2026-09-19)`) but no URL, so the page writes them as text. A link needs a `url`
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
- **The README still says to trigger the Developer Flow from its page.** "Wait for Factory to
  become healthy" ends with "choose **Trigger flow**" on `/flows/dev-factory`, which refuses
  manual triggers; runs start from My tasks.
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
- **The whole-attempt deadline reason is still internal wording.** "the whole-attempt deadline expired before …; queue and human-review waiting are excluded from the budget" (`TaskGuard`) has no plain-words mapping and no Configuration link.
- **The run form's model refusal still names the config API word.** `ModelCatalog.problem` says "uses api openai-completions"; only the Configuration tab says the API in words.
