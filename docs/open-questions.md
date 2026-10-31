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
  `FACTORY_MODEL_*` lines (runtime `claude-code`); delete them.
- The same cleanup removed `factory.dev-factory.delivery.targets` and the startup check that
  refused an old `authorized` delivery key. A private catalog that still has either is now ignored
  without a message: pull requests go to the entry's `delivery.repository`.
- A run that waits for a coding decision shows the `NEEDS_DECISION` status badge without a colour:
  `app.css` has no `badge-status-NEEDS_DECISION` rule, so `statusBadge` (`fragments/components.html`)
  renders a plain badge on `/executions` and on a run page without a task
  (`DeveloperExecutionView.runStatus`). Found in the cleanup (phase 6); a new rule is a visible
  change, so it was left.
- `docker-compose.yml` passes environment variables by an explicit list, so a new secret
  variable must be added there too. This includes the `secret-env` variable of every new run
  profile.
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

## Left after the Developer Flow fixes

Source: reviews of the developer flow fixes (spec in commit 82e0d0d), 2026-10-28.

- An empty Acceptance criteria section, or one that says only "None", opens a ready PR. A
  possible draft reason: "The agent listed no acceptance criteria."
- `FACTORY_CLAUDE_CODE_MAX_TURNS` above 500 now stops `env-default` runs with a plain reason.
  Before the fixes there was no upper limit.
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
