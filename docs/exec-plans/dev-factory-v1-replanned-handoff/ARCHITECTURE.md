# Minimal architecture

Normative for plan `pi-minimal-v2`. Names marked “new” are implementation targets, not claims that those classes already exist.

## 1. One flow, one operator, one attempt

```text
existing task admission / explicit repository + SHA + trusted profile
  → prepare [deterministic]
  → Pi coding worker → CodingRunner → native Pi RPC [one process/attempt]
  → freeze/export [Factory-owned]
  → verify [fresh environment, deterministic]
  → finalize [outcome + artifact/usage summary]
```

Use the existing modular monolith, engine, PostgreSQL and artifact interfaces. Host JVM plus Compose infrastructure remains the default; do not re-containerize the control plane. Pi and build tools run inside Docker managed through the existing docker-java client. A small trusted gateway keeps the upstream key out of that container. [P1](SOURCES.md)

**New flow identity:** `dev-factory-pi`, with trigger `file.inbox.pi`. Add an operator-selected inbox event type, defaulting to the existing `file.inbox` for compatibility; Pi mode selects the new trigger. Never route one submission to both flows. Keep the old descriptor/worker for existing regressions and history; do not migrate active old executions or silently select the legacy harness on Pi failure. The current inbox hardcodes its event type, so this is a justified flow-local configuration change. [R4](SOURCES.md)

One application instance, one active execution, one worker. Set the new flow's `max_attempts: 1`; Pi alone owns its configured agent-level retries. Disable automatic re-launch after an interrupted Pi attempt. A small execution-scoped launch marker in the trusted persistent run directory prevents a repeated delivery from launching paid coding again. After interruption, retain the candidate and require an explicit new runKey; no conversation resume, failover or exactly-once claim. Configure any existing stale-execution threshold above the enforced task deadline so a legitimate active run is not reclaimed early. Pi-mode startup must stop/retain orphaned workloads belonging to this persisted Factory instance before admitting new work; never destroy their only evidence or touch other instances.

## 2. Ownership and replaceability

**Factory owns:** admission and contract; source and trusted profile; container/network/resource lifecycle; deadlines/cancellation; gateway policy; frozen candidate; required independent verification; artifact publication; telemetry normalization; final result.

**Pi owns:** model/provider interaction, native read/search/edit/write/bash tools, iterative debugging, exploratory tests, compaction, session state and agent self-review.

```text
CodingRunner.run(request, eventSink, cancellation) → CodingAttempt

request:
  executionId, attemptId, immutable task/contract view,
  preparedSandbox, repoPath, baseRevision,
  runtime/model configuration reference, instruction manifest,
  deadline/budget, trusted artifact destination

attempt:
  stopReason, processExitCode?, settled,
  session/agentReport/event references, usageCoverage, diagnostic references
```

The boundary must not expose Pi event classes to orchestration, expose a Docker client to Pi, or return final task `SUCCESS`. Preserve runtime-native events under references; normalize only fields the product uses. Do not create a hierarchy of runtime capabilities, a second agent SDK, TypeScript bridge, custom tool dispatcher or compactor.

Add one **sandbox process-session capability** beside blocking `SandboxService.exec`: argv/cwd/whitelisted env, writable stdin, separate stdout/stderr, awaitExit, graceful termination, whole-workload kill. Prove docker-java full-duplex transport with real Pi before building surrounding machinery. Blocking string-based exec cannot serve as its RPC transport. [P1, R5](SOURCES.md)

## 3. Where changes belong

| Area | Decision |
|---|---|
| `factory-flow-dev-factory` | New flow, prepare/Pi/verify/finalize workers and runner adapter; reuse existing task/profile/resolution objects |
| `factory-sandbox` | Small process-session and prepared-source/container lifecycle additions; reuse DockerClient and suitable short-command APIs |
| `factory-app` | Pi-mode wiring, effective configuration, existing API/report surface and thin launcher commands |
| `factory-core` | Reuse unchanged unless a reproducer demonstrates a blocker; no new orchestration platform |
| Custom `CodingHarness` / legacy workers | Freeze as legacy, retain tests, no primary Pi path and no silent fallback |
| Existing exporter/recovery helpers | Reuse proven semantics; extract a small shared helper when needed, not the entire legacy worker lifecycle |

Do not force every error through the old `HarnessReport`. The current CodingWorker directly depends on CodingHarness and intentionally blocks M1 intents awaiting preparation; wire the new worker to actual preparation instead of forging readiness flags. [R6](SOURCES.md)

## 4. Necessary M1-to-Pi adaptations, not another M1

Inspected M1 has useful code but also assumptions that would otherwise recreate the old roadmap. [R7–R8](SOURCES.md)

- Its freeze guard requires `dependencySeedHash`. Introduce an explicit **PRIVATE_FRESH_RESOLUTION** preparation mode for the Pi unit profile. This mode has no seed, records resolver configuration/baseline evidence and permits an absent seed hash. Legacy frozen-seed records retain their strict checks. Use a versioned/additive representation; never substitute a fake hash or globally remove validation.
- Its generic profile hardcodes ARM64, `./mvnw`, an image without Pi and execution network `NONE`. Add a distinct **`java21-pi-unit`** trusted profile, populated with the actual image/platform, approved build command and **GATEWAY_ONLY** network policy. Existing profiles/tests remain intact.
- Its freeze method treats every residual unknown as material. Preserve descriptive unknowns, but distinguish them from missing evidence required to execute the selected profile. Prove unit-suite Docker requirements through its baseline; do not infer all repository capabilities from one passing unit suite.
- Register the specific **`modsidecar-208-v1`** verification plan. Do not pretend the generic `java-maven-verify` ID proves that integration tests ran; its inspected command is only `test`.
- Pi mode must not require an Anthropic key or initialize a paid Spring AI client merely to satisfy unused legacy beans. Use conditional wiring or the existing inert scripted compatibility bean. Legacy/offline behavior stays tested.

## 5. Source, environment and candidate

Prepare public, approved source at the exact SHA through a trusted fetch helper, without running target build scripts on the host. Supply no future refs, solutions, credential-bearing remotes or Factory checkout. Verify the checkout/source identity before Pi starts. Never replace an unavailable historical base with current HEAD.

Use the accepted non-root/read-only-root container settings, private writable workspace/HOME/state/cache, cap-drop, no-new-privileges and CPU/RAM/PID bounds. No host Docker socket, developer HOME, SSH agent, upstream API key or GitHub/Jira credentials. No shared writable Maven cache. Named volumes/private per-run data are not permission to mount arbitrary host directories.

Start with fresh dependency resolution through approved read-only gateway routes for baseline and verification. A seed/cache service and bit-reproducible dependencies are **not** prerequisites. Record environment/source versions and baseline evidence; acknowledge that historical SNAPSHOT dependencies can drift. Do not upgrade the target repository to repair an unavailable baseline.

After normal completion or interruption: collect available runtime diagnostics, stop **all** coding processes, confirm container stopped, then snapshot through the Docker archive API or an isolated trusted exporter. Do not exec in the stopped coding container. Export with trusted Git metadata at the original base, capturing additions/untracked files, deletions, modes, binary data and model commits. Never trust the candidate's `.git` hooks/config/index or execute repository scripts during export.

Persist patch/manifest before destructive cleanup. Retain stopped resources if preservation fails. A cleanup failure becomes separate evidence, not a new model call. Use existing artifact storage plus ordinary per-run files and a final manifest; no new blob database or cross-store transaction system.

## 6. Independent result and visibility

Verifier starts from clean base + **the exported patch**, with fresh build state/cache and no model key. Preparation/verifier gateway instances expose dependency routes only, with no provider key, model route or coding token; never reuse the coding container/network as the verifier. Trusted commands/checks are outside model ownership. Check resulting tree/patch identity, real fresh XML reports and mandatory suite execution. No-op is a failure unless explicitly allowed. An empty patch is zero bytes, not the legacy textual “(no changes)” sentinel. Missing checks or evidence cannot become success.

Final result records `outcome`, `reason`, `failedStage`, candidate/verification references, usage coverage and cleanup state:

| Outcome | Meaning |
|---|---|
| SUCCESS | Complete candidate passed all required independent checks; eligible for review only |
| FAILED | Completed checks demonstrate task/policy failure, including forbidden no-op |
| INCOMPLETE | Required preparation, infrastructure, evidence or checks unavailable; includes budget exhaustion |
| ERROR | Adapter/protocol/Factory execution error, with diagnostics and any partial candidate |
| CANCELLED | Explicit operator cancellation; partial artifacts retained |

Keep technical engine state separate. A preparation/admission BLOCKED reason maps to an INCOMPLETE final assessment when an execution exists; otherwise preserve the admission receipt. Cancel/error/incomplete attempts never get SUCCESS merely because a partial patch passes some tests.

Use a small persistent directory per execution/attempt, indexed through existing artifacts:

```text
input.json                 config.json / instructions.json
attempt.json               candidate.patch / candidate.json
rpc.jsonl                  events.jsonl / pi-session.jsonl
upstream-requests.jsonl     usage.json
verification.json          verification-logs/ and XML reports
result.json                summary.md / manifest.json
```

Names are the new flow's contract; preserve legacy artifact aliases only where an existing consumer actually needs them. The manifest indexes exact files/hashes and is published last; consumers do not treat a partial directory as a completed candidate. No cross-store crash-atomicity is claimed. Provider usage is nullable, immutable evidence; an estimate is not a billed charge. See PI_INTEGRATION.md for aggregation rules.
