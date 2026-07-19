# Backup & Disaster Recovery — FOLIO AI SDLC Factory

The Factory keeps all durable state in **one PostgreSQL database**: executions,
immutable artifacts, HITL reviews, the flow registry mirror, and — most importantly —
the **append-only audit log**. There is no other source of truth; the app is otherwise
stateless (restart-safe by construction, all queue state in the DB). Protecting that
database is the whole DR story.

## What must survive

| Table | Nature | Why it matters |
|---|---|---|
| `audit_event` | **Append-only** (a DB trigger forbids `UPDATE`/`DELETE`) | The compliance record of every step, artifact write, HITL decision, connector action, escalation, and retention purge. This is the highest-value data. |
| `artifact` | Insert-only, versioned | The generated work product (test plans, scripts, results, sync reports); immutable history. |
| `pipeline_execution` | Mutable status, immutable identity | In-flight orchestration state; loss strands running work (recoverable by re-triggering, but the audit trail of the lost run is gone). |
| `hitl_review` | Reviewer decisions | Who approved/amended/rejected what. |
| `flow_registry` | Rebuildable | Mirrored from the YAML descriptors at startup — not critical to back up, but harmless to include. |

> **Append-only in the DB is not durability.** The audit trigger stops the *application*
> from mutating history, but a lost, corrupted, or ransomwared database takes the audit
> log with it. Off-box backups are what make the compliance record durable.

## Backup strategy

Run **both** of the following against the primary:

1. **Point-in-time recovery (PITR)** — the real RTO/RPO lever. Enable WAL archiving
   (`archive_mode=on`, ship WAL to object storage) plus periodic base backups
   (`pg_basebackup`). This recovers to any second, so RPO ≈ the WAL shipping interval
   (seconds–minutes). Managed Postgres (RDS/Cloud SQL/Azure) gives you this out of the
   box — turn on automated backups + PITR and set the retention window.
2. **Logical dumps** — a nightly `pg_dump` (see [`scripts/pg-backup.sh`](../scripts/pg-backup.sh))
   as a portable, restore-testable, cross-version safety net alongside PITR. Keep dumps
   in versioned, access-controlled object storage.

Suggested targets (tune to your compliance requirements):

| Metric | Target | Driven by |
|---|---|---|
| **RPO** (max data loss) | ≤ 5 min | WAL archive interval (PITR) |
| **RTO** (time to restore) | ≤ 1 h | base-backup size + restore automation |
| Audit retention | ≥ your legal/compliance window | keep audit backups even after execution rows are retention-purged |

## Interaction with retention (DATA-1)

The retention purge deletes **execution/artifact/hitl_review** rows for old terminal runs
but **never `audit_event`**. So the live DB stays bounded while the audit history keeps
growing. Two consequences:

- Back up `audit_event` on a cadence that matches its growth; it is the table that
  accumulates indefinitely.
- Consider **shipping audit events to append-only external / WORM storage** (object-lock
  bucket, log pipeline) as a follow-up, so the compliance record survives total loss of
  the primary database independent of DB backups. (Not implemented yet — tracked as a
  hardening follow-up.)

## Restore drill (do this before you need it)

Restores that have never been tested are not backups. Schedule a **quarterly** drill:

1. Provision a scratch Postgres (`docker compose up -d` works for a functional drill).
2. Restore the latest dump: `scripts/pg-backup.sh` documents the matching
   `pg_restore`/`psql` invocation; for PITR, restore a base backup + replay WAL to a
   chosen timestamp.
3. Point a Factory instance at the restored DB (`FACTORY_DB_URL`) with
   `spring.flyway.enabled=true`. Flyway must report the schema already at the latest
   version (no pending migrations) — a mismatch means the dump and the app version drifted.
4. Verify: `/executions` lists historical runs, artifact content renders, and the audit
   timeline is intact. Spot-check that `audit_event` row counts match the source.
5. Record the measured restore time; if it exceeds the RTO target, automate more of it.

## Recovery scenarios

- **App instance lost:** no data action — bring up a new instance against the same DB.
  In-flight `RUNNING` work is recovered by the lease reaper (see the runbook).
- **DB corrupted / lost:** restore via PITR to the last good point (or the latest dump),
  then start the app. Executions that were `RUNNING` at the recovery point resume from
  their last committed step (new artifact versions on re-run).
- **Accidental data deletion:** because artifacts and audit are immutable/append-only in
  the app, the only in-app deletion path is the retention purge. If a purge removed
  something prematurely, recover those rows from backup (the `RETENTION_PURGED` audit
  event tells you exactly what was purged and when).

## Do NOT

- Do not `pg_dump --clean`/restore over a live production DB.
- Do not disable the `audit_event` trigger to "clean up" — it is the integrity control;
  use retention (which is trigger-aware and skips audit) instead.
- Do not store backups in the same blast radius (same account/region/keys) as the primary.
