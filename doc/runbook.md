# Operations Runbook — FOLIO AI SDLC Factory

Operational procedures for running the Factory in production. Pairs with
[backup-dr.md](backup-dr.md) (data durability) and [performance.md](performance.md)
(pool/engine sizing). Everything here is grounded in the current control plane —
statuses, config keys, and metrics named below are real.

> Scope note: this runbook covers **operations**, not the security hardening that
> is deliberately out of scope for this milestone (endpoint auth, webhook
> signatures, secrets management). Treat `/actuator/*` and the HITL console as
> internal-network-only until that lands.

## Execution lifecycle & statuses

An execution row (`pipeline_execution.status`) moves through:

| Status | Meaning | Who advances it |
|---|---|---|
| `PENDING` | Runnable; waiting for a poller claim (or in retry backoff via `next_run_at`) | `ExecutionPoller` claims it → `RUNNING` |
| `RUNNING` | A worker thread is advancing a step | Engine, or the **lease reaper** if the worker died |
| `AWAITING_HITL` | Parked at a human gate | A reviewer decision in `/reviews` |
| `AWAITING_SUBFLOW` | Parent waiting on a child flow | Child completion / escalation reconciliation |
| `COMPLETED` | Finished successfully (terminal) | — |
| `FAILED_ESCALATED` | Retry budget exhausted or engine error; **has a pending escalation review** (terminal-but-resumable) | Approving/rejecting the escalation in `/reviews` |
| `REJECTED` | A reviewer rejected a gate (terminal) | — |
| `CANCELLED` | A reviewer rejected an escalation (terminal) | — |

## First responders: where to look

1. **`/executions`** — every execution, newest first, with its full audit timeline
   (each step, artifact write, HITL decision, connector action, retry, escalation).
2. **`/reviews`** — the human inbox: pending gates **and** escalations awaiting a decision.
3. **`GET /api/status`** — engine enabled flag, connector configured/not-configured, registered flows.
4. **`GET /actuator/health`** — liveness/readiness; `readiness` includes `db`, and the
   `connectors` component shows each integration's configured state (always `UP` — an
   unconfigured connector is expected advisory mode, not a failure).
5. **`GET /actuator/prometheus`** — the metrics below.

## Key metrics & suggested alerts

Metrics are Prometheus-exposed (dots → underscores; counters get `_total`). Thresholds
are starting points — tune to your traffic.

| Signal (Prometheus name) | Watch for | Suggested alert |
|---|---|---|
| `factory_steps_escalations_total` | Steps exhausting the retry budget | rate > 0 sustained 15m → page (systemic failure, e.g. a down dependency or bad prompt) |
| Unresolved escalations piling up | `factory_steps_escalations_total` rising while `factory_executions_terminal_total` stays flat | sustained divergence → escalated runs are not being worked; check `/reviews`. (`FAILED_ESCALATED` is deliberately **not** an outcome tag — it is resumable, so counting it would double-count runs that later resolve.) |
| `factory_connector_actions_total{outcome="failed"}` | Jira/GitHub/TestRail sync failures | rate > 0 sustained → check the connector/credentials |
| `factory_agent_step_duration_seconds` (p99) | Slow workers (LLM latency, Karate) | p99 approaching `lease-timeout-seconds` → lease at risk |
| `hikaricp_connections_pending` / `_timeout_total` | DB pool saturation | pending > 0 sustained, or any timeout increase → raise `FACTORY_DB_POOL_MAX` (see performance.md) |
| Executions stuck in `RUNNING` beyond the lease | Crashed workers not being reaped | count(status=RUNNING, updated_at < now − lease) > 0 for > 2× reap interval → investigate the reaper |
| Pending-review age | HITL backlog | oldest `PENDING` `hitl_review` older than your SLA → notify reviewers |
| `factory_executions_terminal_total{outcome="rejected"|"cancelled"}` | Reviewers rejecting output | spike → prompt/quality regression |

Also alert on the standard actuator readiness probe flipping (DB unreachable) and JVM/GC basics.

## Recovering a stuck execution

**Symptom: an execution sits in `RUNNING` and its audit timeline shows no recent step.**
The worker likely died. The **lease reaper** (`ExecutionClaimService.reapStale`, every
`factory.engine.reap-interval-ms`, default 30s) returns any `RUNNING` row whose
`updated_at` is older than `factory.engine.lease-timeout-seconds` (default **1800s / 30
min**) back to `PENDING`, and a poller re-runs the step. Re-running a step is safe —
workers write new immutable artifact versions, never overwrite.

- **Expected recovery time:** up to the lease timeout. If you cannot wait 30 min and are
  certain the worker is dead, manually requeue (see below).
- **A step that legitimately runs longer than the lease** (a Karate run > 30 min) will be
  reaped while still alive and re-run, wasting work. If your steps are that long, raise
  `FACTORY_ENGINE_LEASE_TIMEOUT_SECONDS` above the longest step.

**Manual requeue (last resort, requires DB access):**

```sql
-- Confirm it is genuinely stuck first (no live worker, updated_at old):
SELECT id, status, current_step_index, updated_at FROM pipeline_execution WHERE id = '<uuid>';

-- Return it to the queue. The guarded step-advance in the engine makes a late-returning
-- original driver a no-op, so this is safe even if the worker is not actually dead.
UPDATE pipeline_execution SET status = 'PENDING', next_run_at = now() WHERE id = '<uuid>' AND status = 'RUNNING';
```

**Symptom: a step keeps failing and lands in `FAILED_ESCALATED`.** It has an escalation
review in `/reviews`. Investigate the error (shown in the review package and the audit
timeline). Then either **reject** (→ `CANCELLED`) or fix the underlying cause and
**approve** — approving resets that step's retry budget and re-runs it from the same step.

## Draining for a deploy / rollout

Graceful shutdown (OPS-2) is enabled. On `SIGTERM`:

1. `ExecutionPoller` stops claiming new work immediately (on `ContextClosedEvent`).
2. The web server drains in-flight HTTP for up to
   `spring.lifecycle.timeout-per-shutdown-phase` (default **60s**).
3. The engine pool finishes already-dispatched steps for up to
   `factory.engine.shutdown-await-seconds` (default **30s**); anything still running past
   that is abandoned and recovered by the lease reaper on the new instance.

**Set the container `terminationGracePeriodSeconds` ≥ 60 + 30 = ~90s**, or the
orchestrator will `SIGKILL` mid-drain (correctness holds — the reaper recovers — but
recovery is slow). A long Karate step in flight at shutdown will exceed the 30s drain and
be re-run after the lease; this is the intended bounded-deploy tradeoff.

## Cost controls (COST-1)

- **Dedup** (`factory.limits.dedup.*`, on by default) collapses re-fired identical
  triggers within the window onto the first execution; look for `EXECUTION_DEDUPED` audit
  events if a run you expected didn't start. **Manual triggers are exempt**: a deliberate
  `POST /api/triggers/manual` is never silently collapsed unless the caller opts in by
  sending the optional `dedupKey` request field (trimmed; max 255 chars, else 422; blank
  treated as absent).
- **Concurrency cap** (`factory.limits.max-concurrent-executions`, default 8) bounds
  simultaneous `RUNNING` executions; excess waits in `PENDING`. **The cap is
  per-instance**: the count-then-claim is atomic within a single JVM's single-threaded
  poller, but N horizontally-scaled instances each enforce their own cap, so total
  fleet concurrency can reach `cap × instances`. Milestone 1 targets a single instance;
  for multi-instance, size the cap as `desired_total / instances`, or treat a
  distributed cap (advisory lock / partitioned claiming) as a follow-up. On a clean
  restart the startup lease-reclaim (`factory.engine.reclaim-running-on-startup`, on by
  default, **single-instance only**) requeues crashed `RUNNING` rows so they don't
  consume cap slots — disable it when running multiple instances.
- **Daily budget** (`factory.limits.max-executions-per-day`, default 200) — once reached,
  new triggers get **HTTP 429** and an `EXECUTION_BUDGET_EXCEEDED` audit event. Raise the
  limit or wait for the UTC day boundary. (This is a spend guardrail; a legitimate burst
  that hits it means the budget is too low for real traffic.) Like the concurrency cap,
  the check is check-then-act, so a concurrent burst — or one event fanning out to
  several flows on the last slot — can overshoot the budget by a few executions; the
  enforcement is all-or-nothing per trigger event, never partial.

## Retention (DATA-1)

Off by default (`factory.retention.enabled=false`) — **the database grows without bound
until you turn it on.** When enabled, `RetentionService` purges terminal execution trees
(`COMPLETED`/`REJECTED`/`CANCELLED` and descendants) older than `ttl-days` on the
`run-cron` schedule, in batches of `batch-size`. `FAILED_ESCALATED` runs are **never**
purged (resumable). **`audit_event` is never purged** — it is the append-only compliance
record; purges are themselves audited as `RETENTION_PURGED`. Enable it in any long-lived
deployment and confirm the nightly `Retention purge removed …` log line.

## Connector degradation

Unconfigured connectors are expected: the flow still completes and every skipped external
sync is audited `CONNECTOR_SKIPPED` and counted in
`factory_connector_actions_total{outcome="skipped"}`. A connector configured but
**failing** shows `outcome="failed"` — that is a real problem (bad credentials, outage);
the finalizer records it and continues rather than failing the whole run, so watch the
metric, not just the execution status.
