# Factory product vision and minimal sprint pilot

**Date:** 2026-10-23

**Status:** Revised design for review. Documentation only; no implementation, paid campaign or publication is authorized by this document.

**Approach:** Develop the existing Factory, as selected in the product discussion.

**Scope authority:** This revision incorporates the product discussion and the latest comments about filters, repository destinations, manual admission and retry. It replaces earlier M1 limits of one Jira project, a mandatory label, fork-only delivery and no retry. The browser mockups illustrate the direction; this document defines the required behavior. General UI settings remain optional.

## 1. Product promise

An engineer delegates suitable Jira work and returns to their main task. Factory discovers the work, prepares its context, implements it, runs the required checks and creates a PR in the configured repository. If it cannot finish, it records a clear reason and observed usage/estimated cost, then takes the next task. The engineer checks the results when convenient, normally once a day.

There are two ways to delegate: select matching Jira tasks on the dashboard and press **Run selected**, or enable automatic processing for a clearly configured filter. A label can narrow that filter but is not mandatory. In automatic mode with a label condition, the familiar scenario remains **label in Jira → PR or clear stopped outcome**. Without a label condition, matching the enabled filter is delegation.

The value is completing the routine workflow without repeated prompts to check code, handle NEWS, create a PR or remember an unfinished agent session. Engineers keep their preferred coding tools for deeper work. Ordinary human PR review remains part of engineering; the pilot measures additional review effort and interruptions relative to that normal workflow.

The first milestone is a usable pilot for a few engineers during one sprint. It tests whether delegation saves attention. General repository support, a full configuration product and proof of commercial viability are later questions.

### Four mandatory product requirements

1. **Simple first use.** A developer seeing Factory for the first time can deploy and configure it independently through a short, prepared startup path. Few required values, useful defaults and actionable errors are part of M1.
2. **Autonomous daily operation.** Once a task is admitted, it reaches a PR or a clear stopped result without intermediate prompts, stage supervision or routine maintainer intervention. Task-specific failures do not need an immediate response and do not block later work.
3. **A friendly, readable UI.** Clear typography, result hierarchy, task-oriented wording and simple navigation are acceptance requirements. Engineers should not need to understand execution IDs or engine internals.
4. **Preserved tokenomics.** Existing token counts, usage breakdowns, estimated costs, totals and indicators of incomplete data remain available, including unsuccessful work and repeat attempts.

General configuration through the UI is optional. The required mode switch, task checkboxes, Run selected and Retry are operational controls, not an optional settings editor.

## 2. The smallest useful M1

**M1 is ready when a first-time engineer can configure a team's sprint source, choose manual or automatic admission, and later understand each result through a friendly UI with the existing tokenomics intact. Execution needs no supervision between admission and the PR or stopped outcome.**

The supported shape is one engineer, one local installation with persistent storage, one team source and one explicit sprint. That source can contain several Jira projects and route independent tasks to several prepared repositories. Each task targets exactly one repository, and tasks run sequentially. M1 does not coordinate separate installations.

For the pilot, only one installation may process an overlapping Jira scope. Independent engineers use non-overlapping filters, for example by assignee, or share responsibility for one processing installation. The setup guide must state this limitation: local deduplication does not detect or prevent another installation taking the same task. Distributed locking is outside M1.

Reuse the existing repository catalog. Qualify at least two prepared project-to-repository mappings so multi-project intake is real, not a future promise. Proposed reference presets are folio-org/mgr-tenant-entitlements and folio-org/mgr-applications with the existing Java 21/Maven java21-unit policy: `mvn -B -ntp test` and fresh executed Surefire reports. These are qualification candidates, not new evidence of successful runs.

Only work whose required evidence fits its preset is supported. A task needing unavailable integration infrastructure stops; a preset must not weaken repository verification requirements. Other existing presets can remain available, but their presence in configuration does not prove pilot readiness.

### Required now versus deferred

| Area | Required for M1 | Optional or deferred |
| --- | --- | --- |
| Setup | Small commented template, prepared repository presets, one startup path with coordinated checks | General UI settings optional; setup wizard and live editing of all configuration deferred |
| Jira source | Team board or saved filter, explicit sprint and eligible statuses; optional assignee, project and label restrictions | General JQL builder, dedicated story-point rules, backlog mode, automatic sprint rollover |
| Admission | Switchable manual gate, default on for testing; checkboxes and Run selected; automatic mode for the configured scope | Rich approval workflows, per-stage approvals |
| Queue | One active task, stable ordering, durable initial admission and explicit retry attempts | Parallelism, manual ordering, dependencies, coordination across installations |
| Execution and PR | Existing isolated flow, repository instructions, required checks; configurable authorized official or test repository | New harness, general skill marketplace, new AI review/repair loops |
| Failure and retry | Clear stopped result, preserved evidence/costs, next task continues; explicit bounded Retry | Automatic retry loops, general resume framework, Take over/Cancel controls |
| UI | Readable task list and detail, clickable Jira keys, effective scope/mode/destination, required operational controls | New frontend stack, exact mockup reproduction, new charts/search suite |
| Costs and limits | Preserve existing tokenomics; current-attempt and cumulative task costs; bound each attempt | New daily-reset budgets, hard dollar caps and cross-sprint analytics |
| PR follow-up | Normal human review; retain existing supported feedback behavior | Automatic comment watcher, new feedback workflow or UI |

Jira remains read-only. The normal product destination is the configured official folio-org repository; personal forks are a configurable destination for our testing. No external notifications, merge or deployment is included. The engineer's private skills and personal workflow are not shared product inputs.

The user selected extending the existing Factory over building a separate service. Reuse its execution, verification, delivery and accounting foundations. The deliberate new work is intake, admission controls, explicit retry, destination support and the usable task presentation. Minimal M1 means bounding those features, not removing an agreed product requirement.

## 3. Setup: configure once, then delegate

### 3.1 One prepared startup path

1. Start the supported local CodeMie proxy.
2. Copy the pilot environment template. Set credentials/endpoints, the team's board or saved filter, the sprint, and the prepared repository/destination entries. Optional restrictions have explicit defaults. The testing example uses manual admission and personal fork destinations.
3. Run one documented Factory start action. It validates configuration and runtime prerequisites and reports the effective Jira scope, mode and repository destinations. It explicitly discloses a small bounded model check.
4. Open the dashboard. In manual mode, inspect the discovered tasks, select suitable ones and press Run selected. Enable automatic mode when ready to delegate all eligible current and future matches.

The guide must provide exact commands for the delivered build and versioned images or one documented build path. It must not require reading source code, editing workflow YAML, writing JQL, inventing verification commands or querying a database. Accept a board/saved-filter URL or ID and provide a direct documented way to obtain the sprint ID/name; a small helper command is sufficient.

Starting must not require a manual disable → separate checks → change flag → restart sequence. The startup action coordinates checks. On failure, show the named setting or dependency to fix; repeat the same action after correction.

Required values are connections, one team source, one sprint and the enabled prepared repository destinations. Eligible statuses come from an explicit editable default. Assignee, project allowlist and label are optional narrowings. Presets supply project routing, source repository, base branch, build environment and verification policy; normal users should not fill these from scratch.

Use the existing environment/catalog mechanism as configuration authority. A small persisted operational mode and admission history do not justify a second general settings database. Show effective non-secret configuration and save the configuration/input identity used by each attempt.

### 3.2 Team scope and filter fields

Use one existing team board or saved filter as the base scope. A team's work can span projects; neither the board's location project nor a guessed universal Jira Team field defines the whole scope. For a board, resolve its underlying saved filter. Show that source by name/link and use its query as the base, with the following additional restrictions:

| Field | M1 behavior |
| --- | --- |
| Team source | Required existing board or saved filter URL/ID; one source can cover multiple projects |
| Sprint | Required explicit team sprint ID, displayed by name; no automatic rollover |
| Eligible statuses | Required non-empty configured list, initially the prepared To Do status(es); not hard-coded to one project's status ID |
| Assignee | Optional: no extra restriction by default in the testing template; support authenticated user or specified account |
| Projects | Optional allowlist; empty adds no restriction to the team source |
| Label | Optional existing label; empty disables Factory's additional label condition |

All added restrictions use AND with the base scope. For example: team source + sprint + eligible status; optionally add “assigned to me” and an existing label. Neither a project nor an assignee restriction is secretly mandatory. Creating a new Jira label, custom field or shared filter is not a setup prerequisite.

Disabling Factory's label condition does not remove a label clause already present in the saved filter. Show inherited restrictions as part of the effective scope. Unsupported projects remain visible as Not eligible with a routing explanation; do not silently drop them or guess a repository.

The dashboard shows a plain-language scope summary, source link, sprint, optional restrictions, mode and last complete sync. Effective query details may sit behind an expandable section. Existing clauses in a saved filter can already express other conditions; M1 does not build a graphical query language, story-point adapter or team-field discovery system.

Resolve the source and validate the generated query against the connected Jira instance. An unreadable source, invalid status/sprint or failed query is a visible configuration/source error, never a fallback to all issues. Changing the explicit sprint uses the same configuration/start path. Backlog intake and automatic sprint advance are deferred.

Technical references: [board configuration and its filter reference](https://developer.atlassian.com/cloud/jira/software/rest/api-group-board/), [JQL fields](https://support.atlassian.com/jira-software-cloud/docs/jql-fields/) and [issue search](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-search/).

### 3.3 Repository routing and PR destination

A Jira project maps to exactly one enabled prepared repository preset. Several projects may route to the same preset. Missing or ambiguous mapping produces Not eligible and a concrete configuration hint; no paid work starts. Tasks spanning several repositories are outside M1.

Keep these identities explicit:

| Identity | Meaning and M1 behavior |
| --- | --- |
| Source repository/ref | Where the workspace starts; normally the official repository and configured base |
| PR target repository/base | Where the PR is opened; normally the corresponding folio-org repository, configurable for testing |
| Publication repository/branch | Where Factory pushes its owned branch; in M1 it is the same repository as the PR target |

The minimum delivery topology is a branch and PR in the same configured repository. Official delivery therefore requires credentials allowed to create a branch and PR there. A personal-fork test profile puts both the branch and PR in that fork. Fork-head → official-base PRs are a separate topology and are not required for this minimum pilot; an engineer without official branch permissions cannot use its official-delivery mode yet. State this limitation in setup, not after paid work.

The current code explicitly blocks folio-org delivery even though catalog defaults name official repositories. Removing that blanket restriction in favor of explicit configured destination authorization and validating the existing delivery path is necessary M1 work, not just a documentation/configuration change. Retain protections against force-push, overwriting user work and unrelated destinations.

Show the resolved destination before manual admission or enabling automatic mode and on task details. Testing must never switch to official repositories automatically. A permission failure must not trigger an alternative destination. Configuration changes must not redirect previously admitted work silently.

### 3.4 One supported runtime

The qualification target is the current packaged Claude Code 2.1.287 profile, literal model identifier claude-sonnet-5-5 and local CodeMie proxy, with Java 21/Maven. Record tested image identity, instructions and verification policy in release/attempt evidence. Engineers need access to the supported deployment/model.

These are design targets from inspected source, not new runtime evidence. Pinning the environment improves comparability but does not guarantee identical output or an immutable provider alias.

The startup path checks readable Jira scope, enabled repository mappings, source/base access, configured publication authorization and Docker images. Its explicitly disclosed, bounded check from the actual coding container verifies inference, a tool call and usable usage reporting. It creates no Jira writes or PRs and reports its usage/cost separately. A complete applicable rate configuration is required.

Prerequisites must pass before task execution. Discovery and preview can remain available with a visible execution blocker. Reuse valid runtime evidence for an unchanged profile/connection so ordinary restart requires neither another paid check nor approval. Recheck affected prerequisites after relevant changes. Opening the dashboard, syncing Jira and displaying unselected tasks never invoke a model.

## 4. Intake, admission and complete execution

### 4.1 Read-only discovery

Poll every five minutes while discovery is enabled. Follow all search pages; update Last successful sync only after a complete read. A failed or partial read shows a source problem, not an empty successful result. Preserve the previous result with a stale indicator and do not admit or start new work from an unvalidated partial scope.

Discovered tasks are not paid executions. Keep task identity, discovered input, routing/eligibility and admission state distinct from engine execution state. The list must show unsupported matches and the reason they cannot run. Discovery does not perform paid planning, coding or AI eligibility analysis.

### 4.2 A switchable manual gate

**Manual admission is required in M1 and enabled by default for testing.** “Optional gate” means the engineer can turn it off, not that implementing it can be postponed.

In **Review before run** mode, new eligible tasks appear as Awaiting selection. Nothing is preselected. The engineer ticks tasks and presses Run selected (N); this admits exactly those tasks to the persistent queue. Ineligible, already attempted or already queued/running tasks cannot be selected for another initial attempt. Failed tasks use the separate Retry action.

Show scope and per-task destination while selecting. If there is a select-all checkbox, it selects only the displayed page, with that meaning visible. Revalidate selected task identities, current inputs, scope and routing when submitting. If an individual row changed, leave it unadmitted with a clear reason; report which other selected rows were admitted. A global source/configuration problem prevents admission of the batch.

The manual choice covers the task's displayed requirements and destination. If these materially change before its first execution, return that task to Awaiting selection with an explanation. A changed queued Retry follows section 5.2 instead. Scope changes clear stale checkbox selections. Once execution starts there are no additional planning, checking or PR-publication approvals in the normal flow.

In **Automatic** mode, every eligible unattempted match is admitted automatically, including existing matches and future matches in the same sprint. Enabling this mode is one explicit action after showing the current filter, destinations and current eligible count. A label is not required; the UI must make the resulting delegation scope clear.

Switching back to Review before run stops further automatic admission. Already admitted queued/running tasks retain their delegation; show their count and explain this behavior. The mode switch is not cancellation. An unchanged restart preserves the selected mode and admission history.

A change to source scope, resolved saved-filter query, routing or destinations invalidates automatic admission authority: pause new starts, show what changed and return to Review before run. Revalidate not-yet-started work; affected initial admissions return for selection if still eligible, otherwise show Withdrawn or Not eligible. Affected retries return to their prior stopped result as described in section 5.2. Unaffected work can keep its recorded delegation. Active attempts retain their snapshot and must pass the publication recheck. No general hot-editing settings application is required.

### 4.3 Durable task and attempt identity

Identify a logical task by Jira instance and immutable issue ID. Persist admission and its attempt identity before paid work. One issue gets at most one initial attempt; only explicit Retry creates a subsequent attempt. Repeated polling, relabelling, editing, changing mode/sprint or restarting cannot rerun a previously attempted issue. The existing short trigger deduplication window is insufficient.

Run selected and Retry must be idempotent against double clicks, stale UI and restart after submission. If an admission exists, show that admission rather than creating another paid attempt. Keep prior attempts and cumulative costs attached to the logical task.

An unstarted task that stops matching the source is withdrawn from the queue. Before its first attempt it may become eligible again: automatic mode can readmit it, while manual mode requires a new selection. Never erase prior execution history to make a task eligible again.

Only one task executes at a time. Use stable creation-time/issue-key ordering; Jira rank and manual ordering are deferred. Each new attempt starts from the currently configured source base, never an earlier unmerged task PR. Known dependencies produce an unsupported/stopped reason instead of a dependency scheduler.

Re-read the issue before starting and snapshot the inputs actually passed to the agent. Manual admission and explicit Retry must still match the approved input/destination; an automatically admitted initial attempt can use current input within its unchanged authorized scope. Before publication, recheck task inputs, scope and destination authorization. Changed requirements, removed eligibility or an unavailable recheck stop publication and retain the result. Compare consumed input directly; no semantic change-analysis service is required.

### 4.4 Complete the workflow without intermediate prompts

Reuse the existing Developer Flow:

> Read task and repository policy → prepare isolated workspace → check baseline → inspect and plan → implement and self-review → independently verify → deliver PR → report result.

Shared context covers repository instructions, required checks, applicable NEWS/changelog rules and PR conventions. The engineer must not issue the remaining workflow commands. Desktop IDE automation and private personal skills are not required.

PR ready means the exact frozen candidate passed required independent checks and was published through trusted delivery. The PR contains a Jira reference, meaningful title, change explanation and validation evidence/limits. Coding-agent claims alone are not verification. Human PR review remains.

A failed required check produces a stopped result, not a normal ready PR. Missing integration infrastructure is not permission to substitute unit tests. Only trusted delivery publishes to the explicitly configured and authorized repository/base.

Delivery finishes when the PR is ready. Human review or merge does not hold the execution slot. Existing engine behavior already allows waiting for feedback without occupying a running slot.

## 5. Failure, explicit retry and operational bounds

### 5.1 Bounded attempts

Proposed defaults are forty coding turns and a sixty-minute deadline from preparation through delivery, for both initial and retry attempts. Preserve existing configured work limits and duplicate-work protection.

There is no mandatory installation-lifetime task quota or routine maintainer unlock. A fixed batch can bound a validation campaign without becoming a product activation limit. Setup checks are separately bounded. Existing configured limits must remain visible; M1 adds no budget-management subsystem.

These are work bounds, not a guaranteed dollar ceiling. A deadline must stop the owned workload or retain a visible blocked state until it is known inactive. Marking a row failed while its process keeps running is insufficient.

### 5.2 Ordinary failure and explicit Retry

Ambiguous requirements, unsupported work and failed verification produce a reason, last completed stage, checks and observed costs. Once the workload is inactive, the failed task stays stopped and the next eligible task proceeds. Engineers can inspect it later, clarify it and retry, or finish it in their usual tool.

**Retry is required in M1.** It is an explicit action on a terminal unsuccessful task, not an automatic loop. Before admitting it, re-read Jira, validate the current source restrictions and repository setup, and use fresh requirements and the current configured source base. Display the current destination and previous result/cumulative cost. If the task or destination changed since the displayed view, refresh it and require a new deliberate click.

Retry itself authorizes one new bounded attempt in either admission mode; it does not need a second manual-gate approval. The task must still match the current configured filter and have an eligible repository. If it does not, explain the restriction instead of silently overriding it. A label is only necessary when the configured filter requires it.

If a queued retry's approved inputs, scope or destination change before it starts, withdraw that admission without running it. Preserve the withdrawn admission record, show the prior stopped result with the new explanation, and require a fresh deliberate Retry when eligible. Do not return an already attempted issue to initial checkbox selection. This rule applies in Automatic mode too; an explicit retry is not permission to execute subsequently changed requirements.

Reject or resolve duplicate/stale retry requests to the same admitted attempt. Disable Retry while work is queued/running, workload ownership is uncertain, or a previous publication may already exist. PR ready tasks use existing feedback/manual review, not Retry to manufacture a replacement PR.

Preserve all attempts, evidence and costs. Show the latest result with an attempt history and a cumulative task total. New attempts must not overwrite earlier failure evidence or reset spending to zero.

### 5.3 Shared problems and uncertain publication

A shared provider, authentication or environment failure pauses new starts with one installation-level cause. Fix the external cause and use the same startup path to recheck/continue; this does not automatically retry failed issues. No new per-repository circuit-breaker framework is required.

Reuse recovery already proven by the engine. If safe continuation cannot be established, freeze the attempt as Needs inspection. Before freeing its execution slot, establish that the previous owned workload is inactive; otherwise pause new starts rather than overlap workloads. Once inactive, the uncertain task can remain stopped while unrelated work continues.

Preserve known candidate, branch, remote commit and PR identities. If a publication response was lost, show that a PR may already exist. Retry is unavailable until inspection establishes the outcome. Finding a valid delivered PR records PR ready with its link; it does not create another PR. A proven incomplete delivery can use existing safe recovery, but M1 does not promise a new resume/reconciliation framework.

A new retry must never overwrite a prior branch or create a duplicate PR. Preserve old artifacts; if fresh work is allowed after reconciliation, use a distinct Factory-owned attempt branch. Do not reuse a branch when doing so requires force-push or overwrites user work. Uncertainty requiring maintainer inspection is exceptional, not a normal daily step.

### 5.4 Operational stop

No new Pause, Stop or Cancel buttons are required. Supply one documented way to disable new work through local configuration and a controlled restart/shutdown. Where possible, let active work finish within its deadline; interruption follows the ownership/uncertainty rules above.

A queued task leaving the effective Jira filter is withdrawn before starting. Removing the label has that effect only when label filtering is enabled. Changing admission mode alone does not cancel admitted work. These are not advertised as immediate cancellation controls.

## 6. UI: improve two existing screens

Keep the current server-rendered technology. Adapt the existing list/detail views to logical Jira tasks with execution attempts underneath. Retain technical pages for diagnosis and reuse the configuration page for read-only effective settings. No new frontend framework or standalone settings application is needed.

Readable typography, spacing, result hierarchy and simple navigation are mandatory. Renaming columns while leaving the screen difficult to scan does not meet M1. At normal laptop width, a first-time engineer must identify a result and the next useful action without learning engine terminology.

### 6.1 Task list

The first screen answers **What can run, and what happened while I was away?**

Show team source/sprint, effective restrictions, mode, destination summary and last successful sync. Include Review before run / Automatic, task checkboxes and Run selected (N). A shared outage or disabled processing has one plain explanation. Ordinary execution does not require keeping this page open.

| Information | Required presentation |
| --- | --- |
| Task | Clickable Jira key opens that issue in Jira; readable title/Details opens Factory's task detail |
| State | Awaiting selection, Queued, Working with stage, PR ready, Could not complete, Not eligible, Needs inspection, or Withdrawn |
| Repository | Resolved PR destination, so tasks from different projects are distinguishable before admission |
| Time | Relative start/finish and active duration; exact timestamp in details |
| Spending | Estimated cumulative task cost, explicitly partial/unknown; no-work-yet tasks are not mistaken for unknown usage |
| Action | Select eligible work, Open PR, View reason/details; Retry available from task detail |

Keys link directly to the configured Jira instance's issue page, not to internal execution routes. Ineligible rows have a disabled checkbox and a visible reason. No default selection, hidden selected tasks or colour-only meanings. UUIDs and raw step identifiers stay out of the main table.

Page refresh is acceptable. Reuse existing live progress without building a new update layer. New search/filter tabs, charts and summary-card layouts are not acceptance requirements. Preserve history through the existing list/detail navigation; do not hide completed work when it leaves the current Jira filter.

### 6.2 Task detail

Put title, clickable Jira key, result/reason, known PR link, destination, active duration and estimated cumulative cost at the top. Show Retry for an eligible terminal failure, with a clear explanation when unavailable. Below, present attempt history and existing evidence for Preparation, Implementation, Verification and Publication, distinguishing Passed, Failed and Not run.

An example failure:

> Could not complete: the task does not say whether a disabled module should be skipped or rejected. No PR was created. Estimated model cost: $0.84. Clarify the Jira description, then Retry, or continue in your usual coding tool.

This illustrates presentation, not an observed result. Actual reasons come from execution state. Plain cause/stage templates and saved agent questions are sufficient; do not add an LLM explanation service.

Expose logs and artifacts below the result. Retain partial work for manual continuation without building automatic export to another coding tool. An engine waiting for PR feedback displays PR ready for initial delivery.

### 6.3 Preserve tokenomics

Reuse existing attempt accounting and rate snapshots. Keep:

- Total reported tokens and existing input, output, cache-read and cache-write breakdowns.
- Estimated costs per attempt and existing cumulative totals, including unsuccessful work, retries and already-supported feedback rounds.
- Runtime/model and rate context, plus partial, provisional, unknown and unavailable-data indicators.

A compact list cost is sufficient when details offer an obvious path to the preserved metrics without raw-log inspection. Rearranging information is allowed; dropping metrics or totals is not. Adding logical task totals must not double-count feedback rounds or retries.

Missing usage/rates remain Incomplete estimate or Unknown, never zero. A known unstarted task can show Not started / no execution spending. Cumulative cost stays marked incomplete if any included attempt has incomplete data. Duration excludes human-review waiting. Setup-check usage/cost is separate from task execution; provider invoices may differ.

No new sprint analytics model is required. Compare old and revised views on the same execution data, including failures, incomplete usage, feedback history and retries, to demonstrate preservation.

## 7. What we deliberately do later

General configuration through the UI is optional. The prepared non-UI setup must already satisfy first use. Required task admission, mode and Retry controls cannot be deferred under this exception.

Deferred: graphical JQL/settings builders, dedicated story-point filtering, broad repository/build-system support, multi-repository tasks, parallelism, dependencies, cloud hosting, private source authentication, multi-user operation and automatic sprint rollover.

Also deferred: cross-repository fork-head/upstream-base PR delivery, automatic retry, takeover/export, configurable AI review passes, automatic PR-comment watching and comparative profile evaluations. Existing supported feedback remains; it does not become a new pilot completion dependency.

Mockups remain references for typography and hierarchy. Their editable Settings, Pause controls, summary cards and filter tabs are not extra requirements. Conversely, manual selection and Retry are now required because the user explicitly requested them.

## 8. Acceptance and the stopping point

These are future demonstrations on the delivered build, not checks run during this design update.

| Scenario | M1 completion evidence |
| --- | --- |
| Fresh setup | A first-time developer independently uses the short guide/template and one start path without source-code knowledge, maintainer help or manual flag/check/restart choreography |
| Startup problem | Missing/invalid prerequisites give a plain cause and correction; repeating the same start action rechecks them |
| Team scope and filters | One team source includes at least two Jira projects mapped to prepared repositories; sprint/status restrictions and optional assignee/project/label narrow correctly; empty optional fields add no hidden conditions |
| Independent pilot installations | Setup documents the one-processing-installation rule for overlapping scope and gives an assignee-filter example; no cross-installation deduplication is claimed |
| No new Jira metadata | Setup and execution work using an existing board/filter without creating labels, fields or Jira writes; inherited source restrictions remain visible |
| Manual gate | Matches appear without paid task work; selecting a subset admits only those tasks once; ineligible/changed rows explain why they did not start |
| Automatic mode | Explicitly enabled scope admits current/future eligible matches without intermediate prompts; label addition delegates when that condition is enabled, and no-label mode also works |
| Mode/scope changes | Returning to manual stops new automatic admission without secretly cancelling queued work; changed filter/destination cannot broaden authority or redirect work silently; unchanged restart preserves mode/history |
| Complete workflow | Tasks reach verified PRs with applicable changelog work and useful descriptions, or a clear stopped result, without remaining workflow prompts |
| Destination support | Normal profile resolves to explicitly authorized folio-org repositories and test overrides resolve to personal forks; setup checks required branch/PR access; destination and same-repository limitation are visible |
| Failure and next task | Success → task-specific failure → success preserves reasons/checks/costs and advances once the failed workload is inactive |
| Explicit retry | A corrected terminal failure starts exactly one fresh attempt on deliberate Retry; prior evidence/cumulative cost survive; stale/double submissions and running/uncertain/PR-ready states cannot create duplicate work; changed queued retry inputs require a new Retry in either mode |
| Durable admission | Repeated scans, relabelling, source changes and restart do not implicitly rerun an attempted issue |
| Interruption/publication | Existing recovery safely continues or visibly freezes the attempt; no overlapping owned workload, blind republication or replacement PR |
| Bounds | Per-attempt limits and shared-outage handling bound work without a lifetime quota or routine maintainer unlock |
| Friendly UI | At laptop width, a new user can select work, read outcomes and navigate list/detail/Jira/PR without decoding IDs or logs; Jira keys open Jira directly |
| Tokenomics | Existing counts, input/output/cache breakdowns, rates and incomplete-data indicators survive; failed/retry/feedback costs remain included without double-counting |
| External boundaries | Jira receives no writes/notifications; publication uses only the configured authorized destination; no merge/deployment |

Initial end-to-end trials use our personal forks. Official-repository support must be demonstrated through destination authorization/routing and connector tests; a live official PR is not authorized by this design and requires a separately agreed real task. Passing a fork-only trial while official delivery remains hard-blocked does not complete destination support.

Simple first use, autonomous work after delegation, friendly UI and preserved tokenomics are all release gates. Pixel-perfect matching and general UI settings are not. After these checks pass, give the pilot to a small group for one sprint and ask whether it reduced interruptions, which PRs were useful, what extra review/repair they needed and where setup failed. A feedback conversation or form is sufficient.

**Stop M1 when engineers can complete this scenario independently and the listed behavior is demonstrated.** Fix barriers to first use, unwanted supervision, unreadable results and accounting regressions before the pilot. Defer additional convenience features and decoration until feedback justifies them.

## 9. Implementation boundary and evidence

This is a product-scope update, not implementation. The original source baseline was main at b30da2511ed1ed4bcf89eb430a0484651317af0b; subsequent local documentation revisions through 8ee6278 did not change runtime behavior. This revision also checked the repository catalog and destination guard. No new runtime evidence is claimed.

Reuse coding, verification, accounting, queue and template foundations. Required additions are team-source discovery, persistent task/attempt admission, the switchable gate, explicit retry, official-destination support and readable UI controls/results. A necessary defect repair belongs within this contract; a general engine rewrite does not.

Relevant anchors:

- [Developer Flow](../../../factory-flow-dev-factory/src/main/resources/flows/dev-factory.yaml), [Jira connector](../../../factory-connectors/src/main/java/org/folio/factory/connectors/jira/JiraConnector.java), [trigger deduplication](../../../factory-core/src/main/java/org/folio/factory/core/trigger/PipelineRouter.java).
- [Repository presets](../../../config/developer-repositories.yaml), [startup checks](../../../factory-app/src/main/java/org/folio/factory/app/developer/DeveloperPreflight.java), [configuration UI](../../../factory-app/src/main/resources/templates/developer-configuration.html).
- [Coding protocol](../../../factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/runtime/CodingProtocol.java), [independent verification](../../../factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/verification/CandidateVerifier.java), [delivery](../../../factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/delivery/CandidateDelivery.java), [destination guard](../../../factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/delivery/DeliveryTarget.java).
- [Execution capacity](../../../factory-core/src/main/java/org/folio/factory/core/engine/ExecutionClaimService.java), [usage summary](../../../factory-app/src/main/java/org/folio/factory/app/web/AttemptUsageSummary.java), [task list](../../../factory-app/src/main/resources/templates/executions.html), [task detail](../../../factory-app/src/main/resources/templates/execution.html), [setup guide](../../../README.md).

Builders may choose storage, adapters and template details within existing conventions. They may not silently add Jira writes, automatic retry loops, publication outside configured authority, weaker verification or private personal skills. They may not trade away simple startup, autonomy, readability or tokenomics to reduce effort.

Proposed defaults and qualification presets are design choices, not claims that every value was dictated by the user. The user-required changes are explicit: multiple projects through team scope, optional label, configurable official/test destination, switchable manual admission, explicit retry and direct Jira links.
