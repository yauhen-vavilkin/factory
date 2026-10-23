# Factory product vision and autonomous sprint pilot

**Date:** 2026-10-23

**Status:** Draft for review. Product vision and milestone definition; not authorization to implement or run a paid campaign.

**Approach:** Develop the existing Factory, as selected in the product discussion.

## 1. Product promise

An engineer marks suitable tasks in a planned sprint and returns to their main work. Factory takes those tasks, prepares the working context, implements the changes, runs the required checks, and creates pull requests ready for the engineer's review. If a task cannot be completed, Factory records the reason and spending, then moves on. The engineer checks the dashboard when they have time, normally once a day.

Factory removes the work of supervising several small tasks: remembering which agent finished, requesting the remaining checks, updating the changelog, creating the PR, and tracking the result. The engineer keeps their preferred coding assistant for deeper and interactive work. Human review of a PR remains part of normal engineering work.

The first milestone produces a usable pilot that other engineers can try during a sprint. Its purpose is to learn whether the product reduces interruptions and unfinished coordination work without increasing review and maintenance effort enough to cancel that benefit. Commercial viability and broad task coverage are not prerequisites for this pilot.

## 2. Agreed direction and proposed defaults

The agreed direction is autonomous execution of explicitly delegated sprint tasks, understandable results and costs, isolated repeatable working conditions, and low configuration overhead. Jira is read-only for Factory. Initial delivery targets are personal forks. There are no external notifications. A failed task does not require an immediate human response. Private personal skills and the author's private workflow are not inputs to the shared product.

The following details are proposed defaults for review, rather than decisions already made by the user:

| Area | Proposed milestone default |
| --- | --- |
| Installation | One engineer, one local Factory installation, one Jira connection, GitHub.com delivery |
| Task source | One explicitly selected board and sprint, one delegation label, only tasks assigned to the connected engineer by default |
| Delegation label | Suggested value factory-ready; editable before activation |
| Jira status | Explicit eligible status selection, initially the board's To Do statuses |
| Discovery | Poll every five minutes while the app is running; a manual refresh only reads Jira |
| Scheduling | One active task at a time; queue order follows Jira rank when available, otherwise oldest creation time and issue key |
| Execution | The existing Claude Code/CodeMie profile with Java 21 and Maven; one required reference repository, proposed as mgr-tenant-entitlements |
| Enablement | Both configuration checks and an explicit bounded runtime check must pass for the active profile |
| Automatic retries | No second paid coding attempt for a stopped task; only bounded recovery of transient infrastructure operations |
| Bounds | Forty coding turns, a sixty-minute task deadline, and ten started attempts per installation day; exposed together as limits |
| Extra AI reviewer | Deferred from the required milestone; existing coding self-review and independent verification remain |
| PR comments | Existing manual feedback actions remain available; automatic comment processing is deferred |
| Configuration | Credentials in a local environment file; ordinary source and repository settings editable in the UI |

These values are deliberately visible. They can be changed during design review without changing the product promise. A daily attempt limit and time limit are not a hard dollar cap.

## 3. Approaches considered

| Approach | Benefit | Tradeoff | Decision |
| --- | --- | --- | --- |
| Develop the existing Factory | Reuse isolated execution, verification, usage accounting, history, and PR delivery; focus effort on autonomous intake and the engineer experience | Existing engine states need a clear product interpretation; old administrative screens must stop dominating the UI | Selected by the user |
| Build a separate small service around a coding agent | A smaller initial architecture and direct control of the task lifecycle | Rebuild or extract important verification, recovery, accounting, and publication behavior before testing the same product promise | Not selected |

Within the selected approach, the milestone changes product behavior and information hierarchy. It does not require a new frontend framework, a generic workflow builder, or a replacement coding harness. The existing engine remains an implementation detail. Necessary changes should follow its module boundaries rather than put Jira or Developer Flow rules in the generic core.

## 4. Milestone boundary

**M1 is ready for engineers to try when a new pilot user can configure a supported repository, preview and enable a sprint queue, leave it unattended, and later understand every task's outcome from one dashboard visit.** At least one task must reach a real verified PR in the user's fork, and unsuccessful tasks must stop cleanly without preventing later tasks from running.

The supported scope is intentionally explicit:

- One local user and one active installation for that user's intake scope. Several supported repositories may be configured; a task changes one repository.
- Public source repositories, GitHub.com forks, and Java 21/Maven repositories with a packaged and executable verification policy.
- Independent tasks with enough information to implement. Small story-point estimates alone do not establish eligibility.
- The local Docker setup is the pilot delivery target. Document the operating systems actually tested. Containerization is a portability mechanism, not evidence that every host platform works.
- Task completion means a usable PR was delivered, or a clear unsuccessful outcome was recorded. Merge and production release are outside Factory's initial obligation.

Backlog-wide automation, automatic sprint rollover, private source authentication, GitLab delivery, other build systems, parallel execution, multi-user coordination, cross-repository changes, dependency chains, and cloud hosting are deferred. These can extend the same product later; they are not claims about M1.

The proposed required reference preset is **folio-org/mgr-tenant-entitlements**, routed from MGRENTITLE tasks to the engineer's configured fork and base branch. Its current java21-unit policy runs `mvn -B -ntp test` and requires fresh executed Surefire reports. M1 must demonstrate the complete workflow on this preset. This is a proposed minimum acceptance target, not a claim that every change in this repository needs only unit tests: a task requiring additional integration evidence is outside this preset's supported task scope.

Other catalog entries can be offered after the same qualification, but are not prerequisites for this milestone. In particular, running a shared Eureka environment or supplying new integration infrastructure for mod-roles-keycloak is not included. Do not weaken an existing integration policy to make a repository appear supported.

## 5. First use and configuration

### 5.1 Four visible steps

1. **Start the local proxy.** The short guide names the supported proxy setup and provides a way to check that it is available.
2. **Provide credentials and start Factory.** Copy the small environment template, fill the Jira and GitHub access values, then run the documented Docker Compose command. The distributed pilot must include tested, versioned images or a single documented build path. Java, Maven, and the coding CLI are supplied inside containers.
3. **Complete setup in the browser.** Connect the sprint source and configure repository destinations using the forms below. The engineer does not edit Java code, workflow YAML, image definitions, or raw verification commands.
4. **Preview and enable.** Inspect the tasks Factory would take, their destinations, the selected profile and limits. Explicitly enable automatic processing for that saved configuration.

An incomplete setup opens Settings with a short list of remaining actions. A successful installation opens Tasks. No paid coding call occurs merely because someone opens a page, saves settings, or previews a filter.

### 5.2 What belongs in the UI

| Section | Ordinary editable fields | Information shown automatically |
| --- | --- | --- |
| Jira source | Board, sprint, delegation label, eligible statuses, Only my tasks | Connected identity, sprint dates, projects in scope, readable effective filter, last successful read |
| Repositories | Source repository, base branch, delivery fork, supported build profile | Matched Jira projects/components, destination identity, required checks, readiness and unsupported cases |
| Limits | Maximum started attempts per day and task deadline | Concurrency of one, coding turn limit, reset time, remaining daily allowance |
| Runtime | Supported profile selection if more than one validated profile is shipped | Harness version, literal model identifier, image identity, instruction bundle version, pricing basis |

For M1 there is one validated runtime profile, so there is no need to present a large model selector. Credentials remain outside the browser; the UI shows configured/missing/failed status and instructions for replacing the local values. It must not reveal tokens.

The proposed profile uses the existing packaged Claude Code 2.1.287 CLI and literal model identifier claude-sonnet-5-5 through the local CodeMie proxy, using Anthropic Messages and bearer-token authentication. The current container endpoint is `http://host.docker.internal:4001`. Record the tested image digest and exact profile in the pilot release. These are current project defaults to qualify, not runtime evidence produced by this design discussion. Pilot users need access to the configured CodeMie deployment and model; engineers without that access are outside this first distribution target. Supporting another provider is a separate qualification decision.

Repository presets provide the known mapping and checks for supported FOLIO repositories. The engineer confirms the base branch and their fork. Adding another supported Java/Maven repository uses the same form. Advanced repository policy can be supplied by the maintainer, but writing a new build profile is not a normal engineer onboarding step.

Selecting Java/Maven supplies a runtime and a starting policy. It does not silently imply that unit tests satisfy every repository's integration requirements. A repository requiring integration infrastructure is ready only when that supported verification environment is available.

### 5.3 Configuration authority

Use one persisted, versioned set of non-secret pilot settings. Packaged repository presets seed a new installation; after import, the UI-managed settings are the authority for this product flow. Environment variables remain the authority for secrets and deployment endpoints. Do not introduce silent merging between editable UI values and an independently edited YAML catalog.

Existing catalog files may be explicitly imported and previewed. Import is a settings operation, not a background overwrite. Show the configuration revision used by each attempt. Changes affect new attempts; an active attempt keeps its frozen execution profile and limits. Destination authorization is checked again before publication.

Changing source scope or publication destination pauses new starts and requires a fresh preview and enable action. Saving an unrelated display preference does not do this. Existing manual/Test Factory configuration remains separate and must not silently enable other flows.

### 5.4 Readiness has two levels

**Configuration checked** means Jira is readable, the filter is valid, mappings are unambiguous, the fork/base is accessible and authorized, Docker/images are available, and the verification policy is supported.

**Runtime verified** means this installed profile has completed a small bounded check from the actual coding container through the configured provider, including a tool call and usable usage reporting. Offer an explicit Run connection test action that clearly says it uses model tokens. It must not push, create a PR, or write Jira. Record the profile and time of this evidence and invalidate it when the runtime/provider configuration changes.

Both readiness levels are required before Enable automatic processing becomes available. Show the remaining action beside a disabled Enable control. The evidence must apply to the current runtime/provider revision, and the shipped profile must have a complete applicable pricing configuration. A source or destination change requires fresh configuration checks and preview; it does not invalidate an unchanged runtime check. A later runtime outage still pauses affected starts even if the initial check passed.

A TCP connection alone is not runtime verification. Missing pricing or incomplete usage must be visible; full estimated cost requires usable usage data and a complete applicable rate card.

## 6. How Factory finds tasks

### 6.1 Effective filter

The standard form represents this intersection:

> The selected board's scope, the explicitly selected sprint, the delegation label, the selected eligible statuses, and the selected assignee scope.

Repository support is checked after discovery. An issue that matches the Jira filter but has no valid repository mapping remains visible as Not eligible with a reason; it must not silently disappear from the preview.

The default Only my tasks uses the authenticated Jira identity. Turning it off broadens the preview and must be reflected before activation. Story-point thresholds and arbitrary JQL editing are not required for M1. The engineer's label is the delegation signal; Factory's checks still decide whether execution can start.

The UI shows board and sprint names and dates. Stable IDs are used internally so duplicate sprint names cannot select the wrong scope. A generated query is inspectable under Advanced, but the engineer is not required to write JQL. Jira supports selecting a sprint by ID and matching labels; the connector should validate the generated query against the connected instance. [JQL field reference](https://support.atlassian.com/jira-software-cloud/docs/jql-fields/)

### 6.2 Preview before activation

Preview is read-only and makes no model call. It displays every matched issue with its Jira link/title, repository and fork, status, and one of Eligible, Already processed, or Not eligible with a reason. No result can be described as the complete preview if fetching later pages failed.

Enable automatic processing applies to the displayed configuration revision, not just the rows present at that moment. Explain that future matching tasks in this same sprint will also be picked up. Showing the exact fork destination is part of this action.

The supported Jira Cloud search API uses paginated JQL search. Follow all returned pages; do not use a single page or assume the old offset-based search contract. Search results can lag recent changes, so verify the issue itself again before execution. [Jira issue search API](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-search/)

### 6.3 Polling and source changes

- Discover matching tasks every five minutes and on an explicit refresh. Only successful complete scans update Last successful sync.
- A failed Jira request produces a source warning with the last successful sync time. It must not turn the queue into an apparently empty successful result.
- Re-read each issue immediately before starting it. If its label, sprint, eligible status, or assignee no longer matches, show Removed from queue and do not start coding.
- Snapshot the task instructions, relevant comments, issue identity, configuration revision, and repository base for the attempt. The agent operates on that snapshot.
- Before publishing, recheck eligibility and material task input. If delegation was removed or the instructions changed, stop with Task changed during execution and preserve the candidate for inspection. Do not publish work against silently changed requirements.
- Closing the selected sprint stops new starts. A running attempt may finish against its snapshot if delegation and material input remain valid. The next sprint must be selected and previewed explicitly.

This is a fixed sprint pilot. A dynamic query that silently advances to every open sprint is not the default.

## 7. Queue and attempt behavior

### 7.1 One persistent task record

A task is identified by the Jira instance and immutable Jira issue identity. Its attempts and outcomes survive polling and Factory restarts. The visible issue key is a label for the engineer, not the only identity.

An unchanged delegation label never means Run again. A delivered, failed, stopped, or taken-over task is not automatically retried because it is found in the next scan, edited, moved to another sprint, or removed and later re-added to the filter. Only an explicit retry creates another attempt.

Do not rely on the engine's short deduplication window for this rule. Admission, scheduling, and publication recovery must reconcile persistent task/attempt ownership before starting a new paid invocation or creating a new PR. Recovery resumes or reconciles the same attempt; it does not blindly launch another one.

M1 does not coordinate two independent Factory installations. Each engineer must have one active installation for their intake scope. The default assignee filter separates pilot users, but is not a distributed lock.

### 7.2 Sequential work

One task performs active preparation, coding, verification, or delivery at a time. PRs awaiting human review do not occupy this slot. A failed task releases the slot. Queue order is visible, and stable ordering uses Jira rank when accessible or creation time plus issue key otherwise. M1 has no drag-and-drop reorder or parallelism controls.

Each task starts from the configured source/base revision available at its own start. An earlier task's unmerged PR is not an implicit base for the next task. A known unresolved prerequisite produces Not eligible or Could not complete with the relevant dependency; Factory does not build a chain of dependent PRs or guess that sequential scheduling makes dependencies safe.

### 7.3 Bounded work and failure

The task deadline covers preparation, coding, verification and delivery, with shorter stage limits where appropriate. Each new attempt also consumes the daily attempt allowance. No autonomous loop keeps rewriting the same failed task indefinitely. Infrastructure retry behavior must be finite, visible, and must not accidentally repeat a paid coding invocation.

A task-specific failure records its stage, reason, observed checks and usage, then allows the next eligible task to run. Ambiguous requirements and repository mismatch are terminal product outcomes for that attempt. They do not open a mandatory interactive conversation; the unresolved question is saved in the detail page.

A broken shared dependency, such as an unavailable model provider or invalid common credentials, pauses new affected starts with one installation-level explanation. A repository-specific problem blocks that repository while other ready repositories can proceed. This avoids spending time on predictable failures across the whole queue.

The daily limit resets using the installation's displayed time zone. Reaching it pauses new starts and shows the reset time; the active task remains within its own deadline. Dollar costs are estimates, and the UI must not describe these bounds as a guaranteed spending ceiling.

### 7.4 Pause, stop, retry and take over

| Action | Exact meaning |
| --- | --- |
| Pause processing | Start no new tasks; let the active task finish. Discovery may continue read-only. Persist the pause across restart. |
| Resume processing | Continue the existing eligible queue under its current approved configuration and limits. A changed scope requires preview first. |
| Stop task | Stop the owned workload and prevent later stages from publishing; retain the attempt, partial work and observed cost. If publication already happened, report the existing PR rather than pretending it was undone. |
| Retry | Fetch the latest issue and current base, explain the previous outcome, and create one new bounded attempt with retained history and cumulative cost. |
| Take over manually | Suppress further automatic work for this task and expose Jira, any existing PR/branch, and available changes/evidence. The engineer continues in their preferred tool. |

A retry after an uncertain publication first reconciles the known branch/PR and remote commit. It must not create a second PR because a previous response was lost. If existing user changes conflict with automatic continuation, stop with a clear explanation. Do not force-push, replace user work, or silently discard it.

## 8. Definition of a PR ready for review

The full initial task path is:

> Read current task and policy → prepare isolated workspace → check baseline → inspect and plan → implement and self-review → independently verify → publish the verified candidate → report PR ready.

The prepared context contains the Jira description and relevant comments, repository/base identity, relevant repository instructions, the supported contribution policy, required checks, changelog rules, and PR conventions. The shared instruction bundle is maintained specifically for Factory. It does not load the engineer's home-directory skills, personal agent memory, or private workflow.

The coding session may plan and run targeted checks internally. It must not require the engineer to request each step. The engineer's current IDE inspection is translated into documented repository checks and code self-review; M1 does not automate a desktop IDE or claim that Maven tests detect every quality issue.

Publication requires:

1. Changes address the selected task and stay within the authorized repository and base.
2. The repository's required checks have run successfully against the exact frozen candidate, with recorded evidence. Missing required integration infrastructure is a failure, not permission to substitute a unit-only check.
3. The contribution policy is handled, including NEWS/changelog updates when applicable. The report says why a required-looking deliverable is not applicable; there is no universal rule to add NEWS for every edit.
4. The PR has the Jira key and meaningful title, a concise explanation, validation performed and relevant limits. It links to the task and points to the configured fork/base.
5. The task report records the PR URL, delivered commit, actual verification summary, duration and cumulative estimated cost.

The coding agent's self-report is not independent verification. Passing checks also does not prove every business requirement. PR ready means ready for ordinary human review, not automatically safe to merge.

Only the trusted delivery path publishes. The coding workload does not receive authority to write Jira, publish to arbitrary repositories, merge, or deploy. A failed verification does not produce a normal ready PR. If code was preserved or a branch was pushed before delivery failed, its exact state is shown separately.

An extra AI review in a fresh context is a plausible later quality improvement. M1 does not require a configurable review-loop framework. If introduced later, it needs a finite pass/fix limit and all resulting code must be independently reverified before publication.

## 9. Engineer experience

### 9.1 Three primary surfaces

The primary navigation is Tasks and Settings. A task opens its detail page. Existing engine pages remain reachable through Diagnostics, rather than filling the primary navigation with Flows, Workers, Artifacts, Prompts and Audit.

This is an information architecture decision. It can be delivered with the existing frontend technology. Readability, clear outcomes and ordinary language are acceptance conditions, not optional polish after the pilot.

### 9.2 Tasks dashboard

The first screen answers What happened while I was away?

At the top show the selected board/sprint, processing state, last successful Jira sync, and Pause/Resume. Use four compact summaries: PRs ready, Could not complete, Working/Queued, and Estimated cost for this sprint. A connection problem or exhausted limit appears as a short actionable banner.

The task table contains:

| Task | Status and current activity | Time | Estimated cost | Result |
| --- | --- | --- | --- | --- |
| Jira key and meaningful title | PR ready | Finished 2 hours ago; worked 6 min | $0.84 | Open PR |
| Jira key and meaningful title | Could not complete — required checks failed | Stopped 40 min ago; worked 9 min | $1.12 | View reason |
| Jira key and meaningful title | Working — verifying changes | Started 8 min ago | $0.31 so far | View progress |
| Jira key and meaningful title | Queued — next | Found 12 min ago | Not started | View task |

The example values illustrate presentation only. Tasks and amounts are not pilot results.

Filters cover All, Working/Queued, PR ready, Could not complete, and Not started/Skipped. New results and failures are easy to locate without interpreting raw engine states. Search by Jira key/title and retained history allow the engineer to find a task after the sprint changes.

Relative times carry exact timestamps and time zones in details or accessible help. Monetary amounts are rounded for reading; the detail view preserves precision and basis. Long titles remain readable at normal laptop width. Meaning is conveyed by text as well as colour. UUIDs, numeric step indices and raw JSON belong in diagnostics.

Progress refreshes without requiring a manual page reload. Stale progress is labelled with the last observed activity; an absence of events is not automatically described as failure. The screen shows what Factory knows, rather than inventing a completion percentage.

### 9.3 Task details

The first screenful contains the task title/Jira link, result or reason, PR link when present, time spent and total estimated cost. Under that, show a short stage history: Preparation, Implementation, Verification, Publication. Distinguish Not run, Passed and Failed checks.

A failed task should read like this:

> Could not complete: the required integration tests could not start because the configured database was unavailable. Publication did not run. Estimated model cost: $0.84. Check the verification environment, then retry when convenient.

That message must be generated from observed state. A generic stack trace or Agent failed is not sufficient. The detail page provides the unresolved question when clarification is needed, plus Retry, Open Jira and Take over manually where applicable.

Attempt history groups original execution, explicit retries and later feedback work under the same task. Technical logs, runtime/image/model identity, token categories, candidate identity and engine artifacts are expandable. They support diagnosis without becoming the primary reading experience.

### 9.4 Product status and engine status

| Product status | Meaning for the engineer |
| --- | --- |
| Not eligible | Matched the source but cannot start; no coding was attempted and the reason is visible |
| Queued | Delegated and waiting for an execution slot |
| Working | An attempt is active; show its meaningful current stage |
| PR ready | Initial verified delivery is complete; the next task may proceed |
| Could not complete | The attempt stopped unsuccessfully; it will not restart automatically |
| Stopped | The engineer stopped the attempt; show any publication that already occurred |
| Removed from queue | Delegation conditions changed before execution |
| Taken over manually | Factory will not automatically continue the task |

These are task outcomes, not replacements for every engine state. In particular, an engine waiting for PR feedback must still display PR ready for initial delivery. A merged or closed PR is additional result information, not a prerequisite for freeing the queue.

## 10. Costs and repeatability

Show estimated model cost for each task and all its attempts, including unsuccessful work and manual feedback rounds. Each attempt retains its rate snapshot. Missing token categories or rates produce Incomplete estimate or Unknown; they must never become a displayed zero or an apparently complete total.

The sprint summary counts attempts started under that sprint/source revision. Task details show lifetime totals and the individual attempt allocation, including earlier sprints. A summary with missing amounts identifies that it is partial. Distinguish elapsed working time from time waiting for the engineer to review a PR.

The report covers model usage, not electricity, local compute, subscriptions or the cost of maintaining Factory. Actual provider invoices may differ from local estimates. No extra analytics product is needed for M1.

A versioned execution profile records the harness, image identity, requested model identifier, Factory instruction bundle and verification policy. Pin an exact provider snapshot where supported; otherwise record the requested identifier without claiming immutable model behavior. Preserve the profile used by each attempt.

Controlled conditions support regression checks and later comparisons. They do not guarantee identical outputs or remove the need to maintain instructions as repositories change. M1 uses one tested profile. A harness/model evaluation platform remains a later option.

## 11. PR feedback after initial delivery

The product vision includes the possibility of taking PR comments through another bounded implementation and verification cycle. The first pilot retains the existing manual actions to fetch comments, choose them, and update the PR. This is optional follow-up work; initial delivery has already completed.

Do not enable an unattended comment watcher as part of M1. Its later design must define which reviewers/comments can request work, which comments have already been addressed, how to handle conflicting instructions and manual commits, and a finite cost/pass limit. It uses the same verification and destination boundaries.

## 12. Existing foundation and necessary changes

The source baseline for this draft is clean main at b30da2511ed1ed4bcf89eb430a0484651317af0b. Existing project-wide architecture documents remain descriptions of the shared platform. This draft proposes a narrower Developer Flow product experience and does not change Test Factory behavior.

| Area | Existing foundation | M1 change |
| --- | --- | --- |
| Intake | Read one Jira issue by key; manual trigger | Paginated search, source preview, polling and persistent task admission |
| Configuration | Environment values, repository catalog and read-only preflight page | Limited editable setup, versioned authority and clear enablement |
| Coding | Structured context, inspection/planning/self-review, isolated runtime | Packaged contribution policy and unattended stop outcomes |
| Verification | Fresh-container checks of the exact candidate | Preserve evidence requirements and expose them in the task report |
| Publication | Authorized fork/base, verified-candidate checks, generated PR | Persistent recovery from ambiguous delivery and product completion at PR ready |
| Scheduling | Durable executions, bounded retries, concurrency setting | Sequential product queue, durable issue history and explicit retry semantics |
| Usage | Attempt accounting, rate snapshots and feedback totals | Task/sprint presentation with honest partial/unknown amounts |
| UI | Useful execution details plus administrative list/dashboard | Task-oriented dashboard, details and settings with diagnostics secondary |

The current code already releases execution capacity while waiting for PR feedback. M1 must expose that correctly, rather than treating it as a newly discovered engine defect. Existing short-lived trigger deduplication is not sufficient for permanent issue admission.

The running UI inspected during the discussion returned Not found for the configuration page, while the current source contains it. The pilot must run the delivered release and its matching configuration; do not assume the screenshots prove that a source feature is absent.

Relevant source anchors:

- [Developer Flow descriptor](../../../factory-flow-dev-factory/src/main/resources/flows/dev-factory.yaml), [Jira connector](../../../factory-connectors/src/main/java/org/folio/factory/connectors/jira/JiraConnector.java), and [trigger router](../../../factory-core/src/main/java/org/folio/factory/core/trigger/PipelineRouter.java).
- [Repository catalog](../../../config/developer-repositories.yaml), [preflight](../../../factory-app/src/main/java/org/folio/factory/app/developer/DeveloperPreflight.java), and [configuration UI](../../../factory-app/src/main/resources/templates/developer-configuration.html).
- [Coding protocol](../../../factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/runtime/CodingProtocol.java), [candidate verifier](../../../factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/verification/CandidateVerifier.java), and [candidate delivery](../../../factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/delivery/CandidateDelivery.java).
- [Execution capacity](../../../factory-core/src/main/java/org/folio/factory/core/engine/ExecutionClaimService.java), [PR feedback worker](../../../factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/feedback/FeedbackWorker.java), and [attempt usage](../../../factory-app/src/main/java/org/folio/factory/app/web/AttemptUsageSummary.java).
- [Current task details](../../../factory-app/src/main/resources/templates/execution.html), [execution list](../../../factory-app/src/main/resources/templates/executions.html), and [local setup guide](../../../README.md).

## 13. Milestone work areas

These are deliverable boundaries for later planning, not an implementation sequence or estimate.

| Work area | Finished result |
| --- | --- |
| Setup and supported profile | A pilot engineer can connect the supported environment and configure repositories through documented ordinary fields |
| Jira source and queue | Delegated tasks are discovered once, ordered, revalidated and processed within visible limits |
| Complete autonomous task | A task reaches a verified PR or an understandable stopped result without intermediate human prompts |
| Product UI and accounting | One dashboard visit explains every outcome, spending, freshness and any required action |
| Pilot readiness | A fresh install and the end-to-end scenarios below are demonstrated; a short guide and feedback questions accompany the release |

## 14. Acceptance before inviting pilot engineers

The milestone is complete only after these behaviors are demonstrated on the delivered build. These are required future checks, not results observed during this design discussion.

| Scenario | Required observation |
| --- | --- |
| Fresh setup | A new user with the required CodeMie access follows the short guide, configures the reference repository/fork, passes configuration and explicit runtime checks, previews the intended tasks and enables processing without reading source or workflow definitions |
| Successful task | A labelled eligible issue reaches a PR with required checks and applicable changelog work, without another prompt from the engineer |
| Failure between successes | A queue with success, ambiguous requirements, verification failure, and another success reaches the expected outcomes; failures do not block later eligible work |
| Repeat discovery and restart | Repeated polls and a restart do not create duplicate paid attempts or duplicate PRs; uncertain publication is reconciled |
| Changed delegation | Removing eligibility before start prevents coding; a material change during execution prevents stale publication and retains the evidence |
| Explicit retry | An unsuccessful task stays stopped until Retry; the new attempt uses fresh input and retains previous evidence and costs |
| Pause and stop | Pause survives restart and stops new starts; Stop affects the owned task and reports any completed publication honestly |
| Shared outage or daily limit | New affected starts pause with one visible reason; the queue is retained and tasks are not falsely reported as individually completed |
| Verification truthfulness | Missing, skipped, failed or stale required reports cannot yield a ready PR; a unit-only result cannot satisfy an integration requirement |
| Cost truthfulness | Success, failures and retries are counted; missing usage remains visibly incomplete; no double counting after recovery |
| UI comprehension | At normal laptop width, a pilot user can identify task, outcome, duration, cost, PR and failure reason without decoding IDs or opening logs |
| External boundaries | Jira receives no writes; no external notification is sent; publication targets only explicitly enabled forks; no merge or deployment occurs |

The release also includes a short troubleshooting guide for proxy unavailable, Jira access, repository mapping, baseline/check failures and incomplete cost data. Users can inspect their results after processing is paused.

For the pilot itself, ask a small group to use the product for one sprint. Collect which tasks they delegated, which results were useful after normal review, how often they had to intervene, where setup was confusing, and whether it reduced interruptions. Compare against their normal coding-agent workflow. Treat PR counts and pass rates as evidence inputs, not sufficient proof of usefulness. Actual task outcomes and review effort determine whether a later milestone is justified.

## 15. Decisions left to implementation planning

The approved product behavior should determine the implementation plan. The builder may choose the persistence schema, internal adapters, route names, UI components, polling implementation and test structure within existing repository conventions.

The builder may not silently change the delegation filter, add Jira writes, broaden publication authority, turn an explicit retry into an automatic retry, weaken required checks, import private personal skills, or replace unknown costs with zero. Those change the product contract.

Before implementation, review the proposed defaults in section 2 and the milestone boundary. Automatic PR feedback, extra AI review passes, evaluation infrastructure, cloud operation and broader repository support remain separate decisions rather than hidden additions to M1.
