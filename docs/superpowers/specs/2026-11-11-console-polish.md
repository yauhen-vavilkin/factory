# Console polish after the owner's walk-through (2026-11-11)

The owner clicked through the live console after the demo screens were implemented and
called the result "slop": disclosures that repeat the page, a spoiler that does not look
like one, chips and amounts that do not line up, a Configuration tab that is a raw dump,
no visible way to open the run of a failed task, a failure text that does not say what it
is. Three independent audits (navigation, measured pixels, content) and a live run of
MGRENTITLE-229 confirmed it. This file was the fix list for one overnight run.

Status: worked through on 2026-11-11; what stayed open is in `docs/open-questions.md`. This
file is a record, not a task list. The owner's current request wins over it and over the
mockups in `2026-11-10-demo-ux-mockups/`; do not redo a page to match them.

## Root causes

1. The run page was only partly moved to the approved design. It still has the old
   status card, step cards with numbered circles, Activity as a monospace card and a
   "Runtime and usage" card, so the console shows two visual systems.
2. The run page shows the engine's view (engine status, engine steps, execution history,
   raw enums) next to the four product steps, and on failed runs they disagree.
3. Nobody owns the failure sentence. Three layers add a prefix each and the result is
   shown word for word in three places.
4. "Technical details" holds whatever had no other place, so it repeats the page.
5. The task page, My tasks and the run page header do not refresh themselves.

## Rules for every item

- Keep the visual system: `tokens.css`, `app.css`, existing fragments. Light theme first.
  Buttons stay buttons. No new pages, no new packages without a stated reason.
- One name per thing. A **task** is a Jira issue. An **attempt** is one try at a task and
  has one **run page**. A **round** is one pass over PR comments. Do not show the words
  "execution" or "engine" on Developer Flow pages.
- Templates stay dumb: text is built in Java records, templates only print it.
- UI text is plain English at B2 level. An error says the cause and what to do with the
  controls the page has.
- Do not change step outputs in `flows/dev-factory.yaml`, `FeedbackArtifacts.VERSION`, or
  anything in `factory-core` for this flow. Secrets and hidden reasoning rules stay; keep
  the sentinel tests.
- Update the tests that assert the old text or structure; add `UiRenderSmokeTest` checks
  for new visible text. `mvn verify` and `node --test factory-app/src/test/js/*.test.js`
  pass before each commit.
- Do not rebuild or restart Docker. Do not push. Do not touch `doc/design-overview.md`.
- If an item cannot be done as written (the data is not there, a test shows the behaviour
  is intended), do the closest correct thing and say so in the report. Do not skip silently.

## Batch 1: My tasks and the task page

**1.1 Open the run of a failed task.** In a Runs row of a failed task the button is
"Open run" and goes to the latest attempt's run page, not "Retry" to `#retry`. "Answer"
(task page, `#answer`) and "PR feedback" (run page) stay. On the task page show the link
to the latest run in every state, with one label "Open run" (today it is hidden when a
retry is allowed, and is called "PR feedback", "Open live run" or "Open last run").

**1.2 Row title is a visible link.** `.dev-row-title` shows it is a link without hover
(underline on hover is not enough: add the same link affordance the breadcrumb uses, or a
persistent subtle underline). Same on Ready to run rows.

**1.3 One failure sentence.** One method owns the text of a failed attempt and returns a
short `headline` and a `reason`:

- headline: `Attempt 3 failed`
- reason: one or two plain sentences without layer prefixes.

Remove "Development stopped: " where it is added (`InitialCodingAttempt`) and
"Implementation failed: " in `TaskLabels.failureText`; strip both when rendering text
stored by older runs. Map the known causes where they are produced:

- output limit: `A command run by the agent printed more output than Factory accepts, so Factory stopped the attempt. Start a new attempt and tell the agent to limit command output, for example with "| tail -n 100".`
- time limit: `The agent did not finish in 30 minutes, so Factory stopped it. Start a new attempt with a lower effort or narrower instructions, or raise the time limit (see Configuration).` No environment variable names in this sentence.
- turn limit: `The agent reached its limit of N turns before it finished. Start a new attempt with narrower instructions.`
- anything else: the stored message without prefixes.

Use it in three places with one wording: the task page status card, the run page status
card, and the Runs row detail (first sentence only, one line, ellipsis by CSS, full text
in `title`).

**1.4 Task page status card.** Layout as in mockup 4: chip and headline on one line,
amount on the right (`$1.77` + muted `spent in 3 attempts`). Under it one line
`Reason` (muted label) + the reason sentence in normal weight, then muted
`No pull request was created.` The chip never wraps above the headline. Long text wraps
under the headline, not under the chip.

**1.5 Start attempt form.**
- The textarea is empty unless the developer typed instructions and the attempt was not
  started. Never prefill it from the previous attempt, the agent's question or its report
  (today MGRAPPS-64 shows about 2,000 characters of internal text and MGRAPPS-119 shows a
  test instruction). Placeholder: `For example: change only FolioTenantApiClient and its test.`
- The textarea has an accessible name (label `for`).
- Model and effort default to the configured defaults, not to the previous attempt's.
- Under the controls, one muted line replaces the current note:
  `A new attempt starts from a clean checkout and adds to the cost. Effort is how long the model thinks at each step: higher is slower and costs more.`
- On My tasks the per-row effort control gets the same sentence as `title`.

**1.6 Attempts list.** Kinds: `first run`, `retry`, `retry with your instructions`,
`answer to the agent's question` (drop "by key"). Amount, chip and button form three
straight columns: fixed-width amount column (right aligned), fixed-width chip column
(chips left aligned in it), fixed-width button column. Same column rule for Runs rows on
My tasks and Rounds rows on the run page.

**1.7 Remove "Technical details" from the task page.** Everything in it is in the URL, the
header or the status card. Move the destination to the header meta line:
`MGRAPPS-64 ↗ · mgr-applications → yauhen-vavilkin/mgr-applications · master`. The
incomplete-cost note stays as the `title` of the amount.

**1.8 "N cannot run here".** No disclosure. One muted line under the Ready to run list,
above the Model and Run controls:
`2 tasks cannot run here: KEYCLOAK-137, KEYCLOAK-82. Project KEYCLOAK has no repository in config/developer-repositories.yaml.`
For a project mapped to several repositories:
`…: project X maps to more than one repository in config/developer-repositories.yaml.`
The Model and Run controls stay right aligned on their own row.

**1.9 Add-by-key message** says the section name the page uses: "Ready to run", not
"Available now".

## Batch 2: the run page follows mockups 2 and 5

**2.1 Header** is the task page header: breadcrumb `Developer Flow / MGRAPPS-64 / Attempt 2`
(key links to the task page), h1, meta line with the key as `KEY ↗` link, repository,
`glm-5.3 · max`. No boxed key tag, no chip in the header (the chip lives in the status
card). Page `<title>`: `MGRAPPS-64 · attempt 2`. The side menu highlights Flows on the run
page and the transcript, like the task page. A run without a task keeps the short run id.

**2.2 Status card** as in the mockups: chip + headline on the left, amount and time on the
right (`$0.23 so far`, `5 min of 30` while it works; `$3.25`, `31 min` after). No
"Now · updates live" label, no pill tags. Headlines:
- working: the current step in words (`Implementing changes`)
- failed: `Attempt 1 failed`, then the `Reason` line of 1.3
- question: chip `Needs your answer` (or `Asked a question` when it was already answered),
  headline `The agent stopped with a question`, the question as text, and a primary button
  `Answer on the task page` (`/tasks/{id}#answer`) only while the task waits for the answer
- PR open: chip `Draft PR`, headline `Pull request #6 is open as a draft`, link `Draft PR #6 ↗`

**2.3 Where this attempt stands.** One muted line under the status card when the run is
not the latest attempt or has a next step:
- older attempt: `Attempt 1 of 3. Latest: attempt 3, Draft PR #14.` (link to that run)
- latest attempt failed: secondary button `Start attempt 4` (`/tasks/{id}#retry`)

**2.4 Step cards** as in mockup 2: four plain cards, title `1 · Prepare task`, second line
`Passed · 2 s` / `Working · 5 min` / `Failed · 31 min` / `Waiting` / `Not started`. No
numbered circles. A run that ended with a question shows step 2 as
`Stopped with a question · 14 min` in the warning tone, not `Passed`.

**2.5 Activity** as in mockup 2: a plain list (no card), rows in four columns: time, verb
(`Ran`, `Read`, `Edited`, `Searched`, `Fetched`, `Built`), target in monospace on one line
with ellipsis, duration (`took 9 ms`, `running now`, `failed · exit 1`). The target is the
first line of the command, never a heredoc body or stderr; the full text stays in the
transcript. Newest first, `Show N earlier` button, link `Full transcript` in the head. No
"Runtime log, oldest first · last 20 events · Audit events" line.

**2.6 Usage** as in mockup 2: a plain column `Usage` with Input, Cache read, Cache write,
Output, Total tokens, Estimated cost. Remove Provider, Runtime, Image and Max turns from it
(model and effort are in the header). The incomplete-usage note reads
`Some usage was not reported, so the real cost may be higher.` and shows only when true.
On a run with rounds the amount in the status card is the total, and the Usage column is
titled `First implementation` so the two numbers do not contradict.

**2.7 PR feedback** as in mockup 5. Texts:
- empty state: `No new comments. Add review comments on GitHub, then press Check PR comments.`
- while waiting, the status card says `Pull request #6 is open as a draft`, not
  "Waiting for your review", and shows no live marker.
- round summary: no `Agent:` prefix; the first sentence of the agent's Changes, cut at a
  word with `…`, never mid-word; if there is none, show nothing (not
  "Verified changes published to the same PR").
- `Round details`: the comments sent to the agent, the agent's report rendered as text
  (no raw Markdown marks), and `Checks passed: 300 tests.` Show the test output only when
  the checks did not pass. No Runtime, Models, Attempts, full commit or raw event ids
  (`tool_execution_start` …).
- `Verify my manual changes` keeps its label; its note reads
  `Runs the checks on commits you pushed to the PR branch yourself. No agent, no cost.`

**2.8 Technical details** (one disclosure, closed): checkout `repo@branch` and commit, the
starting build (command, result, output on demand), the checks (plan, command, exit code)
only when they ran, the delivered branch and commit, pricing source, and one line of links
`Transcript · Audit log · Artifacts (84)`. Remove Engine status, Engine steps, Execution
history, tree and patch SHA, `failure category NONE`, the Pull request row, the delivery
repository when it equals the repository, and every raw enum (`NOT_RUN`, `AWAITING_HITL`,
`DEVELOPMENT_FAILED`): map to words or drop.

**2.9 One disclosure look.** Every `<details>` on Developer Flow pages uses the same
summary (secondary small button with a chevron that points right when closed and down when
open, vertically centred with the text) and the same opened body: indented block with a
left border, tied to its summary. No opened content that reads as loose page text.

## Batch 3: pages update by themselves

**3.1 Task page** polls like the run page: the status card, the form area and the attempt
rows change without a reload when an attempt starts, ends, asks a question or opens a PR.

**3.2 My tasks**: the in-progress strip and the Runs row show growing time and cost while
the coding step works (the revision must change when the shown cost or minutes change),
and the row cost includes PR feedback rounds.

**3.3 Run page header/status chip** changes with the rest of the page when the run ends
(today it stays "In progress" until a reload). `so far` shows only while the run works.

## Batch 4: Configuration and Definition

**4.1 Configuration uses the Developer Flow header** with the tabs; no own h1, no
"Back to flow".

**4.2 Structure**, top to bottom, using only data the page has:
1. `Models` in two lists. `Ready to run (3)`: `glm-5.3 · default · api.z.ai · effort low, high, max · price from docs.z.ai, 2026-09-19`. `Cannot run (5)`: the model and only its failing checks as fix sentences, for example `codemie-sonnet-5.5: Factory cannot reach http://host.docker.internal:4001. Start the CodeMie proxy or fix the base URL in config/developer-models.yaml.` A model whose endpoint is not reachable is in `Cannot run` and is marked `(endpoint not reachable)` in the run forms' model list.
2. `Where runs go`: Jira project, checked out from, pull request to, checks. Footnote on how to add a project. The one-line list of the same repositories is removed.
3. `Limits for every run`: `Time 30 minutes (FACTORY_DEV_FACTORY_TIMEOUT_SECONDS, FACTORY_TASKS_EXECUTION_DEADLINE)`, `Agent turns 200`, `Coding image …`. Replaces the "Selected runtime" key dump.
4. `Tasks come from`: Jira site, assignee, how to change it.
5. `Factory can start runs` in one line when all environment checks pass; otherwise only the failing checks with their fix. No `PASS`/`NOT_RUN` rows.
6. `Check one task` (the issue-key readiness form) last, with one sentence on what it checks.

**4.3 Definition**: no "pending" state marks on steps, artifact names separated by ", ",
retry line `Step retries: up to 3, waiting 30 s, 2 min, 5 min` (today "30120300").

## Batch 5: framework pages, cheap fixes only

- Executions list: Developer Flow rows show the task key and the product status words
  (`Failed`, `Asked a question`, `Draft PR`) and link to the run page; drop the Step column
  for them.
- Reviews: Developer Flow PR-comment waits are titled `Waiting for PR comments: MGRAPPS-21`
  and link to the run page.
- Status: the Jira row says `Not configured for Test Factory sync. My tasks reads Jira through its own settings (Developer Flow, Configuration).`
- Workers and Status links to `dev-factory` open the Definition tab.

Not in this work: Dashboard counters and charts, Audit, Artifacts, Prompts, PR text and
commit titles. They go to `docs/open-questions.md`.

## Measured CSS defects (apply each with the batch that touches its page)

Measured at 1440 px, light theme, `app.css` line numbers as of commit `8ced6db`.

Shared primitives (batch 1, because every page uses them):

- **M1 Button column.** `.btn-col` is `min-width: 96px` (line 243), so "PR feedback" is
  103.9 px and shifts its chip 7.9 px. Make it `width: 96px; padding-inline: 8px`.
- **M2 Right cluster of a row** (Runs, Attempts, Rounds): amount, chip and button are free
  flex items, so the amount jumps up to 66 px with the chip's width. Use one grid for the
  cluster with fixed columns (amount about 64 px right aligned, chip about 136 px with the
  chip left aligned, button 96 px), same in all three lists.
- **M3 Row alignment.** `.dev-row { align-items: center }` (1153) puts the chip and button
  in the middle of two- and three-line rows. Align the cluster with the title line. The
  row meta stays on one line with an ellipsis (full text in `title`), also at 900 px.
- **M4 Status card** (1205-1217) reflows with the headline length (one layout on
  MGRAPPS-64, another on MGRAPPS-119). No wrap between the main part and the amount; chip
  `flex: none`; the headline wraps inside its own column.
- **M5 Disclosure** (287-299): summary weight 500 like buttons, hover fill, a chevron that
  is drawn the same closed and open; opened body: `margin-top: 6px`, padding `10px 12px`,
  1 px border, radius 6, subtle fill. `.run-extra` and `.details-inline` summaries
  (underlined text that looks like a link) use the same disclosure look.
- **M6 Textarea** `min-height: 5rem` (409) cuts the third line: use a whole number of
  lines (81 px) or let `rows` decide.
- **M7 One control size in the whole console.** `.btn`, `.input`, `.select` default to the
  30 px / 13 px size the Developer pages use (today 36 px / 14 px on Configuration, Audit,
  Executions, Artifacts filters, next to 30 px pagination buttons).
- **M8 Effort segments** (815): same border token as the select beside them, hover fill.
- **M9 Tabs.** `.dev-tabs a` changes weight when selected (600 vs 400), so the next tabs
  shift about 4 px. One weight in both states; selection is colour and underline.
- **M10 Section headings.** One size: 16 px. Run panel heads are 15 px, bare `h2` on
  Configuration are 21 px (browser default). `.dev-count`: no extra margin, weight 400.
- **M11 One content width** for all Developer Flow pages (today 1120 px on My tasks and the
  run page, 1040 px on the task page, so the right column jumps 80 px between pages). Use
  1040 px everywhere.
- **M12 Collapsed menu.** `.main { margin: 0 auto }` leaves an 85 px dead gutter when the
  menu is collapsed: content starts at the menu edge plus the page padding, as in the
  mockups.
- **M13 Active menu item** is invisible in light (fill #f4f4f5 on #f7f7f8): use a visible
  fill. The menu toggle and the theme button are 4 px left of the menu labels: align them.
- **M14 Dark theme.** Secondary buttons have no hover (`--border` equals `--secondary`):
  add a hover token.

Run page (batch 2):

- **M15** `.result-chips > *` (876-877) overrides `.chip` and turns the PR chip into a
  square tag. The rule goes away with the old result card.
- **M16** Rounds: the amount baseline is 2 px above the chip and title baselines.
- **M17** Opening "Starting build output" makes the whole page scroll sideways (document
  1440 → 2683 px): `.run-links` needs `min-width: 0`; long output scrolls inside its box.
- **M18** The step strip breaks at 900 px (doubled border, no divider between rows). The
  four separate cards of 2.4 wrap cleanly.
- **M19** List heads are 41 px with tools and 35 px without: one min-height.
- **M20** The two closed disclosures at the bottom sit in one row, 14 px apart, as in
  mockup 5; an opened one takes the full width.

Framework pages (batch 5):

- **M21** `.badge` (solid fill, 22.8 px, weight 500, raw enum text) and `.chip` (tinted,
  24 px, weight 600, dot) are two status shapes. `statusBadge` renders the chip look.
  `NEEDS_DECISION` has no style at all on Executions.
- **M22** The dashboard KPI "31,500,357" overflows its card at 1280 px.

## Batch 6: verification pass

After batches 1 to 5 are live: fresh screenshots of every page at 1440, 1280 and 900 px,
light and dark, with the menu expanded and collapsed; re-measure M1 to M22; walk the flow
My tasks → task → run → PR feedback and back. Fix what is left and repeat until a pass
finds nothing visible at a glance or on a second look.
