# Factory product vision and minimal sprint pilot

**Date:** 2026-10-23

**Status:** Revised design for review. Documentation only; no implementation or paid campaign is authorized by this document.

**Approach:** Develop the existing Factory, as selected in the product discussion.

**Scope authority:** This revision replaces the earlier M1 definitions in this file and incorporates the user's four mandatory priorities below. The browser mockups illustrate the product direction; their particular settings forms, controls and summary cards are not M1 acceptance requirements. A friendly UI and preservation of existing tokenomics are required regardless of the chosen layout.

## 1. Product promise

An engineer adds a label to a suitable Jira task and returns to their main work. Factory discovers the task, prepares its context, implements it, runs the required checks and creates a PR in the configured fork. If it cannot finish, it records a clear reason and estimated spending, then takes the next task. The engineer checks the results when convenient, normally once a day.

The value is completing the routine workflow without repeated prompts to check the code, handle NEWS, create a PR or remember an unfinished agent session. Engineers keep their preferred coding tools for deeper work. Ordinary human PR review remains part of engineering; the pilot measures additional review effort and interruptions relative to that normal workflow.

The first milestone is a small usable pilot for a few engineers during one sprint. It tests whether delegation saves attention. Broad repository support, a polished configuration product and proof of commercial viability are later questions.

### Four mandatory product requirements

1. **Simple first use.** A developer seeing Factory for the first time can deploy and configure it independently through a short, prepared startup path. Few required values, useful defaults and actionable errors are part of M1.
2. **Autonomous daily operation.** After delegation, normal tasks reach a PR or a clear stopped result without intermediate prompts, stage supervision or routine maintainer intervention. Task-specific failures do not require an immediate response and do not block later work.
3. **A friendly, readable UI.** Clear typography, result hierarchy, task-oriented wording and simple navigation are acceptance requirements. Engineers should not need to understand execution IDs or engine internals to use the product.
4. **Preserved tokenomics.** Existing token counts, usage breakdowns, estimated costs, totals and indicators of incomplete data remain available. Reducing new analytics work must not remove existing visibility.

Configuration through the UI is optional. A small, understandable environment template is sufficient if the first-use requirement is met. Optional UI settings must not delay delivery of these four requirements.

## 2. The smallest useful M1

**M1 is ready when a first-time engineer can independently start a prepared sprint source, label suitable tasks, and later understand each result through a friendly UI with the existing tokenomics intact. Normal execution needs no supervision between the label and the PR or stopped outcome.**

The minimum supported shape is one engineer, one local installation with persistent storage, one Jira project and explicit sprint, one prepared public source repository, one personal GitHub.com fork and one packaged execution profile. Tasks are independent and run sequentially.

The proposed reference repository remains folio-org/mgr-tenant-entitlements. The existing Java 21/Maven java21-unit policy runs `mvn -B -ntp test` and requires fresh executed Surefire reports. Only tasks whose required evidence fits that policy are supported. A task requiring unavailable integration infrastructure must stop; this scope does not weaken the repository's verification requirements.

### Required now versus optional or deferred

| Area | Required for M1 | Optional or deferred |
| --- | --- | --- |
| Setup | A small commented environment template, one prepared repository preset and one startup path that performs necessary checks | UI settings are optional; a setup wizard, catalog import and live configuration changes are deferred |
| Jira intake | One fixed project/sprint/assignee/status/label filter, complete polling and visible sync state | Board picker, general JQL builder, story-point rules, backlog intake, automatic sprint rollover |
| Queue | One active task, stable ordering, persistent admission and one attempt per issue | Parallelism, drag-and-drop ordering, dependency chains, multiple installations coordinating |
| Execution | Existing isolated coding flow, contribution instructions, required checks and verified PR delivery | New harness, general skill marketplace, automatic repair/review loops |
| Failure | Clear stopped outcome, preserved evidence and costs; task failures release the queue | Product Retry, Stop and Take over buttons; automatic recovery from uncertain execution/publication |
| UI | Friendly, readable task list and detail screens with clear result hierarchy and navigation; visible effective setup | New frontend stack, broad navigation redesign, new charts/search/filter suite and exact reproduction of the mockups |
| Cost and limits | Preserve existing tokens, breakdowns, costs, totals and incomplete-data indicators; bound each task | New daily-reset budgets, hard dollar caps and cross-sprint analytics; no mandatory installation-lifetime task quota |
| PR follow-up | Engineer reviews the delivered PR in their usual tools | Automatic comment watcher; new feedback UX or feedback work required for pilot completion |

Jira remains read-only. Initial PRs target personal forks. No external notifications, merge or deployment is included. The engineer's private skills and personal workflow are not shared product inputs.

### Approaches and the reduction

The user selected extending the existing Factory over building a separate service. This preserves its execution, verification, delivery and accounting foundations.

Within that approach, the earlier design required editable configuration, several new task controls and broader recovery behavior. This revision uses prepared configuration, two readable screens and conservative stopping on uncertainty. The scope reduction removes new mechanisms while keeping simple onboarding, autonomous daily use, UI usability and existing tokenomics mandatory. It does not permit moving routine orchestration work onto the engineer.

## 3. Setup: configure once, then use Jira

### 3.1 One prepared startup path

1. Start the supported local CodeMie proxy.
2. Copy the pilot environment template and fill the connection credentials, target sprint and personal fork. The prepared preset supplies repository routing, base branch, build checks and ordinary defaults.
3. Run one documented Factory start action. It performs the necessary configuration and runtime checks, reports the effective source and destination, and enables processing when ready. The action explicitly discloses the small bounded model check; it may include one first-run confirmation of the delegated scope within the same startup path.
4. Open the dashboard. It shows Ready/Processing or an actionable startup problem with the setting or dependency to fix. Once ready, delegate suitable tasks by adding the Jira label.

The guide must provide exact commands for the delivered build. It must not require reading source code, editing workflow YAML, writing JQL, inventing verification commands or using APIs/database queries to discover configuration values. The sprint identifier needs a direct, documented way to obtain it. A small helper command is acceptable; a setup application is not required. The release must provide versioned images or one documented build path.

Starting the product must not require a manual disable → separate checks → change flag → restart sequence. Necessary internal checks remain, but the startup path coordinates them. If a prerequisite fails, the engineer fixes the named cause and repeats the same start action; there must be no undocumented recovery ceremony.

Ordinary setup values are limited to connection credentials/endpoints, sprint ID, fork destination and an optional base-branch override. Advanced bounds remain documented defaults. The prepared Jira project, eligible status and delegation label are explicit in the template and effective configuration; changing them is a configuration edit followed by the same start path. An operational intake-disable control may exist, but toggling it is not a required first-use step.

Use the existing environment/catalog mechanism as the single configuration authority. Do not add a second settings database, import rules or runtime editing. Preserve the effective non-secret inputs used by each attempt using existing execution artifacts.

### 3.2 One supported runtime

The qualification target is the current packaged Claude Code 2.1.287 profile, literal model identifier claude-sonnet-5-5 and the local CodeMie proxy, with Java 21/Maven. Record the tested image identity, instructions and verification policy in the release and attempt evidence. Pilot engineers need access to the supported CodeMie deployment/model.

These are design targets taken from the inspected project, not new runtime evidence. Controlled conditions improve comparability; they do not guarantee identical output or an immutable provider model alias.

The startup path checks readable Jira scope, repository/base/fork authorization and available Docker images. Its explicitly disclosed, bounded model check from the actual coding container verifies inference, a tool call and usable usage reporting. The check makes no Jira writes or PRs and reports its own token use/cost separately from task execution. A complete applicable rate configuration is required for the supported profile.

Both checks must pass before intake is enabled; the startup path handles this without separate operator steps. Reuse valid runtime evidence for the unchanged profile/connection so an ordinary restart does not require another paid smoke check or human approval. Recheck affected prerequisites after relevant configuration changes. No connection-test button or separate readiness management screen is required. Opening the dashboard or reading the Jira filter does not invoke a model.

## 4. Jira intake and execution

### 4.1 One explicit filter

The shipped template expresses:

> Configured Jira project + explicit sprint ID + delegation label + configured To Do status + authenticated engineer as assignee.

The proposed label is factory-ready. There is no board selector or arbitrary query editor in M1. The project narrows intake to the single prepared repository. The guide explains how to obtain the sprint ID and confirms the resulting sprint name/scope during setup.

The effective filter and destination are visible read-only. Enabling intake includes future matching tasks in the same sprint, not just the initial preview. Nothing silently advances to the next sprint. The engineer disables intake at the end of the pilot; changing the sprint uses the same startup path, which rechecks the changed scope. A separate sprint-lifecycle monitor is deferred.

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

Proposed per-task defaults are forty coding turns and a sixty-minute deadline covering preparation through delivery. Preserve existing configured work limits and protection against duplicate paid attempts.

There is no mandatory ten-task installation limit, new lifetime-attempt counter or routine maintainer permission to continue the sprint. A small fixed task batch may be used for a one-off validation campaign, but it is not a product activation quota. Setup smoke checks are separately bounded. Any existing configured limit that pauses work must be reported clearly; M1 does not add a new budget-management subsystem.

These are work bounds, not a guaranteed dollar ceiling. The deadline must actually stop the owned workload or retain a visible blocked state until it is known inactive; marking a row failed while its coding process keeps running is not sufficient.

### 5.2 Ordinary failures

Ambiguous requirements, unsupported work and failed verification produce a clear reason, the last completed stage, checks and observed cost. The task stays stopped and the next eligible task proceeds once the previous workload is inactive.

There is no new Retry path in M1. The engineer uses the report, Jira link and any preserved changes to finish the work in their normal coding tool. Editing or relabelling the issue does not retry it. Existing manual Developer Flow entry points are not part of the pilot's retry contract.

A shared provider, authentication or repository-environment problem pauses new starts with one installation-level explanation. The user fixes the reported external cause and uses the same startup path to recheck and continue the queue. It does not rerun a failed issue. Existing bounded infrastructure recovery may be reused, without introducing per-repository circuit breakers or unsafe repeated coding/publication.

Manual intervention is an exception for a real failure, changed scope or ambiguous publication. Normal tasks, task-specific failures and an unchanged healthy restart must not create stage approvals, routine check commands or requests to a maintainer. A stopped task remains available for later inspection while eligible work proceeds.

### 5.3 Restart and uncertain publication

Reuse recovery already proven by the engine. Do not add a general resume/reconciliation framework to M1. Where existing evidence cannot establish safe continuation, freeze that attempt and show Needs inspection.

Before freeing the execution slot, establish that the previous owned workload is stopped or inactive. If ownership remains uncertain, pause intake rather than overlap two workloads. Once it is inactive, an uncertain task can remain stopped while unrelated eligible tasks continue.

Preserve any known candidate, branch, remote commit and PR identity. If a publication response was lost, say that a PR may already exist and requires inspection. Do not automatically repeat coding or publication. A maintainer can inspect the remote outcome during the pilot; autonomous repair of that uncertainty is deferred. The daily engineer does not have to resolve it immediately.

### 5.4 One operational stop procedure

No new Pause, Stop or Cancel buttons are required. Supply one documented way to disable new intake through the local configuration and controlled restart/shutdown path. Where possible, let the active task finish within its deadline. If interrupted, apply the ownership and uncertainty rules above.

Removing a queued issue's label withdraws that task before it starts. The publication recheck also observes removed delegation. This is not advertised as an immediate cancellation mechanism.

## 6. UI: improve two existing screens

Keep the current server-rendered technology. Update the existing execution list and detail templates for the pilot flow; retain technical pages for diagnosis. A read-only effective-configuration summary can reuse the current configuration page. No new frontend framework, broad navigation rebuild or standalone settings application is needed.

Readable typography, spacing, clear result hierarchy and a simple path between task list, details, Jira and PR are part of M1. Renaming columns while leaving the screen difficult to scan does not satisfy this requirement. At normal laptop width, a first-time engineer must be able to identify the result and next useful action without learning engine terminology. Exact colours, decoration and pixel-level matching of the mockups can wait.

### 6.1 Task list

The first screen must answer What happened while I was away?

Show the current sprint/filter, destination fork, intake state and last successful Jira sync. A shared outage, intentionally disabled intake or an existing configured limit gets a short explanation. A task-specific failure appears on its own row. Ordinary operation must not require monitoring this page.

Each row needs:

| Information | Required presentation |
| --- | --- |
| Task | Jira key/link and readable title |
| Outcome | Queued, Working with stage, PR ready, Could not complete, Not eligible, or Needs inspection |
| Time | Relative start/finish time and active duration; exact timestamp available in details |
| Spending | Estimated model cost, explicitly partial/unknown when necessary |
| Next useful action | Open PR or View reason/details |

Rows for withdrawn tasks show that they left the queue without implying execution. Meaning must be readable without colour alone. Keep UUIDs, step numbers and raw engine names out of the main table.

A page refresh is acceptable. Reuse existing live progress where available, but do not build a new live-update layer. New search/filter features, summary-card layouts, charts and a cross-sprint history browser are not acceptance requirements. Existing accounting totals and detail remain accessible regardless of their presentation. Pilot records persist and remain reachable through the existing list/detail routes.

### 6.2 Task detail

Put the title/Jira link, result or reason, known PR link, active duration and estimated cost at the top. Below, reuse existing evidence for Preparation, Implementation, Verification and Publication, distinguishing Passed, Failed and Not run.

A useful failure reads:

> Could not complete: the task does not say whether a disabled module should be skipped or rejected. No PR was created. Estimated model cost: $0.84. Clarify the behavior and continue in your usual coding tool.

That text illustrates presentation, not a real result. Actual reasons must come from observed execution state. Plain stage/cause templates and saved agent questions are enough; do not add an LLM explanation service.

Expose existing logs and artifacts below the result or through links. Retain any partial work for manual continuation, without building automatic export into another coding tool. An engine waiting for PR feedback displays PR ready for initial delivery.

### 6.3 Preserve existing tokenomics

Preservation is mandatory. Reuse existing attempt accounting and rate snapshots, and keep the information currently available to users:

- Total reported tokens and the existing input, output, cache-read and cache-write breakdowns.
- Estimated costs per recorded attempt and existing cumulative totals, including unsuccessful work and already-supported feedback rounds.
- Available runtime/model and rate context, plus the existing partial, provisional, unknown and unavailable-data indicators.

The task list may lead with a compact estimated cost. The detail page must provide an obvious path to the preserved totals and breakdowns, without requiring raw-log inspection. Rearranging information is allowed; dropping existing metrics or totals to simplify the new layout is not.

Missing rates or usage remain Incomplete estimate or Unknown, never zero. Duration excludes later human-review waiting time. Show setup-check usage/cost separately from task execution so it remains accounted for without pretending it belongs to a Jira task. Provider invoices may differ from estimates.

No new sprint analytics model is required. Compare the existing and revised views on the same recorded execution data, including incomplete usage and feedback history, to confirm that the redesign has not lost accounting information or changed its meaning.

## 7. What we deliberately do later

Configuration through the UI is optional and is not a release gate. The prepared non-UI startup path must already be simple enough for a first-time developer. UI settings can be considered when their value and effort are clear, without becoming a prerequisite for the four mandatory outcomes.

The broader vision also includes multiple validated repository presets, optional manual task selection, explicit retry/takeover controls, bounded AI review passes, PR-comment feedback and comparative evaluations of profiles. These are possible follow-up milestones, not a hidden M1 backlog.

Existing PR-feedback functionality is retained without a new integration or acceptance requirement. Engineers can handle review comments in their usual workflow during the pilot.

Also deferred: cloud hosting, private source authentication, other build systems/providers, multi-user operation, dependency chains and automatic sprint rollover. Expansion should respond to pilot evidence.

The browser mockups remain useful visual references for typography and information hierarchy. Their particular editable Settings, Retry/Pause controls, summary cards and filter tabs do not create requirements beyond this document. This does not make baseline usability or existing tokenomics optional.

## 8. Acceptance and the stopping point

These are future demonstrations on the delivered build, not checks run during this design update.

| Scenario | M1 completion evidence |
| --- | --- |
| Fresh setup | A developer seeing Factory for the first time independently uses the short guide/template and one start path; checks and activation are coordinated without manual flag/check/restart choreography, source-code knowledge or maintainer assistance |
| Startup problem | A missing or invalid prerequisite gives a plain cause and concrete correction; the engineer fixes it and repeats the same start action |
| Core workflow and autonomy | Labelled tasks reach verified fork PRs with applicable changelog work and useful descriptions without intermediate prompts, stage approvals or routine queue maintenance; healthy unchanged restart preserves that behavior |
| Failure does not stop the sprint | A sequence containing success, ambiguous requirements or verification failure, and another success produces clear results and advances after task failures |
| Once-only admission | Repeated scans, relabelling and restart do not start a second paid attempt for an attempted issue |
| Interruption and uncertainty | An interrupted or uncertain attempt is either safely handled by existing recovery or visibly frozen; no overlapping owned workload or blind second publication occurs |
| Scope and bounds | Withdrawn/changed input is respected; per-task limits and shared-outage handling bound failed work without imposing a new lifetime task quota or routine maintainer unlock |
| Friendly UI | At normal laptop width, a first-time user can scan readable task titles/statuses, identify results and navigate between list, detail, Jira and PR; typography and hierarchy support this without decoding IDs or opening logs |
| Tokenomics preservation | The same recorded executions retain existing token counts, input/output/cache breakdowns, cost totals, rate context and incomplete-data indicators; unsuccessful work and supported feedback history remain included |
| External boundaries | Jira receives no writes or notifications; delivery is only to the configured fork; no merge/deployment occurs |

Simple first use, autonomous label-to-result work, friendly UI and preservation of existing tokenomics are all release gates. Pixel-perfect matching, optional UI configuration and decoration are not. Finishing the backend path alone does not complete M1.

After those checks pass, give the pilot to a small group for one sprint. Ask whether it reduced interruptions, which PRs were useful after normal review, how much extra review/repair it caused and where setup failed. A simple feedback conversation or form is enough; no analytics product is required.

**Stop M1 work when engineers can complete that scenario independently, all four mandatory outcomes hold and the listed behavior is demonstrated.** Fix barriers to first use, unnecessary routine intervention, unreadable presentation and accounting regressions before the pilot. Defer additional convenience features and decorative refinements until actual feedback justifies them.

## 9. Implementation boundary and evidence

This is a product-scope update, not an implementation plan. The source inspected for the original design was main at b30da2511ed1ed4bcf89eb430a0484651317af0b; subsequent commits a1d87c9 and 137eadb changed documentation only. The tokenomics review also checked the existing task template and usage summary. No new runtime claims are made here.

Reuse the existing coding, verification, delivery, usage and template foundations. The irreducible new work is fixed-filter Jira discovery, durable issue admission, bounded unattended outcomes and a readable list/detail presentation. If this exposes an engine defect that prevents safe completion, repair that defect within the contract rather than starting a general engine rewrite.

Relevant existing anchors:

- [Developer Flow](../../../factory-flow-dev-factory/src/main/resources/flows/dev-factory.yaml), [Jira connector](../../../factory-connectors/src/main/java/org/folio/factory/connectors/jira/JiraConnector.java), [trigger deduplication](../../../factory-core/src/main/java/org/folio/factory/core/trigger/PipelineRouter.java).
- [Repository preset](../../../config/developer-repositories.yaml), [setup checks](../../../factory-app/src/main/java/org/folio/factory/app/developer/DeveloperPreflight.java), [read-only configuration UI](../../../factory-app/src/main/resources/templates/developer-configuration.html).
- [Coding protocol](../../../factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/runtime/CodingProtocol.java), [independent verification](../../../factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/verification/CandidateVerifier.java), [verified delivery](../../../factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/delivery/CandidateDelivery.java).
- [Execution capacity](../../../factory-core/src/main/java/org/folio/factory/core/engine/ExecutionClaimService.java), [usage summary](../../../factory-app/src/main/java/org/folio/factory/app/web/AttemptUsageSummary.java), [task list](../../../factory-app/src/main/resources/templates/executions.html), [task detail](../../../factory-app/src/main/resources/templates/execution.html), [setup guide](../../../README.md).

The builder may choose internal storage, adapters and template details within existing conventions. The builder may not silently add Jira writes, automatic retries, broader publication authority, weaker verification or access to private personal skills. The builder also may not trade away simple startup, autonomous routine operation, readable UI or existing tokenomics to reduce implementation scope. UI configuration remains optional. The one-repository choice, fixed defaults and reduced controls remain explicit design proposals rather than claims that the user specified every value.
