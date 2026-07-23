# Token consumption in the management console

**Date:** 2026-07-23
**Status:** approved

## Problem

LLM token usage is recorded in two places today: the `factory.llm.tokens`
Micrometer counter (tagged by worker and prompt/completion type) and the
`promptTokens`/`completionTokens` keys of each `STEP_COMPLETED` audit event.
Both are invisible to a console user — reading them means scraping
`/actuator/metrics` or the raw audit JSON. Operators cannot answer "what did
this run cost" or "where do our tokens go" from the UI.

## Scope

Two surfaces:

1. **Execution detail page** — per-step token counts plus a run total.
2. **Dashboard** — a stat card with window totals, and a breakdown table
   showing which flow/step consumes them.

Explicitly out of scope: a tokens column on the `/executions` list, a
tokens-per-day chart, and any estimated monetary cost (that needs per-model
pricing configuration).

## Data source

Tokens are derived on read from `audit_event.detail` (jsonb) on
`STEP_COMPLETED` rows. No migration, no new column, no `factory-core` change.

Rationale:

- The append-only audit log stays the single source of truth; nothing
  denormalises a mutable counter onto `pipeline_execution`.
- `DashboardStatsService` already extracts jsonb this way for connector
  outcomes (`detail->>'connector'`), so this follows an established pattern.
- Retention never purges `audit_event`, so aggregates survive the purge of the
  executions they describe — the same reason the existing step-failure query
  uses `coalesce(e.flow_id, '(purged)')`.

Steps that report no usage write `{}` as their detail. `detail->>'promptTokens'`
is then SQL NULL, which `sum()` skips; the aggregate coalesces to `0`.

## Execution detail page

`ExecutionUiController.detail()` already loads `auditLog.forExecution(id)`, so
per-step tokens cost no extra query. The `STEP_COMPLETED` events are parsed into
a `stepId -> tokens` map and merged into the existing `stepStates` row maps.

The page renders per-step counts on the stepper and a run total (prompt,
completion, sum). A step reporting no usage — HITL gates, the deterministic
`test-execution` and `finalize` workers — renders blank rather than `0`, so
"no LLM involved" stays distinguishable from "consumed nothing".

A step retried after failure emits one `STEP_COMPLETED` row, so per-step counts
never double-count. Only the last model call of a worker is reported, matching
the semantics already documented on `AbstractLlmAgentWorker.resultWithUsage`.

## Dashboard

Two additions to the `DashboardStats` record, both computed in
`DashboardStatsService` over the existing day window:

- `TokenUsage(long promptTokens, long completionTokens)` — window totals,
  rendered as a stat card beside the existing ones.
- `List<StepTokenCount>(String flowId, String stepId, long promptTokens,
  long completionTokens)` — rendered as a table ordered by total descending.

The breakdown keys on `step_id`, a real column, rather than worker id. Worker
id appears only in `STEP_STARTED` detail, so a per-worker view would need a
self-join back to `STEP_STARTED` on `(execution_id, step_id)` for no practical
gain: step id maps 1:1 to worker in every flow today and is already the label
used throughout the console.

## Testing

Following the repo's three tiers:

- `DashboardStatsIntegrationTest` — seed token-bearing audit rows plus one with
  `{}` detail, asserting the empty one contributes nothing and does not throw.
- A standalone `ExecutionUiController` MockMvc test for model shape.
- `UiRenderSmokeTest` — assert the rendered page text, the only tier that
  resolves the layout and component fragments.

## Risks

Aggregating over `audit_event` scans rows filtered by `event_type` and
`occurred_at`. The `event_type` index added in `V4` covers the filter; if the
audit table grows large enough for this to matter, the fix is a partial or
covering index, not a schema change to the write path.
