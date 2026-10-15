# PR feedback in Developer Flow

Developer Flow 0.9.0 keeps the original execution open after publishing its PR.
The original implementation stages run once. The appended PR feedback stage
performs finite operations and waits for explicit operator actions between rounds.
Older executions skip the new behavior when resumed.

## Using the execution page

1. **Check PR comments** reads discussion comments, submitted reviews and inline
   comments from GitHub. Select the comments to address.
2. **Fix selected comments** starts one coding attempt against the current PR head.
   Factory independently checks the preserved candidate before publishing to the
   existing branch. No automatic comment resolution or PR merge occurs.
3. Answer a coding question to continue from the saved patch. For code failures,
   explicitly request another agent attempt. For environment failures, repeat
   checks or correct the environment. Patch and diagnostics remain internal
   artifacts and available through the existing read-only API.
4. After a manual branch update, **Verify my manual changes** independently checks
   the fresh head without calling the coding agent or creating an empty commit.
5. Factory checks the PR in the background. A confirmed merge resumes cleanup
   and completes the execution. Closing the PR without merge cancels the execution.
   An open PR keeps the execution waiting; there is no **Finish task** action.

Waiting does not keep a model running. Every owned workload must be removed before
normal waiting. If cleanup cannot be confirmed, only **Retry cleanup** is available.
A lost coding attempt is not automatically repeated. Its cost and last edits may
be unknown; a new paid attempt requires an explicit operator action.

Correction round cards preserve selected comments, the result, independent verification,
published commit, runtime, model usage and activity. The latest round is expanded.
Manual verification has its own card and does not increase the correction round
number. A terminal status does not replace the result of the last round.
The header totals coding attempts across initial implementation and PR corrections.
Rate-card estimates use the model and rates captured when an attempt starts.
Final token observations replace streamed observations for the same attempt.
Cost fields show the accumulated priced amount, including the latest saved usage of
interrupted attempts, without status text. Currencies are displayed separately.
Reported token totals remain visible even when pricing is incomplete. Missing data
is explained under Technical details; an unavailable estimate is shown as a dash,
not a zero charge.
The main runtime card shows only initial-run usage and cost. Factory time adds up
active step intervals and excludes waiting for a person. Rate sources are under
Technical details.

## Recovery and compatibility

- New first-publication branches use `factory/<issue>-<execution UUID>`. Existing
  branches are not renamed.
- Comments are versioned snapshots. A changed PR head or edited comment requires
  refreshing and selecting again. A repeated or stale action cannot start work.
- PR status checks validate the repository and base branch. The monitor scans at
  most 20 waiting executions every 30 seconds, rotating through pages and retrying
  GitHub errors with bounded backoff. It does not run GitHub calls on the engine
  scheduler thread. Failed status checks do not change execution state.
- Publication stores a reproducible commit before pushing. Retrying reconciles the
  saved SHA, including later manual commits. Divergence and deleted branches stop
  publication. Push never forces history.
- New behavior opts into a bounded core continuation result. Ordinary agents,
  normal reviews and Test Factory keep their existing behavior. There is no schema
  migration or scheduler change.
- Apply the application update only when development is idle. Before rolling back,
  finish or cancel new executions and confirm their resources have been removed.
  Preserve the database and execution history.

## Implementation boundaries

Workers orchestrate finite phases; `FeedbackState` owns phase transitions and keeps the
historical JSON field names and defaults. `FeedbackActions` keeps network reads outside
version-checked command transactions. `FeedbackCodingAttempt` preserves partial edits,
while `InitialCodingAttempt` is shared by initial development and clarification and
exports only completed candidates. `BaselineBuild` owns starting-build diagnostics;
`FeedbackResources` reconciles owned workloads before another operation can proceed.

`VerificationPolicy` compares receipts with the current trusted plan. Receipt identity
checks remain in `VerificationReceipt`, and publication phases recheck both identity
and policy. `PublicationGit` shares only command, identity and authentication mechanics:
initial delivery rejects conflicting branches, while feedback reconciles its saved
commit and permits later manual descendants. `PullRequestRenderer` separates untrusted
text from independent verification evidence.

The Pi adapter owns runtime setup and execution. `PiProtocol`, `PiUsage`, and
`PiProgress` separately validate final results, accumulate incremental usage, and
produce bounded activity. Progress is diagnostic and cannot authorize a result.
Execution pages explicitly select attempt-based or historical step-based usage.
Attempt events are interpreted once per request and reused across round cards;
`AttemptCharge` prices saved observations using captured rates. The historical
estimator retains its stricter all-or-unknown and zero-usage compatibility rules.

## Local verification

The stage is covered by `FACTORY_DOCKER_TEST=true mvn -B -ntp clean verify`,
including real-engine rounds against local Git and a Docker coding/verify/publish
scenario; the tests use no paid model and do not write to GitHub.
