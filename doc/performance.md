# Performance: DB connection pool sizing and load-test plan

This document explains how the PostgreSQL connection pool (HikariCP) is sized
relative to the engine's concurrency knobs, and how to load-test the poller.

It pairs with the explicit `spring.datasource.hikari.*` block in
`factory-app/src/main/resources/application.yaml`.

## Who borrows a connection

The pool is sized against **concurrency**, not request volume or step duration.
The long-running work in a step (the LLM call, a Karate run of up to 15 minutes)
runs **outside any database transaction**: `ExecutionEngine.advance()` is not
`@Transactional`, and `StateManager.heartbeat()` exists precisely because the row
is *not* held under lock while the worker runs. Connections are therefore borrowed
only for sub-second bursts (heartbeat, audit append, artifact insert, state
transition, step-cursor advance -- each a short `@Transactional` method on
`StateManager` / `ArtifactStore` / `AuditLog`).

Concurrent borrowers at peak:

| Borrower | Concurrency | Notes |
|---|---|---|
| Engine step executors | `factory.engine.worker-threads` (default **4**) | `factoryEngineExecutor` core=max=worker-threads; each thread does serial short txs |
| Poller / reaper scheduler | **1-2** | `@EnableScheduling` default `TaskScheduler` is single-threaded; `poll()` (claim) and `maintain()` (reap + sub-flow reconcile) never run concurrently with themselves |
| HITL UI / REST / webhook request threads | burst **~8** | `open-in-view: false`, so a connection is held only during repository calls, not the whole request |
| Flyway | **1-2** | Startup only, from this same pool, before traffic and before the poller does meaningful work -- overlaps nothing |

## Chosen defaults and rationale

```
maximum-pool-size = worker-threads(4) + scheduler(2) + web headroom(8) + flyway/misc(2) = 16
minimum-idle      = 4   (== worker-threads: keep the engine's steady-state baseline warm)
```

All values are env-overridable (see the table below). `16` gives the 4 engine
threads their own connections with room for the scheduler, a burst of operators
working the `/reviews` inbox and `/executions` timeline, incoming webhooks, and
startup Flyway -- without any of them queueing on connection acquisition.

Sizing follows from concurrency, so **the pool is not the ingest-throughput
lever.** Steady-state claim rate is bounded by the poller, not the pool:
`batch-size / poll-interval = 5 / 2s = 2.5 executions/s` moved from `PENDING` to
`RUNNING`. To raise ingest throughput, tune `factory.engine.batch-size` /
`poll-interval-ms` (and only then re-check the pool); enlarging the pool alone
does nothing. Do **not** change `worker-threads` to fix a pool problem -- if you
*do* raise `worker-threads`, raise `FACTORY_DB_POOL_MAX` by the same amount.

**Whenever step latency dominates (real LLM calls, Karate runs), the binding
ceiling is not the poller but the concurrency cap**:
`factory.limits.max-concurrent-executions` (default **8**) stops the claim loop
once 8 executions are `RUNNING`, so observed throughput is ~`cap /
avg-slot-hold-time` regardless of `batch-size`. In that regime
`FACTORY_LIMITS_MAX_CONCURRENT_EXECUTIONS` is the first knob to turn (see the
runbook's cost controls for its per-instance semantics).

### Timeouts

| Property | Default | Reasoning |
|---|---|---|
| `connection-timeout` | `30000` ms | Fail fast when the pool is exhausted rather than block a request/engine thread indefinitely; a timeout surfaces as a saturation signal instead of a silent stall. |
| `max-lifetime` | `1800000` ms (30 min) | Recycle connections below any downstream idle cap. Postgres imposes none by default, but PgBouncer / cloud proxies / firewalls do -- keep this a few seconds under theirs to avoid handing out a server-killed connection. |
| `idle-timeout` | `600000` ms (10 min) | Only relevant while `minimum-idle < maximum-pool-size`; trims the pool back toward `minimum-idle` during quiet periods. |
| `leak-detection-threshold` | `60000` ms (60 s) | No legitimate borrow lasts anywhere near a minute (longest is a single short tx / large-markdown artifact insert, well under a second). A hit means a genuinely leaked connection, logged with a stack trace -- not a false positive from the LLM/Karate step, which holds no connection. |

### Fixed-size pool (optional, production recommendation)

HikariCP recommends a **fixed-size pool** (`minimum-idle == maximum-pool-size`)
to eliminate connection-establishment latency spikes under bursty load. To adopt
it, set `FACTORY_DB_POOL_MIN_IDLE` equal to `FACTORY_DB_POOL_MAX`. The default
keeps `minimum-idle=4` for a lighter dev/idle footprint.

### Postgres `max_connections`

The Postgres default is `max_connections = 100` (minus a few reserved for
superusers). At `maximum-pool-size=16`, roughly **5 app instances** fit before
exhausting it. When scaling horizontally, either lower `FACTORY_DB_POOL_MAX` per
instance or raise the server's `max_connections`, and if a pooler (PgBouncer)
sits in front, keep `max-lifetime` under its `server_idle_timeout`.

## Environment overrides

| Environment variable | Property | Default |
|---|---|---|
| `FACTORY_DB_POOL_MAX` | `spring.datasource.hikari.maximum-pool-size` | `16` |
| `FACTORY_DB_POOL_MIN_IDLE` | `spring.datasource.hikari.minimum-idle` | `4` |
| `FACTORY_DB_POOL_CONNECTION_TIMEOUT_MS` | `spring.datasource.hikari.connection-timeout` | `30000` |
| `FACTORY_DB_POOL_MAX_LIFETIME_MS` | `spring.datasource.hikari.max-lifetime` | `1800000` |
| `FACTORY_DB_POOL_IDLE_TIMEOUT_MS` | `spring.datasource.hikari.idle-timeout` | `600000` |
| `FACTORY_DB_POOL_LEAK_DETECTION_MS` | `spring.datasource.hikari.leak-detection-threshold` | `60000` |

## Load-test plan

Goal: drive **N** concurrent executions through the poller and confirm the pool
is not the bottleneck (and find the point at which the *poller* is).

### Setup: lift the cost limits first

The defaults this branch ships are sized for production spend control, not load
testing, and will distort or halt the test:

- `FACTORY_LIMITS_MAX_EXECUTIONS_PER_DAY=0` (or above the total trigger count) —
  the default budget of 200 is exhausted after ~80 s of ingest at the 2.5/s claim
  rate, after which every manual trigger returns **429** (manual triggers carry no
  dedup key, so every POST counts).
- Raise `FACTORY_LIMITS_MAX_CONCURRENT_EXECUTIONS` (or set `0` to disable) —
  otherwise in-flight executions plateau at 8 and the poller/pool ceiling you are
  trying to measure is never reached. Measurement 4 below (engine-executor queue
  backpressure) is unreachable at the default cap.

### Driver

Each `POST /api/triggers/manual` with the inline sample creates one `PENDING`
execution:

```bash
# Fire N concurrent triggers (N=50 shown). Zero connector credentials => advisory
# mode: every external sync is audited CONNECTOR_SKIPPED, so nothing blocks on Jira/
# GitHub/TestRail and the run exercises only the engine + DB.
seq 50 | xargs -P 50 -I{} curl -s -o /dev/null -X POST localhost:8080/api/triggers/manual \
  -H 'Content-Type: application/json' \
  -d @factory-app/src/main/resources/samples/sample-story-inline.json
```

The LLM call still needs `ANTHROPIC_API_KEY` and its latency will dominate wall
time. To load-test the **engine + DB** in isolation (no tokens, no network
variance), drive the same volume through the test harness's scripted
`StubChatModel` (as `TestFactoryEndToEndTest` does) so step latency is near-zero and the
pool/poller are the only contended resources.

### What to measure

Expose metrics for the run (OBS-1 wires this permanently; for an ad-hoc test add
`metrics` to `management.endpoints.web.exposure.include`) and read
`GET /actuator/metrics/<name>`:

1. **Claim latency / throughput** -- time from execution row created (`PENDING`)
   to `RUNNING`. Expect a staircase bounded by `batch-size`/`poll-interval`
   (~2.5/s at defaults). If this is your ceiling, tune the engine knobs, not the
   pool.
2. **Pool saturation** -- the pool is the bottleneck iff these move:
   - `hikaricp.connections.pending` -- threads blocked waiting for a connection;
     should stay ~0. Sustained > 0 = undersized pool.
   - `hikaricp.connections.active` vs `hikaricp.connections.max` -- pinned at max
     under load = fully utilized.
   - `hikaricp.connections.acquire` (timer, p99) -- acquisition latency.
   - `hikaricp.connections.timeout` (counter) -- any increase means borrowers hit
     `connection-timeout`; raise `FACTORY_DB_POOL_MAX` (and check Postgres
     `max_connections`). Vendor-neutral equivalents: `jdbc.connections.active` /
     `.idle` / `.max` / `.min`.
3. **Step throughput** -- rate of `STEP_COMPLETED` audit events and end-to-end
   execution completion rate (`EXECUTION_COMPLETED`), from the audit log or
   `/executions`.
4. **Engine-pool backpressure** -- `factoryEngineExecutor` has queue capacity 200
   and `CallerRunsPolicy`; when the queue fills, `poll()` runs a step inline on
   the scheduler thread, which stalls claiming. Watch for `poll()` cadence
   slipping past `poll-interval-ms` and claim throughput dropping -- that is engine
   saturation, distinct from pool saturation.

### Pass criteria

- `hikaricp.connections.pending` ~= 0 and `hikaricp.connections.timeout` flat at
  the target concurrency (N >= worker-threads).
- No leak-detection warnings in the log (borrows stay well under 60 s).
- Claim + step throughput scale with `batch-size` / `worker-threads`, confirming
  the DB pool is provisioned ahead of the engine, not behind it.
