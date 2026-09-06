# Inbox admission — identity, replay and operator decisions (T22)

Admission of a task file dropped into the dev-factory inbox is **idempotent and
content-addressed**. This note freezes the identity definition and the operator
rules for malformed and revised input.

## Identity of an admitted task revision

The admitted revision of a task is its **normalized content** — the eight parsed
fields (`id`, `repo`, `base`, `branch`, `goal`, `acceptance`, `constraints`,
`notes`) with parser defaults applied — never the file name, mtime or raw bytes.
The admission key is `file.inbox:<sha256-of-normalized-payload>`.

Consequences:

- **Replay** — re-dropping the same content (byte-identical or merely
  reordered/re-indented/commented, under any file name) returns the
  already-admitted execution. One admitted revision = one pipeline execution
  per matching flow. This covers crash replay (route committed, claim move
  failed → next poll re-routes the same admission), duplicate events and two
  pollers racing the same file: the unique `(flow_id, admission_key)` index on
  `pipeline_execution` (Flyway `V2__execution_admission_key.sql`) backs the
  promise; the loser of a race re-reads and returns the winner's execution.
- **Intentional new revision** — a change to the normalized content (revised
  goal, extra acceptance criterion, different branch, …) is a different key and
  is admitted as a **new execution** with its own full audit trail. Re-dropping
  a fixed/revised file needs no operator ceremony.

Manual initiations (`PipelineRouter.routeManual`), sub-flow children and all
legacy rows carry no admission key (`NULL` never collides in the unique index).

## Operator decisions

| Situation | Where it is visible | Operator decision |
| --- | --- | --- |
| Malformed/invalid task file (bad YAML, unknown key, bad ref, missing field) | moved to `failed/`, original bytes preserved; `WARN` log line `Task file … rejected` | Inspect `failed/`, fix the file, drop the corrected content again — a corrected file is a distinct revision and is admitted as a new execution. Nothing is deleted or silently discarded. |
| Route failed / no flow matched | moved to `failed/`, `WARN` log | After fixing the cause, re-drop the same file: identical content replays the existing admission (no duplicate); corrected content admits a new revision. |
| Crash or failed claim move after the database commit | file stays in the top-level inbox | No action needed: the next poll re-routes it as the same admission and completes the claim move. |
| Re-dropped duplicate (same content) | `INFO` log `replayed admission … returning existing execution` | None — exactly-once holds by design. |
| Which executions exist for a task revision | `pipeline_execution.admission_key` (queryable), plus `EXECUTION_STARTED` audit events whose detail carries `admissionKey` | Query by admission key to see the one execution per admitted revision. |

## Workspaces

Sandbox workspaces in local mode are **execution-owned** (`sbx-<execution id>`,
via `SandboxSpec.ownerId`): concurrent executions of the same task — e.g. two
revisions in flight — get distinct workspaces and cannot delete each other's.
This removes the shared-task-name collision; it is not an isolation boundary.
