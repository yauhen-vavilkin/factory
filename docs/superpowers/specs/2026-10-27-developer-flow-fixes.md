# Developer Flow fixes after the manual run (2026-10-27)

This spec holds the **final decisions** for problems P1-P13 from the manual run and the
items added while we checked them. It is the only source for implementation.
`docs/superpowers/plans/2026-10-27-manual-run-review.md` is history (Russian, with
superseded options). Read it only for evidence; when it disagrees with this file, this
file wins. Out-of-scope ideas live in `docs/open-questions.md`; do not build them.

Branch `m1-sprint-pilot`. Work in five themes, in order, one commit (or a few) per theme.

## Rules for every theme

- Product postulate from `AGENTS.md`: simple, transparent, easy to configure. Extend existing
  classes and pages; a new package or layer needs a reason written in the commit message.
- UI text, code comments and commit messages: plain English, B2 level.
- Every error message a person sees says the cause and what to do, in plain words.
- Keep the existing visual system (`tokens.css`, `app.css`, existing components). No mockups
  are needed; the layout decisions are below.
- Do not show hidden reasoning anywhere. The sentinel tests
  (`DeveloperExecutionViewTest#rendersConservativePiLifecycleLabelsWithoutMessageOrReasoningContent`,
  `UiRenderSmokeTest#developerExecutionShowsLiveActivityAndHonestDeliveryOutcome`,
  `HIDDEN_REASONING_SENTINEL`, `HIDDEN_MESSAGE_SENTINEL`) stay and get new cases where this
  spec adds output (thinking blocks must never reach the transcript or the page).
- Secrets never reach the database, artifacts, logs, argv or pages. Everything that comes
  from the CLI goes through `ClaudeCodeCodingRuntime.redact`, and `redact` must replace the
  secret of every configured profile, not only the legacy `apiKey` / `authToken`
  (`ClaudeCodeCodingRuntime.java:37-42`).
- Do not change the outputs of steps in `flows/dev-factory.yaml` or
  `FeedbackArtifacts.VERSION`: the engine fails a step with undeclared or missing outputs
  (`ExecutionEngine.java:255-266`), and a version bump silently stops feedback on open PRs
  (`FeedbackWorker.java:83`).
- Flyway migrations are append-only. Before adding one, check the highest existing
  `V*__*.sql` number and use the next one. Never edit a migration that is already committed.
- Tests: this repo uses Surefire only (no Failsafe). Run one class with
  `mvn -B -ntp -pl <module> -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`.
  UI changes need content assertions in `UiRenderSmokeTest` (the only tier that renders
  fragments) plus the controller or view tests that already cover the page. Before the
  theme commit, run the tests of every module you touched.
- Update tests that assert old behaviour (for example `--bare` in argv); do not delete them.
- Do not touch the running Docker stack (`docker compose`), the `.env` file, or GitHub.
  The orchestrator rebuilds and runs live checks after all themes.
- Commit on the current branch, no push. End each commit message with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Theme 1: page loading and the Available counter (P1, P2)

P1: the Developer Flow page sometimes loaded without styles. Cause we can fix: `/css/*` and
`/js/*` are served without a version in the URL and without `Cache-Control`, so a browser can
mix a cached old `app.css` / `poll.js` with new HTML.

- Turn on Spring's resource chain with the content-hash strategy for static resources, so
  every `@{/css/...}` and `@{/js/...}` link gets a hash. Check every template and every
  script that loads another file by a hard-coded path; all must go through the hashed URL.
- Versioned URLs get a long `Cache-Control` (one year, immutable). HTML pages stay uncached.
- Test: a render test asserts the page links contain a content hash; a request for the
  hashed URL returns `Cache-Control` with a long max-age.

P2: on My tasks the counter said "Available 3" and Run looked active although all three
tasks were "Not eligible".

- The counter counts only tasks that can run. When some shown tasks are not eligible, show
  them next to it: `Available 0 · 3 not eligible`. Both server render
  (`PilotTaskUiController.availableShown`) and `poll.js` (`shownCount`) use this rule.
- The server renders Run and the header checkbox already disabled when no shown task can
  run (`flow-my-tasks.html`), so the page is correct before `poll.js` runs.

## Theme 2: runtime stream, transcript, activity and failure reasons (P6 reason, P7, P8, P9, item 9)

Today `Processes.run` keeps all stdout and stderr of the Claude Code process in memory, with a
16 MB total cap (`ClaudeCodeCodingRuntime.EVENT_STREAM_LIMIT`). Over the cap the process is
killed and the attempt fails as `RUNTIME_ERROR` with no reason. After the process ends, the
whole output is parsed a second time. Nothing of the conversation is stored. A Factory
restart loses everything.

### Stream (Claude Code runtime only)

- Add a streaming path for the Claude Code runtime. Leave `Processes.run` defaults and its
  other callers (git, baseline `mvn`, verification, Pi) unchanged.
- One parser reads each stdout line as it arrives and is the only source of the outcome.
  Remove the second parse. Keep only parser state in memory, not the raw output.
- No total output cap. Keep the time limit. Keep a cap for a single line (8 MB); it replaces
  both the 512 KB observer limit (`Processes.java:106-108`) and
  `ClaudeCodeProtocol.MAX_FRAME_LENGTH` (line 17). A line over the cap, a JSON line the
  parser cannot read, or an exception inside the parser fails the attempt with a plain
  reason. Lines are never dropped silently.
- Writes of activity events or transcript entries are best effort: a failed database write
  is logged and marks the transcript incomplete, it does not fail the attempt.
- Keep only the last 64 KB of stderr in memory, redacted. At most about 300 characters of it
  may go into a failure reason (`result_reason` is `varchar(500)`, `V5__pilot_tasks.sql:41`).
- `--include-partial-messages` stays for live activity. Partial deltas are not stored.

### Failure reasons (P6, item 9)

Every unsuccessful Claude Code outcome keeps a reason in plain words that says what happened
and what to do. Map at least: turn limit (say the limit; suggest more turns or another
effort), budget limit, provider error (keep the CLI result subtype and its error text,
redacted), time limit (say the minutes), one output line too large, the CLI exited with a
code, Docker error. Today the CLI subtype and text are dropped in
`ClaudeCodeProtocol.java:261-268`; keep them. The reason reaches `dev_coding_outcome.json`,
the attempt `result_reason`, My tasks, the task page and the execution page.

### Transcript (P9, decided: option 2)

- Store a transcript for each coding run, written to the database in small batches while the
  attempt runs, so it survives a Factory restart up to the last batch. One execution has
  several runs (initial, `continue-implementation` via `InitialCodingAttempt.retry`, each PR
  feedback round), so key the new table by execution id plus run id (reuse the `attemptId`
  that `InitialCodingAttempt.java:230` and `FeedbackCodingAttempt` already pass), with an
  ordered sequence. The runtime module has no database: the caller passes a transcript sink
  into `code()`, the same way it passes `progress`; the app module implements it.
- The foreign key to `pipeline_execution` uses `ON DELETE CASCADE`, or retention deletes the
  rows explicitly next to the other rows (`RetentionRepository.java:73-81`).
- First entry: the exact prompt text sent to the CLI (`CodingProtocol.prompt` result). Do
  not add a new artifact for it (see the rule about step outputs).
- Then, in order: assistant text messages, tool calls with their input, tool results, the
  final result event with usage. No thinking blocks. No partial deltas. Everything redacted.
- Tool results are cut to 8 KB each with a visible "cut" marker. Total transcript cap 5 MB
  per run; after the cap, store one entry "transcript cut, the attempt went on" and keep
  running. Hitting the cap never fails the attempt.
- New page: the execution's transcript, readable (prompt collapsed by default, tool calls
  with input and result, durations). Link it from the Activity header as "Transcript". The
  existing audit page keeps its own link, renamed "Audit events" where it was "full log".

### Activity panel (P7, P8)

- Time column: show `HH:mm:ss` only, never wrapped; the full date and time in the title
  attribute. Order: oldest first, newest at the bottom; change the header text from
  "newest first" to "oldest first" and move the highlight to the newest row.
- Tool events carry telemetry from the stream, matched by `tool_use_id`: tool name, a short
  input summary (Bash command, Read/Edit/Write path, Glob/Grep pattern, WebFetch URL), the
  result state, the error text when it failed, and the duration. Claude Code tool names are
  capitalised (`Bash`); label them correctly (today only Pi's lower-case names are known in
  `DeveloperRuntimeActivity.java:162-176`).
- Long runs: remove the emit cap of 200 events per attempt (`ClaudeCodeProtocol.java:18`).
  Activity events are append-only audit rows and the panel already shows only the last 20
  (`DeveloperRuntimeActivity.java:52-61`), so nothing else changes. Activity is rendered on
  the server; `poll.js` needs no change for P7.

## Theme 3: run profiles, effort, run form, instructions, `--bare` off (P10, P11, items 11, 12)

### Profiles

- Profile `env-default`, label "Configured in .env", is built **in code** from today's
  `DevRuntimeProperties.ClaudeCode` (all `FACTORY_CLAUDE_CODE_*` variables, same secret rule
  by `authentication` as `DevRuntimeProperties.java:183-185`). Its effort list is all five
  levels; its default effort is `FACTORY_CLAUDE_CODE_EFFORT`, and blank still means "send no
  `--effort`" (the CLI then uses `high`; show it as "high (CLI default)"). So an old `.env`
  keeps working without changes.
- Extra profiles live in a new file `config/developer-run-profiles.yaml`, wired like
  `config/developer-repositories.yaml` in every place that one is wired: the classpath copy
  in `factory-app/pom.xml:131-136`, the import in `application.yaml` lines 3-5,
  `SPRING_CONFIG_IMPORT` and the volume list in `docker-compose.yml`, and an override
  variable `FACTORY_DEV_FACTORY_RUN_PROFILES_FILE` (add it to `COMPOSE_ONLY` in
  `ComposeConfigurationTest.java:29`).
- A file profile has: `id`, `label`, `base-url`, `authentication` (`bearer-token` or
  `api-key`), `secret-env` (the name of the variable that holds the secret, resolved through
  Spring `Environment`; the secret itself is never in the file), `model`, `efforts` (list),
  `default-effort`, `max-turns`, `max-output-tokens`.
- Valid effort values are the CLI set `low, medium, high, xhigh, max` (today Factory rejects
  `xhigh`; allow it). Each file profile lists its own subset; `default-effort` must be in it.
- The committed file ships `glm-5.3` and `glm-5.3-flash`: `https://api.z.ai/api/anthropic`,
  `bearer-token`, `secret-env: FACTORY_ZAI_API_KEY`, `efforts: [low, high, max]`,
  `default-effort: max`, `max-turns: 100`, `max-output-tokens: 16384`.
- The default profile is `FACTORY_DEV_FACTORY_DEFAULT_RUN_PROFILE`; blank or unset means
  `env-default` (compose forwards unset variables as empty strings, so treat blank as unset).
- Pass `FACTORY_ZAI_API_KEY` and `FACTORY_DEV_FACTORY_DEFAULT_RUN_PROFILE` through
  `docker-compose.yml`. Document them, the profiles file and `FACTORY_CLAUDE_CODE_EFFORT` in
  `README.md`, `.env.example` and `.env.pilot.example` (new keys there must be commented out:
  `ComposeConfigurationTest.java:123` checks the exact key set). Fix the misleading
  `FACTORY_MODEL_REASONING_EFFORT` note: it is for the old Pi runtime only.
- A profile whose secret is empty is shown as "not configured" and cannot be chosen.
- Profiles apply to the `claude-code` runtime only. With the Pi runtime the run form shows no
  profile choice and Pi works as today.

### How the chosen settings travel (do not change this path)

1. The run form posts `profileId`, `effort`, `maxTurns` and, on the task page,
   `instructions`. The server validates them against the profile (effort in its list,
   turns 1-500, instructions up to 8000 characters) and rejects bad input with a plain
   message.
2. The attempt stores a snapshot: profile id, profile label, model, endpoint origin (no
   credentials), effort, max turns, instructions. Nullable columns or one JSON column on
   `pilot_attempt` via a new migration.
3. The snapshot travels in `PilotExecutionContext` (add a field), filled by
   `PersistedPilotAttemptPolicy.context` (`PersistedPilotAttemptPolicy.java:70-74`).
   `IntakeResolveWorker` already calls `pilotPolicy.find` (line 70); it puts the snapshot
   into a new `CodingRequest` field `runSettings` (profile id, effort, max turns,
   instructions; no secrets). It is saved in `dev_coding_request.json` with the request. Do
   not add it to the trigger payload: `POST /api/triggers/manual` must not be able to set it.
4. `ClaudeCodeCodingRuntime` builds argv and the environment file from the request's
   `runSettings` and the profile it names (base URL, secret, model, max output tokens), not
   from one global config. A request without `runSettings` (manual API trigger, old data)
   uses the default profile with its defaults. If the named profile no longer exists or is
   not configured, the run fails with a plain reason that names the profile and says to fix
   `config/developer-run-profiles.yaml` or `.env`.
5. Every single-config call site follows the request: `identity()` becomes
   `identity(CodingRequest)` (used for the rate and `coding_starting` at
   `InitialCodingAttempt.java:229-231` and `FeedbackCodingAttempt.java:56-58`, which feed
   "Runtime and usage" in `DeveloperExecutionView.java:146-151`); `requireConfigured()`
   (`ClaudeCodeCodingRuntime.java:50`, `InitialCodingAttempt.java:340`) checks the chosen
   profile; `DeveloperPreflight.checkCodingConfiguration` (lines 181-192) checks the profile
   the run will use, so a broken `env-default` does not block a GLM run.
6. `FeedbackWorker` copies `runSettings` from the original `dev_coding_request.json`
   (around line 204), so PR feedback rounds run with the same profile, effort, turns and
   instructions.
7. Price comes from the model actually sent (the `model` in run metrics) and
   `config/developer-pricing.yaml`. The snapshot model is for display.

### Run form (UI)

- My tasks Run (several tasks): a compact row above or next to Run with Profile, Effort and
  Max turns. One choice applies to all selected tasks. Defaults: the default profile and its
  defaults. Effort options follow the chosen profile (render all options with a
  `data-profile` attribute and filter in the existing script; no new framework).
- Run by key: the same three controls.
- Task page: the Retry action becomes a small form in the Result card: Profile, Effort,
  Max turns, and a textarea "Instructions for the agent" (optional, plain text). Retry fills
  them from the previous attempt. This is the only place with the instructions field.
- Triggered's Retry button links to the task page form (`#retry`); it does not start a run
  by itself any more.

### Where the settings are visible

- Check configuration lists all profiles with per-profile checks: secret set, endpoint
  reachable (TCP, as today), rate card exists, effort list valid. Show effective effort.
- Execution page, "Runtime and usage": profile, model, effort, max turns.
- Task page Attempts table: profile and effort for each attempt (for example `glm-5.3 · max`),
  as a small second line in the Attempt column. This is the only place in that table.

### Instructions and the prompt (P11)

- `CodingProtocol.prompt` serializes the whole request as JSON (`CodingProtocol.java:26-28`).
  Inside `prompt()`, serialize a copy of the request without `runSettings`, so effort and
  turns do not reach the model and the instructions appear once. Do not use `@JsonIgnore`:
  the same mapping writes `dev_coding_request.json`, which `FeedbackWorker` reads back.
- The prompt gets the attempt's instructions as a separate, clearly titled block
  ("Instructions from the operator"), after the task data.
- The prompt states that Factory's constraints win over any repository instructions: do not
  commit, branch, push, open pull requests or change git configuration; Factory does that.
- The full prompt is the first transcript entry of each run (theme 2).

### `--bare` off and WebFetch (item 12)

- Remove `--bare` so the CLI loads the repository's own `CLAUDE.md` / `AGENTS.md`
  (CLI 2.1.287 reads `AGENTS.md` when there is no `CLAUDE.md`). Keep `--setting-sources ""`,
  `--strict-mcp-config` and the empty MCP config.
- Things `--bare` used to switch off must stay off where the CLI allows it by environment:
  auto-memory, background traffic (`CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1` is already
  set), auto-update. Find the exact variable names in the CLI binary
  (`grep -a` over `/usr/local/bin/claude` in image `factory-dev-claude-code:2.1.287-java21`)
  and set only ones that exist.
- Add `WebFetch` to the tool list and to `--allowedTools`.

## Theme 4: agent report, PR description, draft PR (P12, P13)

Decided: the simple version. No new "Needs input" stop.

- In `CodingProtocol`, replace the request for a free "final summary" with a short template
  for the final message of a completed attempt, four sections with these exact headings:
  `## Changes` (it also takes the "conventions you applied" line, `CodingProtocol.java:17`),
  `## Acceptance criteria` (one line per criterion: met / not met / not checked, with a short
  reason), `## Assumptions`, `## Could not read`. `acceptanceCriteria` in the request is
  always empty (`IntakeResolveWorker.java:178`), so the prompt tells the agent to take the
  criteria from the task description. Keep the prompt addition short (about five lines).
  `FACTORY_DECISION_REQUIRED` stays as it is; the template applies only to a completed answer.
- Factory parses the sections from the final message, before the 4000-character cut
  (`PullRequestRenderer.java:142`).
- `PullRequestRenderer` builds a readable description from them: real Markdown headings and
  lists, written for reviewers, not chat. Keep "Changed files" and the issue link. Text from
  the model is still sanitized: mentions, all URLs (as `prose()` does today,
  `PullRequestRenderer.java:207`) and HTML; keep that protection but stop flattening lists
  and headings inside the parsed sections. The first line is not a repeat of the Jira title.
- The PR is a draft when "not met" or "not checked" appears, when Assumptions or Could not
  read is not empty, or when the template was not followed (then the description shows the
  final message as readable text under "Agent report").
- Carrier: `DeliveryWorker` computes the draft flag and its reasons from
  `dev_coding_outcome.json` and writes them into `dev_delivery.json` (no new artifact). The
  existing-PR replay branch (`DeliveryWorker.java:204-214`) uses the same computation.
  `PilotOutcomeService` copies the flag and reasons to the attempt.
- `GitHubConnector` is shared with Test Factory (`TestFactoryFinalizerWorker.java:126`): add
  an overload with `draft` and keep the current 5-argument method.
- The UI shows "Draft PR" (neutral or warning tone, not the green "PR ready") on My tasks,
  the task page and the execution page, with the list of reasons on the task page.

## Theme 5: Triggered and Attempts tables (P3, P4, P5, P6 UI)

- Triggered (P3): Task cell shows the key on the first line and the title below it, clamped
  to two lines with the full title in the title attribute, then the repository line.
  Rebalance the grid (`app.css` around line 966) so Status, Cost and When get readable
  widths. Add a narrow last column for the action.
- "incomplete" under cost (P3, P6): explain it. Show "cost may be higher" with a title
  attribute that says some usage was not reported by the runtime.
- Retry (P4): in the action column, a normal secondary button "Retry" for tasks that can be
  retried, linking to the task page form (`#retry`). The status cell shows the badge and the
  plain-words reason under it, wrapping up to two lines, full text in the title attribute.
- Attempts on the task page (P5): oldest first (1, 2, 3), a new attempt is added at the
  bottom (`PilotTaskUiController.java:168` reverses today). Triggered stays newest first.
- Attempts (P6): replace the small "run 8425…" link with a clear secondary button
  "Open run". The Result column shows the plain-words reason and what to do (from theme 2)
  and "Draft PR" from theme 4. Profile and effort stay in the Attempt column (theme 3).

## Not in scope

Everything in `docs/open-questions.md`: runtime tuning (MCP, skills, global instruction
file, Confluence REST hint, effort measurements), resume after restart, `QUEUED` state while
running, steps shown as "Waiting" after the end, the CLI cost estimate, dead `.env` keys.
