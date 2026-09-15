# Factory Offline Quickstart

Prerequisites: Java 21 and Docker Desktop (or another Docker engine with Compose).
No model API key is needed, and the offline profile cannot create a paid model client.

```bash
./scripts/factory init
./scripts/factory infra up
./scripts/factory doctor --offline
./scripts/factory start --mode offline
curl -fsS http://127.0.0.1:18080/actuator/health
./scripts/factory stop
```

Factory runs as a host JVM process. Compose runs only PostgreSQL. Both ports are
bound to `127.0.0.1`. Configuration, logs, artifacts, recovery bundles, and the
PostgreSQL data directory live under the gitignored `.factory/` directory.
The wrapper uses `.factory/cache/maven-repository`, so it does not depend on a
writable global Maven cache. `stop` and `infra down` preserve data.

The launcher rejects a duplicate process recorded by `.factory/run/factory.pid`
and also refuses to start if another healthy Factory instance owns the configured
port. Local offline and live profiles disable Jira, GitHub, and TestRail connector
writes even if connector credentials exist in the shell.

Live mode is explicit and is not part of the offline baseline:

```bash
ANTHROPIC_API_KEY=... ./scripts/factory start --mode live
```

It fails before application startup when the key is missing. Live provider
diagnostic calls remain deferred and opt-in; none of the baseline validation
commands makes a provider call.

### Operational notes for live execution

- **Z.ai endpoint selection:** Z.ai Coding Plan credentials require the Coding Plan endpoint (`FACTORY_MODEL_BASE_URL=https://api.z.ai/api/coding/paas/v4`), while General API credentials use `https://api.z.ai/api/paas/v4`. The system does not silently convert between them.
- **Gateway attempt budget lifecycle:** The gateway enforces a per-process attempt budget (`FACTORY_MAX_ATTEMPTS`, default 40). Between sequential real coding runs, restart Factory (`./scripts/factory stop && ./scripts/factory start ...`) so the gateway attempt counter resets.


Run the classified test suites separately:

```bash
./scripts/factory validate --unit
./scripts/factory validate --integration
./scripts/factory validate --eval-pack
```

`--unit` excludes Docker, eval-pack, and live tags. `--integration` requires a
working Docker daemon and selects all PostgreSQL/Testcontainers and real sandbox
tests. `--eval-pack` runs the scripted hermetic evaluation pack, including its
three required regression, counterexample, and summary scenario classes. Every
command fails if it selects zero tests and writes its full log and test selection
under `.factory/logs/validation/`.

## Resolve and submit a development task

Factory accepts UTF-8 YAML or JSON up to 256 KiB. A v1 task names exactly one
`baseRevision` (a full 40-character SHA) or `baseRef` (a branch). It does not
default to the repository's current or default branch.

```yaml
schemaVersion: 1
source:
  type: JIRA
  id: MODSIDECAR-208
  project: MODSIDECAR
repository: folio-org/folio-module-sidecar
baseRevision: c13e0383d9283ef554c195cf5357bb3c6eeb4e65
runKey: first
deliveryMode: LOCAL_ONLY
goal: Add the accepted thread-pool setting.
acceptanceCriteria:
  - id: AC-1
    text: The configured default is 8.
    source: TICKET
constraints: {}
notes: Preserve the complete task text.
```

With Factory running, inspect the deterministic repository/revision/profile
decision without submitting it:

```bash
./scripts/factory resolve-task task.yaml
```

Submit through an atomic `.partial` to final rename:

```bash
./scripts/factory submit task.yaml
```

The same semantic task and `runKey` reuses the database admission. Change
`runKey` for an intentional new experiment. Invalid files receive a reject
receipt. A task with a material open decision (a declared `decisions` entry, or
repository evidence naming more than one repository) is admitted into the
`dev-factory-pi-decision` flow, which persists `decision-request.json` and pauses
at the `developer-decision` HITL gate before any coding; answer it with
`./scripts/factory decide`, and the execution resumes from stored state. M1 resolves and stores
intent but does not run it: coding remains blocked until later preparation has
produced real source, image, dependency-seed, and baseline references.
