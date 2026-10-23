# Factory product vision and minimal sprint pilot

**Date:** 2026-10-23

**Status:** Revised design for review. Documentation only; no implementation or paid campaign is authorized by this document.

**Approach:** Develop the existing Factory, as selected in the product discussion.

**Scope authority:** This revision replaces the earlier, broader M1 definition in this file. The browser mockups illustrate the longer-term product direction; their settings forms, controls and summary cards are not M1 acceptance requirements. The product promise remains unchanged.

## 1. Product promise

An engineer adds a label to a suitable Jira task and returns to their main work. Factory discovers the task, prepares its context, implements it, runs the required checks and creates a PR in the configured fork. If it cannot finish, it records a clear reason and estimated spending, then takes the next task. The engineer checks the results when convenient, normally once a day.

The value is completing the routine workflow without repeated prompts to check the code, handle NEWS, create a PR or remember an unfinished agent session. Engineers keep their preferred coding tools for deeper work. Ordinary human PR review remains part of engineering; the pilot measures additional review effort and interruptions relative to that normal workflow.

The first milestone is a small usable pilot for a few engineers during one sprint. It tests whether delegation saves attention. Broad repository support, a polished configuration product and proof of commercial viability are later questions.

## 2. The smallest useful M1

**M1 is ready when an engineer can follow a short guide, enable one prepared sprint source, label suitable tasks, and later find a verified PR or an understandable stopped outcome for each attempted task.**

The minimum supported shape is one engineer, one local installation with persistent storage, one Jira project and explicit sprint, one prepared public source repository, one personal GitHub.com fork and one packaged execution profile. Tasks are independent and run sequentially.

The proposed reference repository remains folio-org/mgr-tenant-entitlements. The existing Java 21/Maven java21-unit policy runs `mvn -B -ntp test` and requires fresh executed Surefire reports. Only tasks whose required evidence fits that policy are supported. A task requiring unavailable integration infrastructure must stop; this scope does not weaken the repository's verification requirements.

### Required now versus deferred

| Area | Required for M1 | Deferred |
| --- | --- | --- |
| Setup | A small commented environment template, one prepared repository preset, a short guide and documented checks | Editable settings UI, setup wizard, catalog import, live configuration changes |
| Jira intake | One fixed project/sprint/assignee/status/label filter, complete polling and visible sync state | Board picker, general JQL builder, story-point rules, backlog intake, automatic sprint rollover |
| Queue | One active task, stable ordering, persistent admission and one attempt per issue | Parallelism, drag-and-drop ordering, dependency chains, multiple installations coordinating |
| Execution | Existing isolated coding flow, contribution instructions, required checks and verified PR delivery | New harness, general skill marketplace, automatic repair/review loops |
| Failure | Clear stopped outcome, preserved evidence and costs; task failures release the queue | Product Retry, Stop and Take over buttons; automatic recovery from uncertain execution/publication |
| UI | Improve the existing task list and detail templates; show the effective setup read-only | New frontend stack, full navigation redesign, charts, search/filter suite, exact reproduction of the mockups |
| Cost and limits | Per-task estimate, visible incomplete data, task deadline and a finite pilot attempt allowance | Daily-reset budgets, hard dollar caps, cross-sprint accounting and analytics |
| PR follow-up | Engineer reviews the delivered PR in their usual tools | Automatic comment watcher; new feedback UX or feedback work required for pilot completion |

Jira remains read-only. Initial PRs target personal forks. No external notifications, merge or deployment is included. The engineer's private skills and personal workflow are not shared product inputs.

### Approaches and the reduction

The user selected extending the existing Factory over building a separate service. This preserves its execution, verification, delivery and accounting foundations.

Within that approach, the earlier design required editable configuration, several new task controls and broader recovery behavior. This revision instead uses prepared configuration, two readable screens and conservative stopping on uncertainty. That is the proposed scope reduction in response to the user's request. It trades some onboarding convenience and recovery automation for a smaller implementation and support surface.

## 3. Setup: configure once, then use Jira

### 3.1 Four documented steps

1. Start the supported local CodeMie proxy.
2. Copy the pilot environment template and fill the connection credentials, target sprint and personal fork. The prepared preset supplies repository routing, base branch, build checks and ordinary defaults.
3. Start Factory with intake disabled. Run the documented setup check and the explicitly requested bounded runtime check. Inspect the read-only effective configuration and matching-task list.
4. Enable intake using the documented configuration flag and restart. Confirm that the dashboard shows the intended filter and fork.

The guide must provide exact commands for the delivered build. It must not require reading source code, editing workflow YAML, writing JQL or inventing verification commands. A small helper command is acceptable; a setup application is not required. The release must provide versioned images or one documented build path.

Ordinary editable values are limited to connection credentials/endpoints, sprint ID, fork destination, an optional base-branch override and the intake enable flag. Advanced bounds remain documented defaults. The prepared Jira project, eligible status and delegation label are explicit in the template and effective configuration; changing them is a configuration edit followed by restart, not a new UI feature.

Use the existing environment/catalog mechanism as the single configuration authority. Do not add a second settings database, import rules or runtime editing. Preserve the effective non-secret inputs used by each attempt using existing execution artifacts.

### 3.2 One supported runtime

The qualification target is the current packaged Claude Code 2.1.287 profile, literal model identifier claude-sonnet-5-5 and the local CodeMie proxy, with Java 21/Maven. Record the tested image identity, instructions and verification policy in the release and attempt evidence. Pilot engineers need access to the supported CodeMie deployment/model.

These are design targets taken from the inspected project, not new runtime evidence. Controlled conditions improve comparability; they do not guarantee identical output or an immutable provider model alias.

The setup check verifies readable Jira scope, repository/base/fork authorization and available Docker images. A small, explicitly invoked model check from the actual coding container verifies inference, a tool call and usable usage reporting. It makes no Jira writes or PRs. It reports its own token use/cost separately from task execution. A complete applicable rate configuration is required for the supported profile.

Both checks must pass before intake is enabled. This can be enforced through the documented startup/check path; no connection-test button or readiness state machine is required in the UI. Recheck after relevant connection/runtime/destination changes. Opening the dashboard or checking the Jira filter does not invoke a model.

## 4. Jira intake and execution

### 4.1 One explicit filter

The shipped template expresses:

> Configured Jira project + explicit sprint ID + delegation label + configured To Do status + authenticated engineer as assignee.

The proposed label is factory-ready. There is no board selector or arbitrary query editor in M1. The project narrows intake to the single prepared repository. The guide explains how to obtain the sprint ID and confirms the resulting sprint name/scope during setup.

The effective filter and destination are visible read-only. Enabling intake includes future matching tasks in the same sprint, not just the initial preview. Nothing silently advances to the next sprint. The engineer disables intake at the end of the pilot; changing the sprint requires the setup check again. A separate sprint-lifecycle monitor is deferred.

Poll every five minutes while enabled. Follow all search pages and update Last successful sync only after a complete read. A failed or partial read must show a source problem, not an empty successful result. The initial read-only match list may reuse the task table or a setup-command report; it does not require a preview modal.

Use the supported Jira search API and validate the generated filter against the connected instance. Technical references remain the [JQL field reference](https://support.atlassian.com/jira-software-cloud/docs/jql-fields/) and [issue search API](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-search/).

### 4.2 Minimal persistent admission

Identify issues by Jira instance and immutable issue ID. Persist admission and execution identity before launching paid work. Repeated polls, label removal/re-addition, task edits and application restarts must not create a second attempt for an already attempted issue. The existing short trigger deduplication window is insufficient.

A task not yet attempted can leave the queue if it no longer matches; it may become eligible again before its first attempt. Completed, unsuccessful and uncertain attempts remain recorded. Clearing history or changing the sprint is not a supported way to rerun them.

Only one active task runs. Use stable creation-time/issue-key order; reading Jira rank and manual queue ordering are deferred. Each task starts from the configured source base, never an earlier unmerged PR. Known prerequisites that make a task unsuitable are reported, not solved through a dependency scheduler.

Re-read the issue before starting. Snapshot the task inputs actually passed to the agent. Before publication, recheck delegation and those inputs. Removed delegation, changed requirements or an unavailable recheck stops publication and retains the result. A direct comparison of consumed input is enough for M1; no semantic change-analysis service is required.

### 4.3 Complete the task without intermediate prompts

Reuse the existing Developer Flow:

> Read task and repository policy → prepare isolated workspace → check baseline → inspect and plan → implement and self-review → independently verify → deliver PR → report result.

The shared context covers repository instructions, required checks, applicable NEWS/changelog rules and PR conventions. An engineer must not need to issue the remaining workflow commands. Desktop IDE automation and the author's private skills are not required.

PR ready means that the exact frozen candidate passed the required independent checks and was published through the trusted delivery path. The PR includes the task reference, meaningful title, change explanation and validation evidence/limits. Show the PR link and delivered identity in the report. Coding-agent claims alone are not verification; passing checks does not replace human review.

A failed required check produces a stopped outcome, not a normal ready PR. Missing integration infrastructure is not permission to replace required integration evidence with unit tests. Only the trusted delivery path publishes to the configured authorized fork/base. Existing protections against overwriting user work, force-pushing and unrelated destinations remain.

Initial delivery is complete when the PR is ready. Waiting for human review or merge does not hold the execution slot. Existing engine capacity behavior already supports waiting for feedback without occupying a running slot.

## 5. Bounded failure instead of a recovery product

### 5.1 Limits

Proposed defaults are forty coding turns, a sixty-minute deadline covering preparation through delivery, and at most ten started task attempts for the pilot installation.

The pilot attempt counter persists across restart and has no daily reset. Reaching the allowance stops new starts and shows why. A maintainer may explicitly raise the configured total after reviewing the run; restarting must not grant another allowance. Setup smoke checks are separately bounded and do not consume task slots.

These are work bounds, not a guaranteed dollar ceiling. The deadline must actually stop the owned workload or retain a visible blocked state until it is known inactive; marking a row failed while its coding process keeps running is not sufficient.

### 5.2 Ordinary failures

Ambiguous requirements, unsupported work and failed verification produce a clear reason, the last completed stage, checks and observed cost. The task stays stopped and the next eligible task proceeds once the previous workload is inactive.

There is no new Retry path in M1. The engineer uses the report, Jira link and any preserved changes to finish the work in their normal coding tool. Editing or relabelling the issue does not retry it. Existing manual Developer Flow entry points are not part of the pilot's retry contract.

A shared provider, authentication or repository-environment problem pauses new starts with one installation-level explanation. With only one repository there is no per-repository circuit breaker or routing subsystem. The documented operator procedure fixes the connection/environment, checks it and restarts intake. It does not rerun a failed issue.

### 5.3 Restart and uncertain publication

Reuse recovery already proven by the engine. Do not add a general resume/reconciliation framework to M1. Where existing evidence cannot establish safe continuation, freeze that attempt and show Needs inspection.

Before freeing the execution slot, establish that the previous owned workload is stopped or inactive. If ownership remains uncertain, pause intake rather than overlap two workloads. Once it is inactive, an uncertain task can remain stopped while unrelated eligible tasks continue.

Preserve any known candidate, branch, remote commit and PR identity. If a publication response was lost, say that a PR may already exist and requires inspection. Do not automatically repeat coding or publication. A maintainer can inspect the remote outcome during the pilot; autonomous repair of that uncertainty is deferred. The daily engineer does not have to resolve it immediately.

### 5.4 One operational stop procedure

No new Pause, Stop or Cancel buttons are required. Supply one documented way to disable new intake through the local configuration and controlled restart/shutdown path. Where possible, let the active task finish within its deadline. If interrupted, apply the ownership and uncertainty rules above.

Removing a queued issue's label withdraws that task before it starts. The publication recheck also observes removed delegation. This is not advertised as an immediate cancellation mechanism.

## 6. UI: improve two existing screens

Keep the current server-rendered technology. Update the existing execution list and detail templates for the pilot flow; retain technical pages for diagnosis. A read-only effective-configuration summary can reuse the current configuration page. No new frontend framework, broad navigation rebuild or standalone settings application is needed.

### 6.1 Task list

The first screen must answer What happened while I was away?

Show the current sprint/filter, destination fork, intake state, last successful Jira sync and remaining pilot allowance. A shared outage, disabled intake or exhausted allowance gets a short explanation. A task-specific failure appears on its own row.

Each row needs:

| Information | Required presentation |
| --- | --- |
| Task | Jira key/link and readable title |
| Outcome | Queued, Working with stage, PR ready, Could not complete, Not eligible, or Needs inspection |
| Time | Relative start/finish time and active duration; exact timestamp available in details |
| Spending | Estimated model cost, explicitly partial/unknown when necessary |
| Next useful action | Open PR or View reason/details |

Rows for withdrawn tasks show that they left the queue without implying execution. Meaning must be readable without colour alone. Keep UUIDs, step numbers and raw engine names out of the main table.

A page refresh is acceptable. Reuse existing live progress where available, but do not build a new live-update layer. Search, custom filters, aggregate cards, charts and a cross-sprint history browser are not acceptance requirements. Pilot records still persist and remain reachable through the existing list/detail routes.

### 6.2 Task detail

Put the title/Jira link, result or reason, known PR link, active duration and estimated cost at the top. Below, reuse existing evidence for Preparation, Implementation, Verification and Publication, distinguishing Passed, Failed and Not run.

A useful failure reads:

> Could not complete: the task does not say whether a disabled module should be skipped or rejected. No PR was created. Estimated model cost: $0.84. Clarify the behavior and continue in your usual coding tool.

That text illustrates presentation, not a real result. Actual reasons must come from observed execution state. Plain stage/cause templates and saved agent questions are enough; do not add an LLM explanation service.

Expose existing logs and artifacts below the result or through links. Retain any partial work for manual continuation, without building automatic export into another coding tool. An engine waiting for PR feedback displays PR ready for initial delivery.

### 6.3 Honest cost reporting

Reuse existing attempt usage and rate snapshots. Report unsuccessful work too. Missing rates or usage remain Incomplete estimate or Unknown, never zero. Duration excludes later human-review waiting time.

M1 needs per-task costs and separate setup-check cost, not a new sprint analytics model. An existing total can be retained if its scope and incomplete amounts are clear. Provider invoices may differ from estimates.

## 7. What we deliberately do later

The broader vision still includes simpler UI-based setup, multiple validated repository presets, optional manual task selection, explicit retry/takeover controls, bounded AI review passes, PR-comment feedback and comparative evaluations of profiles. These are possible follow-up milestones, not a hidden M1 backlog.

Existing PR-feedback functionality is retained without a new integration or acceptance requirement. Engineers can handle review comments in their usual workflow during the pilot.

Also deferred: cloud hosting, private source authentication, other build systems/providers, multi-user operation, dependency chains and automatic sprint rollover. Expansion should respond to pilot evidence.

The browser mockups remain useful visual references for typography and information hierarchy. In particular, their editable Settings, Retry/Pause controls, summary cards and filter tabs do not create requirements beyond this reduced document.

## 8. Acceptance and the stopping point

These are future demonstrations on the delivered build, not checks run during this design update.

| Scenario | M1 completion evidence |
| --- | --- |
| Fresh setup | Another engineer uses the short guide/template and documented checks, confirms the source/fork and enables intake without source or workflow-YAML knowledge |
| Core workflow | An eligible labelled task reaches a verified fork PR with applicable changelog work and useful PR description, without another engineer prompt |
| Failure does not stop the sprint | A sequence containing success, ambiguous requirements or verification failure, and another success produces clear results and advances after task failures |
| Once-only admission | Repeated scans, relabelling and restart do not start a second paid attempt for an attempted issue |
| Interruption and uncertainty | An interrupted or uncertain attempt is either safely handled by existing recovery or visibly frozen; no overlapping owned workload or blind second publication occurs |
| Scope and bounds | Withdrawn/changed input is respected; task deadline, persistent pilot allowance and shared-outage pause prevent unattended work from running indefinitely |
| Readable evidence | A pilot engineer can find task, outcome, time, cost, PR or failure reason without decoding IDs or opening logs; missing cost/check data is not misreported |
| External boundaries | Jira receives no writes or notifications; delivery is only to the configured fork; no merge/deployment occurs |

A fresh setup and the full label-to-result path are the release gate. Pixel-perfect matching of the mockups, additional settings controls and cosmetic refinement are not.

After those checks pass, give the pilot to a small group for one sprint. Ask whether it reduced interruptions, which PRs were useful after normal review, how much extra review/repair it caused and where setup failed. A simple feedback conversation or form is enough; no analytics product is required.

**Stop M1 work when engineers can complete that scenario independently and the listed behavior is demonstrated.** Fix defects that prevent or misrepresent the scenario; defer convenience and visual refinements until actual pilot feedback justifies them.

## 9. Implementation boundary and evidence

This is a product-scope update, not an implementation plan. The source inspected for the original design was main at b30da2511ed1ed4bcf89eb430a0484651317af0b; the intervening commit a1d87c9 added documentation only. No new runtime claims are made here.

Reuse the existing coding, verification, delivery, usage and template foundations. The irreducible new work is fixed-filter Jira discovery, durable issue admission, bounded unattended outcomes and a readable list/detail presentation. If this exposes an engine defect that prevents safe completion, repair that defect within the contract rather than starting a general engine rewrite.

Relevant existing anchors:

- [Developer Flow](../../../factory-flow-dev-factory/src/main/resources/flows/dev-factory.yaml), [Jira connector](../../../factory-connectors/src/main/java/org/folio/factory/connectors/jira/JiraConnector.java), [trigger deduplication](../../../factory-core/src/main/java/org/folio/factory/core/trigger/PipelineRouter.java).
- [Repository preset](../../../config/developer-repositories.yaml), [setup checks](../../../factory-app/src/main/java/org/folio/factory/app/developer/DeveloperPreflight.java), [read-only configuration UI](../../../factory-app/src/main/resources/templates/developer-configuration.html).
- [Coding protocol](../../../factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/runtime/CodingProtocol.java), [independent verification](../../../factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/verification/CandidateVerifier.java), [verified delivery](../../../factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/delivery/CandidateDelivery.java).
- [Execution capacity](../../../factory-core/src/main/java/org/folio/factory/core/engine/ExecutionClaimService.java), [usage summary](../../../factory-app/src/main/java/org/folio/factory/app/web/AttemptUsageSummary.java), [task list](../../../factory-app/src/main/resources/templates/executions.html), [task detail](../../../factory-app/src/main/resources/templates/execution.html), [setup guide](../../../README.md).

The builder may choose internal storage, adapters and template details within existing conventions. The builder may not silently add Jira writes, automatic retries, broader publication authority, weaker verification or access to private personal skills. The one-repository choice, fixed defaults and reduced controls are explicit design proposals in this revision, rather than claims that the user specified every value.
