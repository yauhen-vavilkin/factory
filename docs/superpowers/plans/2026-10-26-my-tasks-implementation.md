# My tasks: implementation plan

Spec: [`../specs/2026-10-26-sprint-jira-feature.md`](../specs/2026-10-26-sprint-jira-feature.md).
Pixel reference: [`../mockups/my-tasks-v3/`](../mockups/my-tasks-v3/flow.html) (static render of the
Claude Design canvas the operator approved on 2026-10-26). Branch `m1-sprint-pilot`. No commits
without the operator. Revised after the fresh-context critic review (ACCEPT WITH FIXES).

Principle: delete more than we add. No new classes unless named below with a reason. Core
(`factory-core`) is not touched. Each stage compiles and its tests pass before the next starts.

## Stage 1. Delivery destination without an authorization flag

- `DeliveryTarget(repository, baseBranch)`; `requireAuthorized()` → `requireValid()` (keeps the
  owner/repo format and non-blank base checks). Remove `authorized` from `DevFactoryProperties`,
  `DevDeliveryProperties` (legacy override), `FeedbackWorker.isDestinationAuthorized` (identity
  comparison stays), `DeveloperPreflight`, `DeveloperPreflightProbe`, the configuration page
  column, `config/developer-repositories.yaml`, compose, `.env.pilot.example`, README.
- Not done, by decision: `destinationFingerprint` hashes the routing only. A delivery-repository
  change after admission is not detected; the revoked-flag protection went away with the flag.
- Tests: drop flag-only cases, keep format cases, fix constructors (app + dev-factory).

## Stage 2. Pilot backend (one compiling step)

Source and scan:
- `PilotSourceProperties`: only `assignee`; `effectiveQuery()` = `assignee = "<name>" AND
  statusCategory != Done ORDER BY updated DESC`; `searchUrl(base)` = `<base>/issues/?jql=<enc>`.
  Blank → "Set FACTORY_PILOT_ASSIGNEE to your name as shown in Jira". Delete `PilotScopeResolver`.
- Jira connector: delete `parseSourceReference`, `resolveSource`, `getSprint`, `filterLink`,
  `SourceReference`/`ResolvedSource`/`Sprint` and their tests and fallbacks. Add `issuelinks`
  and `comment` to `SEARCH_FIELDS` (checked live: search returns them like GET issue).
- `PilotDiscoveryService`: always a bean; `sync()` only from the scan endpoint; delete
  `PilotDiscoveryPoller` and `discovery.*` properties; no automatic hooks, no authority
  fingerprint, no "known dependency" rule. Expose the task upsert for run by key.

Queue, admission, holds:
- Delete `PilotModeService`, `PilotAutomaticAdmission`, `POST /tasks/mode`, automatic-only
  repository methods, `refreshAutomaticInput`, `AllowedActions.enableAutomaticAdmission`.
- No sync gate and no observed-authority check in admission, retry or launch. Per-row staleness
  (version, input, destination fingerprints) stays for the list.
- Started work holds: retry and launch ignore `inScope`; the launcher never withdraws; the
  publication guard keeps eligibility, routing and destination checks but drops `inScope` and
  the input-drift check; `approvedRetryChange` keeps only the destination check. The agent
  already runs on the frozen `approvedSnapshot`.
- Queue order: `findDispatchable` by `a.createdAt, a.id`.
- `V12__pilot_my_tasks.sql`: seed `pilot_installation` row 1 if absent; `admitted_via
  'AUTOMATIC'` → `'MANUAL'`; drop `mode`, `accepted_authority_fingerprint`,
  `observed_authority_fingerprint`, `source_name`, `source_url`, `sprint_name`,
  `pilot_attempt.scope_fingerprint` (and `PilotExecutionContext.scopeFingerprint`).

Run by key (`POST /flows/dev-factory/run-by-key`, field `key`):
- Accept `ABC-123` (`IssueSnapshot.ISSUE_KEY`) or `<configured Jira>/browse/ABC-123` (same host);
  fetch with `searchIssues("key = ABC-123")`.
- Order: evaluate routing on the fetched issue → refuse when not eligible, no row written →
  in one transaction lock the existing task (if any), refuse "already queued or running" on a
  live attempt, upsert without touching `inScope`, reserve `INITIAL` (no attempts) or `RETRY`,
  `AdmittedVia.BY_KEY` → dispatch quietly. A unique-key or optimistic-lock race with a scan is
  reported as "Jira was being scanned, try again".

Push permission before admission (Run, Add to run, Retry):
- A private method on the admission path resolves the target through
  `DevDeliveryProperties.requireTarget(key, repositories)` and calls a public
  `DeveloperPreflightProbe.requirePushPermission(repository)` once per repository, outside the
  row transaction. No token or 401/403/no push → "Your GitHub token cannot push to owner/repo".

Mark as stopped (`POST /tasks/{taskId}/attempts/{attemptId}/mark-stopped`):
- `PilotOutcomeService.markStopped(attemptId)`: locks the installation, re-reads the attempt,
  requires `NEEDS_INSPECTION`, sets `STOPPED` with "Marked as stopped by the operator: <old
  reason>" capped at 500, clears the slot only if this attempt holds it, then dispatches.
  Absent execution bean → readable refusal. Test: the old execution cannot publish afterwards.

Safety:
- Same-origin check on every pilot POST (`Sec-Fetch-Site` same-origin/none, else `Origin`
  equals the request origin; requests without either header, such as curl, pass) → 403.
- `POST /flows/{id}/trigger` refuses `dev-factory` ("Developer Flow runs start from My tasks").
  The JSON API `/api/triggers/manual` stays (not reachable by a cross-site form).

Presentation model:
- Available = in-scope tasks without a non-withdrawn attempt (eligible and not eligible), no
  page clamp. Triggered = tasks with a non-withdrawn attempt, newest first, paged as today.
- "Next in line" from the `findDispatchable` order; Working row links to its run;
  `retryAllowed` and fingerprints on rows; `admittedVia` → "added by key".
- Queue waiting = the active attempt is `NEEDS_INSPECTION`.
- Poll revision includes the latest attempt `updatedAt`, so state changes reload the page.
- Error and success redirects go straight to `/flows/dev-factory`; Retry from a row returns
  there (`returnTo=flow`), from the summary to the summary.

## Stage 3. Pages (pixel-perfect to `my-tasks-v3`)

- Navigation without Tasks; `GET /tasks` → `/flows/dev-factory`; summary stays `/tasks/{id}`
  under Flows.
- `GET /flows/dev-factory` (no `view`) is served by `PilotTaskUiController` with the My tasks
  tab; `?view=definition` falls through to `FlowUiController` (`flow.html` gets the tabs for
  dev-factory and no JSON trigger button). No new controller class.
- Templates per reference: flow (`flow.html`, `queue-waiting.html`), summary
  (`task-failed.html`, `task-pr-ready.html`), run page developer branch (`run.html`); PR
  feedback fragment untouched. Status chips filter client-side; hidden rows are unchecked and
  disabled so they never submit. Mark as stopped uses `confirm.js` and the layout dialog.
- CSS appended to `app.css`, tokens only (dark theme), sizes/paddings/radii/type equal to the
  reference measurements. The app shell (sidebar) stays as is; content origin equals the
  reference.

## Stage 4. Config and docs

`.env.pilot.example`: Jira URL prefilled, `FACTORY_PILOT_ASSIGNEE`, `FACTORY_CONNECTORS_GITHUB_TOKEN`,
`FACTORY_PILOT_EXECUTION_ENABLED=true`, optional fork lines. Compose, `ComposeConfigurationTest`,
`application.yaml`, README pilot section follow.

## Verification

- Per stage: changed test classes, then `mvn -pl factory-app -am test`; dev-factory module tests
  in stage 1; GitHub `/repos/{o}/{r}` stubs where admission runs.
- Pixel check: fixture-rendered pages vs reference at 1440 px with the text-anchored probe
  (content-relative boxes, type, padding, radius); then ~940 px, 375 px, dark theme.
- Final fresh-context critic on the full diff; `mvn verify` before handing back.

## Result (2026-10-26)

All four stages are implemented; `mvn verify` is the final gate. The pages were measured
against `my-tasks-v3` at 1440px with a text-anchored probe (box, font, padding, radius, colors
relative to the h1): every compared element matches. Deliberate differences:

- Copy: "Needs inspection" rows read "outcome not confirmed" and the waiting line says Factory
  "could not confirm how its run ended". The code keeps no cause for a held attempt, and the
  hold covers both unconfirmed cleanup and a lost publication response.
- The inline Mark as stopped… stays on one line (the reason text truncates); in the queue
  mockup it wraps and grows the row, which the agreed rule forbids.
- The run page technical details also list the evidence the old page had, only when present
  (verification result, delivery branch and commit, failure facts, provider diagnostics,
  starting build output, pricing), so failed runs stay debuggable.
- The app shell (sidebar, page padding) is unchanged; content width is capped at 1120px (task
  summary 1040px) as in the mockup, so content lands at the same x at 1440px.
- Sizes that the mockup takes from content-box sizing are reproduced literally: the 38px
  "Check configuration" link, the 454px usage box, the 554px confirm card, panel bases +2px.
- Token colors `--success` and `--destructive` now equal the design's #15803d and #b91c1c.

Fixed after the fresh-context critic review of the full diff:

- Retry re-reads the task from Jira at the click (best effort, same Jira only), so a task added by
  key or not rescanned retries on what Jira says now; a task that now routes to another repository
  is refused rather than pushed to a repository the token check did not cover.
- Mark as stopped on a run that wrote a publication intent records that a pull request may already
  exist (branch and repository); the summary then says to check the repository instead of "No pull
  request was created", and the confirmation mentions it.
- A refused dispatch (execution off, retention on, failed preflight, Docker down) shows as "Queued
  tasks are not starting" above Triggered until a dispatch succeeds.
- V12 withdraws work queued by the removed automatic mode, takes every old team task out of My
  tasks and resets the scan state, so nothing starts without a click; covered by a migration test.
- Available is scoped to the configured Jira; "added by key" survives a Retry; working and queued
  counts cover the whole queue; the live-refresh baseline is read before the page data; the outcome
  release locks the installation before the attempt, the same order as mark as stopped.
- README no longer describes the removed delivery authorization flags.

Left as is: the same-origin guard trusts the Host header (DNS rebinding). The JSON API has no
guard at all, so this is an app-wide follow-up, not part of this change.
