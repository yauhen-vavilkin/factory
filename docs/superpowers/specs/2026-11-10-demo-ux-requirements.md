# Developer Flow demo: UX requirements

Status: the owner approved all five boards on 2026-11-10, and the plan below was implemented
on 2026-11-10 and 2026-11-11 (then polished by `2026-11-11-console-polish.md`). This file is a
record of the agreed design, not a task list. The owner's current request wins over it and over
the mockups; do not redo a page to match them.

Mockups: https://claude.ai/artifact/TWgamEj8WopVNg1Vd8Kask (private canvas of the repo owner).
The source of the five boards is in `2026-11-10-demo-ux-mockups/` next to this file: plain HTML
with inline styles, to read as a layout reference.

## Implementation plan

For the implementation session. Base branch `m1-sprint-pilot`. The mockups are the canvas
above; the real sizes and colours come from `tokens.css` and `app.css`, not from the mockups.

In scope, in this order (each item is one commit, tests green before the next):

1. The red test `TaskLifecycleIntegrationTest
   .deadlineIsPersistedOnceWhenPreparationStartsAndNeverReset`: find whether the code, the
   test or the fixture is wrong and fix that.
2. Whole console: the collapsible menu rail with the remembered choice, the name "AI FOLIO",
   one cost format `$0.70`, one chip shape, the secondary button style, no `;jsessionid=` in
   the address bar.
3. My tasks (screen 1, board 1).
4. The lost question: `CodingProtocol.answer` finds the marker line anywhere in the final
   message; a task run that asks ends in the state "Needs your answer" instead of "Candidate
   contains no changes".
5. Task page (screen 3, boards 3 and 4), with "Start attempt N with this answer" passing the
   question, the answer and the agent's text before the question to the next attempt.
6. Run page while it works (screen 2, board 2).
7. Run page with a pull request (screen 4, board 5), and one comment from Factory on the pull
   request after a round, with the commit and the agent's summary. This needs one new write
   in `PrFeedbackGateway`; an unconfigured GitHub connector skips it and audits
   `CONNECTOR_SKIPPED`.

Not in scope: pausing and resuming a coding session, a reply per review comment, updating the
pull request text, the Configuration, Status, Executions and Dashboard pages, the pull request
text and commit titles, anything in `factory-core`. Write what you find on the way to
`docs/open-questions.md` instead of fixing it.

Rules that are easy to break: the Developer Flow invariants in `AGENTS.md` (secrets, hidden
reasoning, step outputs in `dev-factory.yaml`, `FeedbackArtifacts.VERSION`); page rows are
records; every new page text gets an assertion in `UiRenderSmokeTest`.

Done means: `mvn verify` passes, `node --test factory-app/src/test/js/*.test.js` passes, the
app is rebuilt and each of the five pages is opened in a browser and compared with its board.

## How we work

- The owner and the lead agent agree on each screen on the canvas, one screen at a time.
- Each agreed decision is written here at once. This file is the handoff for the
  implementation run, which another model does after all screens are agreed.
- No code change before the owner approves the screen.
- Requirements start from what the engineer sees and clicks. A backend change is listed only
  when a screen needs it.

## What the owner wants from every screen

Taken from the owner's comments on the canvas. Check each new screen against this list before
showing it.

- Few boxes. No table inside a card next to another table. Lists are rows with thin lines
  between them, without an outer frame and without a header row when the cells explain
  themselves.
- Little to read. A label or a chip instead of a sentence. Grey sentences are a defect.
- Compact status elements, and only when they apply: no banner when nothing runs.
- The sources of truth are one click away from every list and page: the Jira key opens the
  Jira task, the pull request chip opens GitHub. Both look clickable (an arrow icon).
- Second-level information is behind a disclosure that looks like a small button, closed by
  default.
- Small details are consistent: one chip shape, one alignment, one cost format.
- Buttons stay buttons, in one size. One filled (primary) button per screen, for the main
  action of that screen; every other action is a secondary button with a light grey fill (the `--secondary` token, dark grey in the dark theme), not white on white. All buttons are small: 30 px
  high, 13 px text, and the buttons of one column have one width (96 px on My tasks). The
  owner found 36 px by 120 px far too big ("a helicopter cockpit"). Actions are never
  plain text links: the owner rejected that version ("everything merged into a mush"), and
  also the version with three filled buttons of different sizes ("a zoo of buttons").
- Change one thing per round. When the owner criticises one element, fix that element and
  leave the rest of the screen as it was.

## Decisions for the whole console

- The side menu can be collapsed to a narrow rail with one button that opens it. The choice is
  remembered. All flows stay in the menu: Factory is an engine for several flows, so My tasks
  does not become the home page.
- The product name in the UI is "AI FOLIO", not "AI Factory" (`fragments/layout.html`: the
  brand in the menu and the page title suffix).
- Mockups and the demo use the light theme.
- Cost has one format everywhere: `$0.70`. No "0.695676 USD", no "0 so far".
- Status chips have one shape everywhere: tinted background, border, a dot, a label. Only the
  colour changes with the state.
- Information of second importance goes under a disclosure, and the disclosure looks like a
  control (border, arrow, count), not like one more line of grey text.

## Screens

Order follows the engineer's path.

### 1. My tasks (`/flows/dev-factory`): agreed on 2026-11-10

- Tabs in the header row: My tasks, Definition, Configuration. "Check configuration" is no
  longer a separate button.
- In-progress strip, one line 40 px high, shown only while a run works: "In progress", the
  Jira key, the title, then model, effort and time. After a small gap, outside the strip, a
  button "Open run". The owner asked for this order: the state first, then the task.
- "Ready to run": a plain list, no frame and no header row. A row has a checkbox, the title,
  under it the Jira key as a link to Jira, "Jira: <status>" and the repository, and on the
  right "Effort" with its choices. The status filter chips are removed (the owner agrees).
- Tasks that cannot run: a closed disclosure button "2 cannot run here", a native
  `<details>` element without JavaScript; opened, it shows one line with the keys and the
  reason right under the button.
- The page reads from top to bottom in the order of the work (the owner's flow): get tasks,
  choose, start, watch. So the "Ready to run" header holds the ways to get tasks: the "Add by
  key" field with "Add", and "Scan Jira" (not in the page header). Under the list, on the
  right, come "Model" and the one filled button "Run N tasks".
- "Runs" (was "Triggered"): a plain list, newest first. It holds every started task: queued
  and in progress on top, finished below. The strip above is only a shortcut to the running
  task and does not replace its row. A row has the title (opens the task page), the Jira
  key as a link to Jira, cost and age, a status chip and one action button. When a pull
  request exists the chip is "Draft PR #6" or "PR #6" and opens GitHub.
- Actions by state: PR feedback (a pull request exists), Answer (the agent asked), Retry
  (failed).
- New state "Needs your answer". The owner calls this the best feature of the flow and wants
  it to work. Backend work: see "Backend changes".

### 2. Live run (`/executions/{id}`, while it works): agreed on 2026-11-10 (board 2)

The owner rejected a redrawn, minimal version of this page: the cost and usage panel was gone
and the page looked like a bare log. So the page keeps its present layout (status card, four
step cards, Activity, the "Runtime and usage" panel on the right) and gets only these fixes:

- Activity: one row per tool call (start and end merged), with the duration labelled, for
  example "took 6 ms". A bare "6 ms" in a column was read as "6 ms ago".
- Cost in the one format (`$0.23`) in the status card and in "Runtime and usage".
- "Technical details" is closed by default; the engine's 10 steps and its raw status stay
  inside it.

### 3. Task page (`/tasks/{id}`): the agent asks, and failed with Retry (boards 3 and 4)

One page in two states. Agreed on 2026-11-10.

- Head as on the run page, then one status card: the chip ("Needs your answer" or "Failed"),
  a headline, and the money spent in all attempts.
- The action section comes first, because the engineer comes here to act:
  - **The agent asks**: the question, the agent's short reason, the options as radio rows
    with "My own answer" last, an optional text field, then Model, Effort and the filled
    button "Start attempt 2 with this answer". There is no pause: the answer goes into the
    next attempt as instructions. The options come from the agent's
    `FACTORY_DECISION_REQUIRED` block.
  - **Failed**: "Start attempt N" with the optional field "What should the agent do
    differently?", Model, Effort and the filled button.
  - Both say: "A new attempt starts from a clean checkout and adds to the cost."
- "Attempts": a plain list, newest first. A row has the attempt number, how it started,
  model, effort, date, time, the instructions given, its cost, a result chip and "Open run".
- "Technical details" is a closed disclosure.

### 4. Run page with a pull request: PR feedback (`/executions/{id}`, board 5)

Agreed on 2026-11-10. The same page as the live run.

- The status card holds the chip "Draft PR #6" (opens GitHub), "Waiting for your review",
  the total cost and time. The four step cards all say "Passed".
- "New PR comments": "Check PR comments" on the right of the header; each comment is a row
  with a checkbox, the text, a link to the file and line on GitHub and the author; under the
  list the filled button "Fix N comments" with a note that it starts a round and adds to the
  cost.
- "Rounds": one row per finished round with the number of comments, the commit link, date,
  time, tests, the agent's summary, a link to Factory's one comment on the pull request, the
  cost and a chip. No per-comment "Done" or "Not done": the code does not have that data.
- "Verify my manual changes" is a secondary button with a one-line explanation (what it does
  is still to be confirmed in the code).
- Activity, usage and technical details are closed disclosures.

### 5. Pull request text and commits: not discussed, not in the demo work

### 6. Configuration (`/developer/configuration`): not discussed, not in the demo work

### The canvas after the last pass on 2026-11-10

Five boards in the order of the work, each with a note above it that says what the page is
and how the engineer gets there: 1 My tasks (agreed), 2 Run page in progress, 3 Task page
when the agent asks, 4 Task page when failed, 5 Run page with PR feedback. There are two
page types, built from the same blocks: the collapsed menu rail, the head (breadcrumb, title,
Jira key and repository), one status card, on run pages the four step cards, then plain
sections with a heading and thin lines, and closed disclosures at the bottom.

## Backend changes that the screens need

- **The agent's question must reach the engineer.** In the run of MGRAPPS-64 on 2026-11-10
  (execution `70b3baee`) the agent ended with a `FACTORY_DECISION_REQUIRED` block and Factory
  showed "Candidate contains no changes". Required: the run stops in a state "Needs your
  answer", the question and its options are on the task page, and the engineer's answer
  starts the next attempt. Factory never resumes a coding session (no `--resume` anywhere;
  even `DevelopContinuationWorker` starts a new coding run on a clean checkout), so the next
  attempt gets the question, the answer and the text the agent wrote before the question,
  to spend less on reading the code again. Details in `docs/open-questions.md`.

## Findings to place on a screen

From the walk through the console and the live run on 2026-11-10. Each one is decided when
its screen is discussed.

- Run page: "Could not complete" at the top and "Engine status COMPLETED" below; "Verify
  changes" and "Create pull request" stay "Waiting" after the run ended; raw text such as
  "NOT_RUN" and "feedback phase · INIT"; no action button on a failed run.
- Run page: the activity log prints each tool call twice, cuts long commands, keeps 20 events
  and never says what the agent concluded. Two step lists on one page (4 steps and 10 engine
  steps).
- After Run there was no link to the live run (solved by screen 1).
- Default effort for glm-5.3 is max (from `config/developer-models.yaml`).
- After "Add to list" the address bar shows `;jsessionid=`.
- PR feedback: no reply to the comments on GitHub, the PR text is not updated, the commit is
  "Address PR feedback (round 1)" without the task key, author `Factory <factory@localhost>`.
  When the agent skips a requested change, its reason is only in the transcript.
- PR feedback card: empty for about 20 s after the click, raw event names in its activity,
  addressed comments can be selected again, file and line are not a link.
- PR text: "Changed files" repeats "Changes"; hashes of the candidate mean nothing to a
  reviewer; 306 tests in one place and 300 in another; a title over 72 characters is cut in
  the commit headline.
- Configuration page: raw keys, a long list of PASS/WARN lines per model.
- Status page: Jira is "Not configured" while My tasks scans Jira.
- Executions list: raw status names and cut ids instead of the task key and title.
- Dashboard: many tiles and empty panels for a few runs.
- CI on the factory PR 6 is red: `TaskLifecycleIntegrationTest
  .deadlineIsPersistedOnceWhenPreparationStartsAndNeverReset`.

## How the mockups fit the code (checked 2026-11-10)

Read in the code, not run. The aim: the demo work changes templates and view classes, and
touches the engine and the flow as little as possible.

Templates and view classes only (the data is already on the page models):

- My tasks: `flow-my-tasks.html` already gets the Jira URL and status, repository, state
  label, detail, cost, age, pull request label and URL, retry flag, working and queued counts.
- Collapsible menu and the name: `fragments/layout.html` plus a few lines of JS next to
  `theme.js`.
- Task in progress: `execution.html`; the activity lines are built in one class,
  `DeveloperRuntimeActivity`, so merging "Running X" and "X completed" stays there.
- Failed task and Retry: `task.html` already has the attempts with model, result, cost and
  time, the total cost, and the retry form with model, effort and instructions. To check:
  whether the instructions of a past attempt are stored for display.
- PR feedback layout: `fragments/pr-feedback.html` and `FeedbackUi`.

Small backend fix, one class:

- The lost question. `CodingProtocol.answer` accepts a decision only when the final message
  starts with `FACTORY_DECISION_REQUIRED:` and is one line. In the MGRAPPS-64 run the agent
  wrote text before the marker, so the answer was read as a normal completion, and the empty
  change then failed in `CandidateFreezer` with "Candidate contains no changes". Fix: find the
  marker line in the final message. The question, options and evidence are then in the
  coding outcome, and `DeveloperExecutionView` already reads the question.

New behaviour, with a cheap cut for the demo:

- "Send answer and continue" on a task run. `DecisionGates.openOrStop` stops a task run on
  purpose instead of opening the review ("a task run records a stop instead of waiting").
  Waiting and continuing exist only for runs outside My tasks (`CodingDecisionGateWorker`,
  `DevelopContinuationWorker`) and inside PR feedback (action `ANSWER`). Making a task run
  wait touches the active slot and the attempt deadline. Cheap cut: the page shows the
  question and the options, and "Answer" starts the next attempt with the chosen option as
  its instructions. It costs one more run and needs no engine change.
- Replies on GitHub and "Done / Not done" per comment. `PrFeedbackGateway` only reads
  (`read`, `status`, `comments`), and the agent does not report a result per comment. Cheap
  cut: the round card shows the agent's summary of the round, and Factory posts one comment
  on the pull request with the commit and that summary. No per-comment chips.
- Updating the pull request text after a round: needs a new GitHub write. Not in the demo.
