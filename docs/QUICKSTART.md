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
The wrapper uses `.factory/cache/host-maven-repository`, so it does not depend on a
writable global Maven cache. `stop` and `infra down` preserve data.

The launcher rejects a duplicate process recorded by `.factory/run/factory.pid`
and also refuses to start if another healthy Factory instance owns the configured
port. External connector writes (Jira, GitHub, TestRail) are off by default, even
if connector credentials exist in the shell; only an explicit
`FACTORY_EXTERNAL_WRITES_ENABLED=true` enables them. Jira reads do not need it.

Live mode is explicit and is not part of the offline baseline:

```bash
ANTHROPIC_API_KEY=... ./scripts/factory start --mode live
```

It fails before application startup when the key is missing. Live provider
diagnostic calls remain deferred and opt-in; none of the baseline validation
commands makes a provider call.

### Operational notes for live execution

- **Z.ai endpoint selection:** Z.ai Coding Plan credentials require the Coding Plan endpoint (`FACTORY_MODEL_BASE_URL=https://api.z.ai/api/coding/paas/v4`), while General API credentials use `https://api.z.ai/api/paas/v4`. The system does not silently convert between them.
- **Gateway attempt budget lifecycle:** each Developer Flow execution gets its own model-call budget. Factory gives the coding runtime an execution-scoped credential (`fx1.<executionId>.<HMAC of the run token>`); the gateway counts attempts per execution (`FACTORY_MAX_ATTEMPTS`, default 40), shared by that execution's coding and its bounded repair, and ends an execution's credential `FACTORY_EXECUTION_TTL_SECONDS` (default 14400) after that execution's first model call. Sequential or parallel executions do not affect each other, and a task resumed after a long pause is not limited by how long Factory has been running. No restart is needed between tasks. Budgets are held in gateway memory; `start --mode pi` rotates the run token and starts new budgets.
- **Sandbox Maven cache:** sandboxes share one trusted Maven repository, `.factory/cache/sandbox-maven-repository`, mounted at `/maven-repository`. Only trusted preparation on the authoritative base (the baseline sandbox) mounts it read-write and uses it as its local repository. Coding, repair, verification, re-verification and export sandboxes mount it read-only and, before any other command runs, copy it into the sandbox-private `/workspace/maven-repository`, which Maven uses (`MAVEN_ARGS=-Dmaven.repo.local=/workspace/maven-repository`) and which is removed with the sandbox. Dependencies a candidate adds are downloaded into that private copy only, so a candidate cannot change what a later verifier or the host trusts. A read-only Maven tail (`maven.repo.local.tail`) is not used: FOLIO poms such as folio-module-sidecar build `-javaagent:${settings.localRepository}/...` paths that must exist in the local repository itself. The copy takes about 2 s for a 250 MB repository and grows with the repository. Factory's own `./mvnw` build uses the separate `.factory/cache/host-maven-repository` (`.mvn/maven.config`) and never reads the sandbox repository. To clear a cache, stop Factory and remove the directory; the next build downloads dependencies again. The old shared `.factory/cache/maven-repository` is no longer used and can be deleted. Sandboxes have no CPU or memory cap of their own and use what the Docker daemon provides.


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

## Run a task from a Jira issue key

Factory can start the Developer Flow from a Jira issue key. It reads the issue
(description, status, type, project, components, labels, story points, an
explicit acceptance-criteria field when one exists), the 20 newest comments,
the requirement/scope/status history, the parent, subtasks and direct links
(one hop only; up to 20 links, of which 10 are read for their description).
It stores the result as an immutable snapshot under
`.factory/data/jira-snapshots/<ISSUE>/<sha256>.json`, attaches it to the
execution as `jira-snapshot.json`, and admits the task through the same
resolution and admission path as `submit`.

Configuration (read by the Factory JVM only; never passed to sandboxes or the
coding runtime):

```bash
export FACTORY_CONNECTORS_JIRA_BASE_URL=https://folio-org.atlassian.net
# Optional: only needed for issues that are not publicly readable.
export FACTORY_CONNECTORS_JIRA_EMAIL=me@example.org
export FACTORY_CONNECTORS_JIRA_API_TOKEN=...
./scripts/factory start --mode pi
```

Start a task:

```bash
./scripts/factory run-jira MODSIDECAR-207
./scripts/factory run-jira MODSIDECAR-207 --delivery-mode DELIVER_PR --verification-plan java-maven-verify-it
./scripts/factory run-jira MODSIDECAR-207 --base-ref master --run-key retry-2
```

or open `http://127.0.0.1:18080/jira` ("Run Jira Task") in the UI, or call
`POST /api/dev/tasks/jira` with `{"issueKey": "MODSIDECAR-207", "deliveryMode": "LOCAL_ONLY"}`.
The response names the issue, execution id, resolved repository and revision,
and the outcome (`ADMITTED`, `ADMITTED_NEEDS_DECISION`, `BLOCKED`,
`NO_MATCHING_FLOW`). Jira or request errors (`JIRA_NOT_CONFIGURED`,
`ISSUE_NOT_FOUND`, `JIRA_ACCESS_DENIED`, ...) create no execution.

- The repository comes from the trusted catalog (Jira project and a single
  component). Conflicting evidence pauses for a repository decision; no
  approved match is `BLOCKED`. Without `--base-ref` the repository's own
  default branch is used.
- Jira never selects the verification plan. Name one with
  `--verification-plan java-maven-verify` (`mvn test`) or `java-maven-verify-it`
  (`mvn clean verify`); without it the execution pauses for a
  `VERIFICATION_PLAN` decision before coding. An execution pauses at most once:
  a task that pauses for another reason must name its plan.
- The root issue must state the task: a meaningful description or an explicit
  acceptance-criteria field. A summary-only or placeholder issue pauses for a
  `TASK_REQUIREMENTS_MISSING` decision; linked issues and comments do not count.
- Jira's returned key is the task identity; an old key of a moved issue is kept
  only as `requestedKey`.
- The task goal is the issue summary. Acceptance criteria are added only from an
  explicit Jira acceptance-criteria field; otherwise the list is empty and the
  coding runtime works from the bounded issue text (at most 32 KB).
- Running the same requirements (summary, description, acceptance-criteria
  field) on the same exact revision with the same plan and run key returns the
  existing execution, and the response reports that execution's repository,
  revision and snapshot. Status, labels, links, comments and history do not
  start a new execution; changed requirements, an advanced base revision, a
  different plan or a new `--run-key` do.
- Jira access is read-only. Factory never comments on, transitions, assigns or
  edits Jira issues; with external writes disabled every Jira write call is
  refused. There is no Jira write-back yet.

## Trusted delivery (branch / push / PR)

Delivery runs only in the Factory process, never in a coding sandbox, and only
for an independently verified candidate. Factory re-checks the candidate
identity (patch SHA-256, verified tree, base, repository), re-applies the frozen
patch to the exact base in a private temporary checkout, refuses unless the
reproduced tree equals the verified tree, then pushes the deterministic branch
`factory/<TASK>-<patchSha256[0:12]>` and opens one pull request against the
target repository's default branch. A repeated request reuses the same branch
and pull request; a different existing branch with that name is never
overwritten. Every attempt is recorded as `delivery.json`.

The delivery credential is separate from every other credential and is read only
by the Factory JVM:

```bash
export FACTORY_DELIVERY_GITHUB_TOKEN="$(gh auth token)"   # delivery scope only
export FACTORY_DELIVERY_GITHUB_FORK_OWNER=my-github-user  # optional: deliver to my fork
./scripts/factory start --mode pi
```

Without a fork owner the target is the authoritative repository itself; with
one, the target must be a GitHub fork of it whose default branch contains the
base revision. Without a token every delivery is refused before any remote call.

- Deliver a completed `SUCCESS` execution (no rebuild; uses stored evidence):
  `./scripts/factory deliver EXECUTION_ID`
- Deliver as part of the flow: submit with `deliveryMode: DELIVER_PR` (or
  `run-eval ... --delivery-mode DELIVER_PR`); the `deliver` step runs after the
  final verification, and `SUCCESS` then requires `DELIVERED`. Executions that
  are FAILED, BLOCKED_ENVIRONMENT, ERROR, CANCELLED or waiting for a decision
  are never delivered.
