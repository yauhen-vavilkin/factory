# Flow Descriptor Reference

A flow plugin is defined by one YAML descriptor on the classpath. This is the
complete field reference and the exact validation the platform applies. For
the hands-on guide (building the workers behind a descriptor, testing,
conventions) see [Extending the Factory](extending-the-factory.md).

## Discovery and parsing

- Descriptors are discovered at startup from
  `classpath*:flows/*.yaml` (every module on the classpath contributes;
  pattern overridable via `factory.flows.location-pattern`). Parsed
  descriptors are held in memory and mirrored to the `flow_registry` DB table
  — parser: [`FlowDescriptorParser`](../factory-core/src/main/java/org/folio/factory/core/registry/FlowDescriptorParser.java),
  registry: [`FlowRegistry`](../factory-core/src/main/java/org/folio/factory/core/registry/FlowRegistry.java).
- Field names are **snake_case** (`agent_chain`, `worker_id`, `event_type`).
- Parsing is **strict**: an unknown field anywhere in the document fails
  startup (`FAIL_ON_UNKNOWN_PROPERTIES`). Typos cannot ship.
- Any validation failure below raises `FlowValidationException` and **aborts
  application startup** — a broken plugin is never half-loaded.

## Complete annotated example

```yaml
id: test-factory                     # unique across all registered flows
name: Test Factory                   # human-readable
version: 1.0.0                       # definition version; bump on behavioural change
triggers:
  - event_type: manual
  - event_type: jira.issue.transitioned
    filters:                         # all must match (JSON pointer -> expected value)
      "/issue/fields/status/name": "Ready for QA"
input_schema:
  required: [issueKey]               # top-level payload fields the router enforces
output_schema:
  artifacts: [test_plan.md, test_scripts.md, test_results.md, sync_report.md]
retry_policy:
  max_attempts: 3
  backoff_seconds: [30, 120, 300]
agent_chain:
  - step_id: triage
    type: AGENT
    worker_id: triage-agent
    inputs: ["$trigger"]             # reserved name: the trigger payload
    outputs: [scope_manifest.md]

  - step_id: qa-plan-review
    type: HITL_GATE
    gate:
      gate_id: gate-1-test-plan
      title: "QA review: manual test cases"
      review_instructions: >
        What to check, and what AMEND vs REJECT should mean at this gate.
      reviewed_artifacts: [test_plan.md, scope_manifest.md]

  - step_id: delegate                # SUB_FLOW example (not part of the Test Factory flow)
    type: SUB_FLOW
    sub_flow:
      flow_id: some-registered-flow
      input_mapping:                 # parent artifact -> child artifact
        scope_manifest.md: input.md
      output_mapping:                # child artifact -> parent artifact
        result.md: collected.md
```

## Top-level fields

| Field | Type | Required | Semantics |
|---|---|---|---|
| `id` | string | yes (non-blank) | Flow identity: referenced by manual triggers (`flowId`), sub-flow steps (`sub_flow.flow_id`), executions, and the UI. Duplicate ids across modules fail startup. |
| `name` | string | yes (non-blank) | Display name. |
| `version` | string | yes (non-blank) | Version of the flow *definition*. The DB mirror is keyed `(id, version)` with a content hash; changing the YAML without bumping the version logs a warning and updates the mirror snapshot. Bump on any behavioural change. |
| `triggers` | list | no (default `[]`) | Trigger contracts — see below. A flow with no triggers can still run as a sub-flow or via manual initiation. |
| `input_schema` | object | no | Free-form, with one enforced key: `required` — an array of **top-level** trigger-payload field names that must be present and non-null. Checked by the router for both webhook-matched and manual triggers; a webhook trigger failing the check is skipped for that flow (logged), a manual trigger is rejected. Everything else in `input_schema` is documentation. |
| `output_schema` | object | no | Documentation only (conventionally `artifacts: [...]` listing the flow's deliverables). Not enforced — actual output enforcement is per-step via `outputs`. |
| `agent_chain` | list | yes (≥ 1 step, ≥ 1 `AGENT` step) | The ordered pipeline. Executed strictly in sequence by a step cursor; there is no branching — model conditional behaviour inside workers, and parallelism/composition via sub-flows. |
| `retry_policy` | object | no | Per-step retry budget for the whole flow — see below. |

## `triggers` entries

| Field | Type | Required | Semantics |
|---|---|---|---|
| `event_type` | string | yes (non-blank) | Exact match against the incoming event's type. Existing types: `manual`, `jira.issue.transitioned`, `jira.issue.created`, `jira.event`, `github.<X-GitHub-Event>` (e.g. `github.push`). New sources add adapter endpoints in `factory-app`. |
| `filters` | map | no | JSON-pointer → expected value. Each pointer is resolved into the event payload and compared **as a string** (non-string scalars are stringified; booleans/numbers work — `"/ref": "refs/heads/main"`). A pointer resolving to an object or array never matches and logs a warning. All filters in one contract must match; multiple contracts are alternatives (OR). |

Manual triggers (`POST /api/triggers/manual`) name the flow explicitly and
**bypass contract matching** — but not `input_schema.required` validation.
Declaring `- event_type: manual` is still good practice as documentation.

Router-level guards that apply regardless of contract: payload size cap,
dedup (webhook payload identity via `factory.limits.dedup.id-pointers`;
manual triggers only with an explicit `dedupKey`), and the daily execution
budget. See [README — Configuration](../README.md#configuration).

## `retry_policy`

| Field | Type | Default | Semantics |
|---|---|---|---|
| `max_attempts` | int | `3` (also used when ≤ 0) | Attempts per **step** (not per flow). Each failed attempt re-runs the whole step. On the final failure the execution transitions to `FAILED_ESCALATED` and an escalation review opens (reserved gate id `escalation`). |
| `backoff_seconds` | list of int | `[30, 120, 300]` | Delay before retry attempt *n* (1-based). Attempts beyond the list reuse the last entry. |

## `agent_chain` steps

Common to every step:

| Field | Type | Required | Semantics |
|---|---|---|---|
| `step_id` | string | yes; unique within the flow | Identity in audit events, retry bookkeeping, artifact attribution, logs. |
| `type` | `AGENT` \| `HITL_GATE` \| `SUB_FLOW` | yes | Selects which type-specific block below is required. |
| `inputs` | list of artifact names | no | Contract used by `AGENT` steps (and shown in escalation review packages). The reserved name `$trigger` grants the worker read access to the trigger payload. |
| `outputs` | list of artifact names | no | Contract used by `AGENT` steps. |
| `config` | map | no | Free-form key/values passed to the worker (`AgentContext.config`). |

### `type: AGENT`

| Field | Required | Semantics |
|---|---|---|
| `worker_id` | yes (non-blank) | Must resolve to a registered `AgentWorker` bean at startup (validated across all flows). Worker ids are global — any flow may reference any worker. |

Engine contract for an AGENT step:

- The worker receives **only** the artifacts named in `inputs` (latest
  versions), plus the trigger payload iff `$trigger` is declared. An `inputs`
  entry that no earlier step produced fails the attempt at runtime
  (`requires artifact '…' which does not exist`).
- The worker must return **every** artifact named in `outputs`
  (`did not produce declared output artifact` otherwise). Anything it returns
  is persisted as a new artifact version attributed to the `step_id`, after
  all `StepPostProcessor` beans pass.
- Any exception fails the attempt and consumes the retry budget.

### `type: HITL_GATE`

| Field | Required | Semantics |
|---|---|---|
| `gate.gate_id` | yes (non-blank) | Gate identity in reviews and audit. `escalation` is **reserved** for retry-budget escalations — using it fails startup. |
| `gate.title` | no | Review inbox headline. |
| `gate.review_instructions` | no | Shown to the reviewer — write the actual checklist. |
| `gate.reviewed_artifacts` | no | Artifact names whose latest versions are embedded in the review package (artifacts that don't exist yet are silently omitted). Amendments are accepted for these artifacts and saved as new versions attributed `hitl:<reviewer>`. |

The execution parks in `AWAITING_HITL` until a decision: `APPROVE` (resume),
`AMEND` (save changed artifacts as new versions, then resume), `REJECT`
(terminal `REJECTED`). See the [gates guide](extending-the-factory.md#8-hitl-gates).

### `type: SUB_FLOW`

| Field | Required | Semantics |
|---|---|---|
| `sub_flow.flow_id` | yes (non-blank) | Must be a registered flow id — validated at startup after all descriptors load, so cross-module references work regardless of load order. |
| `sub_flow.input_mapping` | no | `parent artifact → child artifact`: copied into the child at invocation (attributed `subflow:<parent execution id>`). A mapped parent artifact that doesn't exist fails the attempt. |
| `sub_flow.output_mapping` | no | `child artifact → parent artifact`: copied up when the child **completes** (a child completing without a mapped artifact fails the hand-off). |

The child runs as a full execution of the referenced flow with the parent's
trigger payload; the parent parks in `AWAITING_SUBFLOW` through the child's
entire lifecycle, including the child's HITL gates. A child that terminates
without completing (rejected/cancelled) escalates the parent; approving that
escalation re-invokes the sub-flow step.

## Reserved names

| Name | Where | Meaning |
|---|---|---|
| `$trigger` | step `inputs` | grants the worker the trigger payload (`AgentContext.triggerPayload`) |
| `escalation` | gate ids | reserved for the platform's retry-exhaustion reviews; descriptors may not use it |

## Startup validation summary

Startup fails (with `FlowValidationException`) when a descriptor has:

- unparseable YAML or any unknown field;
- blank `id`, `name`, or `version`;
- an empty `agent_chain`, or no `AGENT` step at all;
- a duplicate `step_id` within the flow, or a duplicate flow `id` across
  modules;
- an `AGENT` step without `worker_id`, a `HITL_GATE` without `gate.gate_id`,
  or a `SUB_FLOW` without `sub_flow.flow_id`;
- a gate using the reserved id `escalation`;
- a `worker_id` with no matching `AgentWorker` bean, or a `sub_flow.flow_id`
  naming an unregistered flow (both checked after all modules load);
- two worker beans sharing one `id()` (reported as `IllegalStateException`).

What startup does **not** check (fails at runtime instead): that every
`inputs` entry is produced by an earlier step, that prompt files exist for
LLM workers, and that workers actually return their declared `outputs`. The
end-to-end test for your flow (see
[Testing your flow](extending-the-factory.md#13-testing-your-flow)) is what
catches those before deployment.
