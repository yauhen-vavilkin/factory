# Developer Flow: UI (yarn + Jest) support — plan

Status: draft for review · Pilot: [UIU-3606](https://folio-org.atlassian.net/browse/UIU-3606) on `folio-org/ui-users`

## Goal and scope

Let Developer Flow implement tickets in FOLIO `ui-*` modules that test with **Jest**, with the
same guarantees as backend tasks: trusted starting build, sandboxed coding, frozen candidate,
independent verification on a fresh checkout, publication only of the verified patch.

In scope: yarn v1 modules whose `yarn test` runs Jest through `@folio/jest-config-stripes`.

Out of scope:
- BigTest/Karma modules (e.g. `stripes-components`, `stripes-core`, `stripes-smart-components`).
- Visual / E2E checks (Playwright, screenshots, a running Stripes platform).
- Tickets that span a backend and a UI repository.
- Lint. The trusted check is install + tests only; lint stays with upstream CI and PR review.

## Facts this plan is based on

Measured 2026-10-23 on Windows/Node 24 (CI uses Linux/Node 22 — numbers are indicative),
standalone clone, CI-equivalent commands.

| Module | `yarn.lock` | Install cold / warm | Jest | Tests | node_modules / yarn cache |
|---|---|---|---|---|---|
| ui-myprofile | no | 119 s / 27 s | 29 s | 45 | 0.5 / 1.0 GB |
| ui-users | yes | 145 s / 42 s | 115 s | 1642 | 1.1 / 2.4 GB |
| ui-orders | no | 126 s / 31 s | 137 s | 1204 | 0.5 / 1.1 GB |
| ui-inventory | no | 184 s / 39 s | 171 s | 1534 | 1.2 / 2.6 GB |

- **Standalone clone, not a workspace.** FOLIO's shared CI (`folio-org/.github` `ui.yml@v1`)
  tests each module alone: depth-1 clone, Node 22, `@folio:registry` →
  `https://repository.folio.org/repository/npm-folioci/`,
  `yarn install [--frozen-lockfile if yarn.lock exists] --ignore-scripts --non-interactive`,
  then `yarn test` with `JEST_JUNIT_OUTPUT_DIR=artifacts/jest-junit`. A hoisted workspace would
  make the result depend on sibling modules.
- **Evidence is already produced.** `@folio/jest-config-stripes` v3 and v4 set
  `reporters: ['jest-junit', 'default']`; every measured module wrote `junit.xml`.
- **JUnit alone hides broken suites.** On ui-myprofile `master`, 5 of 7 suites failed to load
  (`keyboardjs` ESM, fixed by the ui-claims#77-style bump to `jest-config-stripes` ^4), yet
  `junit.xml` reported `tests="4" failures="0" errors="0"`. With
  `JEST_JUNIT_REPORT_TEST_SUITE_ERRORS=true` a load failure is counted as an error (verified).
- **Flaky tests exist.** ui-users `OwnerSettings.test.js` hit the 5 s Jest timeout once in the
  full run and passed 6/6 alone.
- **Most modules have no lockfile** (3 of 4 measured). Some `.gitignore` files list `yarn.lock`,
  others (ui-orders) do not.

## Design

Introduce a **toolchain** per verification plan: `maven` (today, the default) or `yarn`. The
toolchain selects four things that are Maven-only today; everything else (intake, decisions,
freezing, receipts, delivery, PR feedback) is reused unchanged. All changes stay in
`factory-flow-dev-factory` and `factory-app` config — no `factory-core` changes.

| Concern | Maven today | Yarn toolchain |
|---|---|---|
| Test evidence | `SurefireEvidence` (`target/surefire-reports/TEST-*.xml`, one `<testsuite>` root) | New `JestJunitEvidence`: fresh `artifacts/jest-junit/junit.xml`, `<testsuites>` root, tests > 0, failures = errors = 0 |
| Dependency cache | `factory-dev-m2-cache`, `MAVEN_OPTS`, read-only seed (`DockerWorkloads`) | Yarn cache volume, `YARN_CACHE_FOLDER`; same rule: only the trusted starting build writes it |
| Build output | `MavenBaselineOutput` (summary, transient download detection, sanitizing) | Yarn equivalent: registry/network errors (`ETIMEDOUT`, `ECONNRESET`, `EAI_AGAIN`, HTTP 5xx from the registry) are transient |
| Failure kind | `VerificationFailure.classify` (Java compile / JUnit assertion text) | Jest: `expect(received)`/`Expected:`/`Received:` → `CANDIDATE`; suite load error → not repairable |

Trusted environment for every yarn workload (Factory-set, part of the plan identity):
`@folio:registry` (written to a Factory-owned `.yarnrc` under the workload `HOME`),
`YARN_CACHE_FOLDER`, `CI=true` (Jest then refuses to write new snapshots),
`JEST_JUNIT_OUTPUT_DIR=artifacts/jest-junit`, `JEST_JUNIT_REPORT_TEST_SUITE_ERRORS=true`.
Install always uses `--ignore-scripts --non-interactive`.

Proposed configuration (names to be settled in phase 0):

```yaml
factory.dev-factory:
  repositories:
    ui-users:
      source-repo: ${FACTORY_UI_USERS_SOURCE_REPO:folio-org/ui-users}
      base-branch: master
      build-image: node:22.22.0-bookworm              # ships yarn 1.22; same Node as the coding images
      verification-plan: node22-jest
      jira-projects: [UIU]
  runtime:
    plans:
      node22-jest: [sh, -c, "yarn install --frozen-lockfile --ignore-scripts --non-interactive && yarn test"]
    plan-toolchains:
      node22-jest: yarn                            # absent → maven
    yarn:
      cache-volume: ${FACTORY_DEV_FACTORY_YARN_CACHE_VOLUME:factory-dev-yarn-cache}
      folio-registry: ${FACTORY_DEV_FACTORY_FOLIO_NPM_REGISTRY:https://repository.folio.org/repository/npm-folioci/}
  delivery:
    targets:
      ui-users:
        repository: ${FACTORY_UI_USERS_DELIVERY_REPO:folio-org/ui-users}   # pilot: fork via .env
        base-branch: master
        authorized: true
```

Receipts: add `toolchain` (missing → `maven`, so historical receipts stay readable) and a
toolchain-neutral report count; keep the existing JSON field names, as `pr-feedback.md`
already requires. `VerificationPolicy.matches` also compares the toolchain and the trusted
yarn environment, so changing the registry invalidates old receipts.

Candidate freezing: `CandidateFreezer.GENERATED` becomes per toolchain. For yarn, exclude
`node_modules`, `artifacts`, `junit.xml`, `coverage`, and **untracked** `yarn.lock` /
`package-lock.json` — do not rely on each module's `.gitignore`. A tracked `yarn.lock` the
agent updates is a normal part of the patch.

## Phases

Each phase is one reviewable change with green `mvn verify`.

**Phase 0 — toolchain seam, no behavior change.**
`Toolchain` enum (`MAVEN` only) and `runtime.plan-toolchains` (plan id → toolchain; default
`maven`; a plan that is not configured, or an unknown toolchain, fails at startup).
`CandidateVerifier` picks its evidence reader with an exhaustive `switch`, so adding `YARN`
in phase 1 makes the compiler flag that one switch — nothing else is behind the setting yet.
The remaining Maven-only sites must be found and routed by hand in phases 1–3:
`VerificationFailure.classify`, `DockerWorkloads`/`MavenCache`, `BaselineBuild` and
`InitialCodingAttempt` (`MavenBaselineOutput`), `CandidateFreezer.GENERATED`, the coding
image, `PullRequestRenderer` evidence lines, and the Surefire-named receipt fields.
They are introduced in the phase that adds their yarn implementation: existing tests pin
the `DockerWorkloads`/`MavenCache`/`MavenBaselineOutput` signatures, and an abstraction shaped
around a single implementation would be guesswork.
Done: existing tests unchanged and green apart from failures already present on `main`
(see risks); defaults and binding covered in `DevRuntimePropertiesTest`.

**Phase 1 — yarn evidence and verification.**
`YARN` joins `Toolchain`. `JestJunitEvidence` reads one trusted path,
`artifacts/jest-junit/junit.xml`, and sums the per-suite counters (they equal the root
counters in every recorded report). It shares the hardened XML parser with `SurefireEvidence`
(`JunitXml`, `TestEvidence`), caps the file at 8 MiB (the largest recorded full run is
0.4 MB), accepts only a fresh file, and rejects a symlink at any path component. A suite that
fails to load (`tests="0" errors="1"`) counts as one executed, failed unit: it is a FAIL, not
invalid evidence, and it keeps `failures + errors ≤ tests`, which `VerificationReceipt`
enforces. Receipts gain `toolchain` (absent → `maven`, so stored receipts stay readable);
`VerificationPolicy` treats a toolchain change like a plan change. `surefireReportCount` keeps
its historical name and carries the primary report count for every toolchain. Failure
classification is per toolchain (Jest: `expect(received)`/`Expected:`/`Received:` →
repairable candidate failure; registry/network errors → environment; a timeout on its own
→ unknown, and a suite load error is never repairable, because any error rules out
`CANDIDATE`; a run that also contains an assertion failure is classified by that failure). PR text says "Jest reports". `required-reports` is
rejected at startup for yarn plans. The plan's argv must run Jest with
`JEST_JUNIT_OUTPUT_DIR=artifacts/jest-junit` and `JEST_JUNIT_REPORT_TEST_SUITE_ERRORS=true`;
phase 2 injects them as trusted workload environment.
Done: `JestJunitEvidenceTest` on recorded `junit.xml` fixtures (passing with skips, a real
timeout failure, the real suite-load-error report) plus zero tests, all-skipped, stale file,
oversized, malformed/DOCTYPE/XXE, wrong structure, missing and overflowing counters,
symlinks; verifier, policy, PR-text and config tests per toolchain. Verified on Linux (see
risks), including the symlink tests that Windows skips.

**Phase 2 — yarn workloads.**
`BuildEnvironment` (toolchain, cache volume, FOLIO npm registry) replaces the bare Maven
cache-volume argument of `DockerWorkloads`; `DevRuntimeProperties.environment(plan)` supplies
it from `runtime.yarn.cache-volume` / `runtime.yarn.folio-registry` (validated: the registry is
written into a shell command). Yarn workloads get a trusted environment — a `.yarnrc` with
the `@folio` registry, `YARN_CACHE_FOLDER`, `CI=true`, `JEST_JUNIT_OUTPUT_DIR`,
`JEST_JUNIT_REPORT_TEST_SUITE_ERRORS` — and keep the Maven rule: only the trusted baseline
writes the shared cache; coding and verification get a **private writable copy of a read-only
seed**, so untrusted code can never poison it. `Workload.stop()` moves `node_modules` out of
`/workspace` (a rename), so export holds sources and reports only; verification deletes
`artifacts/jest-junit` before it runs; the freezer ignores untracked top-level `artifacts/`,
`coverage/`, `junit.xml`, `yarn-error.log`, `yarn.lock` and `package-lock.json` for yarn
(tracked ones are kept; nested paths and Maven repositories are unaffected, so a Java package
named `artifacts` survives). `YarnBaselineOutput` reports install steps and results, drops the
hundreds of peer-dependency warnings, and treats only yarn's own `error …` lines with network
markers (`ETIMEDOUT`, `ESOCKETTIMEDOUT`, `ECONNRESET`, `ECONNREFUSED`, `EAI_AGAIN`, `ENOTFOUND`,
`socket hang up`, registry 5xx in both the plain and the backslash-escaped quote form) as
transient (engine retry). Classification reuses the same check and looks for network markers
only on yarn error lines; yarn's `info There appears to be trouble…` retry notice is shown as
progress but never classifies a failure, since the install may have recovered. The shared sanitizer now also redacts the token in `Authorization: Bearer …`.

Cache spike (ui-users, node:22, Linux VM, cache 2.6 GB / `node_modules` 1.3 GB):

| Strategy | Time |
|---|---|
| Cold install into an empty volume | 96 s |
| Warm: shared writable volume reused | 14 s |
| Read-only seed copied into the container, then install | 8 s + 15 s = 23 s |
| Read-only cache used in place (`--offline` or `--prefer-offline`) | 14 s |

Chosen: copy the seed. Using the read-only cache in place is 9 s faster and works, but fails
as soon as a candidate adds a dependency that is not in the seed (yarn must write to its
cache), and verification has to cope with exactly that.

End-to-end check on a real module (ui-orders `master`, Jest with `--maxWorkers=2`, empty
cache, a scripted stand-in for the coding agent; throwaway test, not committed): trusted
baseline 104 s install + 82 s tests, coding container 54 s (seed copy, `yarn install`, edit),
verification 156 s, 438 s in total; the frozen patch contained only the edit (the agent's
`yarn.lock`, `node_modules` and a planted `artifacts/jest-junit/junit.xml` were excluded) and
verification passed 1196 tests with a receipt of toolchain `YARN`.
Done: command-shape and validation tests for the yarn mounts, environment and registry;
Docker-gated tests on real containers (cache persists, seeded workloads cannot modify it,
`.yarnrc` and environment are present, export excludes `node_modules`); freezer, build-output,
retry and classification tests; the end-to-end run above.
Not covered here: Jest writes `PASS`/`FAIL` to stderr and the baseline progress observer reads
stdout only, so the test phase shows only yarn lines and heartbeats (merging stderr in the
plan with `2>&1`, or observing stderr in `Processes`, would show suite progress).

**Phase 3 — images and Compose** (implemented).
The existing coding images are extended, not duplicated: `factory-dev-pi:0.85.1-java21-node22`
and `factory-dev-claude-code:2.1.287-java21-node22`. Both Dockerfiles copy Node 22.22.0 and
yarn 1.22.22 (`/opt/yarn-v1.22.22`, linked into `/usr/local/bin`) from the pinned
`node:22.22.0-bookworm-slim` stage, which Pi already used for Node. This adds about 7 MB to the
Pi image (817 → 824 MB) and 146 MB to the Claude Code one (790 → 936 MB), keeps one image per
runtime, and needs no new settings, Java changes or Compose services: `CodingRuntime.image()`
and the coding config are unchanged, and backend-only installs build nothing extra. The Compose
checks now also require `node --version` = `v22.22.0` and `yarn --version` = `1.22.22`.
The tags changed on purpose: Compose builds only when the image is missing, so an old
`…-java21` image without yarn would otherwise be kept and UI coding would fail with
`yarn: not found`.

Compose also declares the `factory-dev-yarn-cache` volume (`FACTORY_DEV_FACTORY_YARN_CACHE_VOLUME`)
and passes `FACTORY_DEV_FACTORY_FOLIO_NPM_REGISTRY`; both are in `.env.example` and the README.

Trade-off, accepted: the coding container is the Maven image's Ubuntu base with a copied
Node, while the starting build and verification run on Debian `node:22.22.0-bookworm`. A
package that compiles native code (node-gyp needs Python/make/g++, which the Maven image
lacks) could install differently in coding than in verification; verification is independent,
so the agent's own test run is advisory only, and the prebuilt linux-x64 glibc binaries Jest
needs work on both. A Node bump now rebuilds the backend images too; split the images again
if a future toolchain needs conflicting tools. Use `node:22.22.0-bookworm` (not the floating
`node:22-bookworm`) as the UI `build-image` in phase 4 so all three stages run the same Node.

Verified: both images build without credentials with Podman's Compose provider and pass the
Compose checks. Docker-gated tests on Linux (harness recipe below), all green:
`ClaudeCodeDockerTest` (24, including two yarn tests: the real CLI in the extended image
with node and yarn, and a fixture repo — jest 29 + jest-junit, no lockfile — that goes trusted
starting build → real Claude Code session against a local Messages server (`yarn install`, a
new source and test file, `yarn test`) → export without `node_modules` → fresh verification
workload whose Jest report shows 2 tests and 0 failures; about 52 s), `PiCodingHttpContractTest`
(2, the second runs the same request in a yarn workload and checks node 22, yarn 1 and
`CI=true`), `DockerWorkloadsTest` (14) and `FeedbackDockerTest` (1). One full run's wall-clock
time (43 min instead of ~8) was inflated by the laptop sleeping; the yarn tests alone took the
same time as before.
The fixture goes through workloads rather than `CandidateVerifier` (which needs a Git remote);
the verifier's yarn paths are covered by its unit tests and the phase 2 run on ui-orders.

**Phase 4 — pilot: UIU-3606 on ui-users.**
Configure `ui-users` (has `yarn.lock`) with delivery to the fork
`FACTORY_UI_USERS_DELIVERY_REPO=Dmytro-Melnyshyn/ui-users` (set in `.env`; the fork's
`master` must contain the base SHA — delivery blocks otherwise); run the flow end-to-end.
Done: starting build passes on `master`; the coding attempt fixes the Patron/Keycloak dialog
regression (area: `src/views/UserEdit/UserEdit.js`, `src/components/Wrappers/withUserRoles.js`,
introduced by UIU-3586 PRs #3044/#3049) with updated tests; independent verification PASS;
PR opened on the fork; one PR-feedback round works. Record real container timings here.

**Phase 5 — modules without `yarn.lock`.**
The starting build generates a lockfile once and stores it as a Factory artifact (with its
SHA-256 in the readiness record). Coding and verification receive it as an untracked,
excluded file, so all three install the same dependency versions even while npm-folioci
publishes new snapshots. Verification installs with it seeded (frozen when `package.json` is
unchanged from base); the receipt records the lockfile hash.
Done: a no-lockfile fixture verifies with the pinned versions after a newer snapshot is
published; ui-orders or ui-myprofile runs end-to-end.

## Risks and open questions

- **Cache growth.** The shared yarn cache has no eviction and grows with every module (1–2.6
  GB each, with heavy overlap). Seeding costs about 3 s per GB (8 s for 2.5 GB), per coding
  and verification container. If it gets large, reset it by removing the volume while the
  stack is stopped; per-repository volumes remain an option.
- **Flaky tests** fail verification as `UNKNOWN`/`CANDIDATE`. Accepted for the pilot; the
  operator can request another check. Revisit (e.g. bounded re-run of failed suites only) with
  real data.
- **Container limits: memory.** Factory sets no memory cap on workloads. A first end-to-end
  run on ui-orders with Jest's default of one worker per core (7) and `--coverage` exhausted the
  8 GB Podman VM and froze it until it was restarted; with `--maxWorkers=2` the same run
  passed. The pids limit (512) was not a problem. Size the VM for the biggest module (the
  host here has 32 GB, so `memory=` in `.wslconfig` can be raised) and put `--maxWorkers=…` in
  the yarn plan's command; concurrent executions multiply the pressure.
- **Podman compatibility of the workload commands** (verified in phase 2 with the `docker`
  CLI against Podman's compatible socket): a `--workdir` missing from the image is created and
  a `--cap-drop ALL` container starts; all 14 `DockerWorkloadsTest` and `FeedbackDockerTest`
  pass on real containers. The native `podman create --workdir` does not create it, which is
  irrelevant here because Factory uses the Docker CLI.
- **Red `master`.** Modules on `jest-config-stripes` ^3 can fail to load suites (ui-myprofile
  today). The flow correctly blocks before model spend; such modules need the upstream bump
  first.
- **Coding agent setup.** The prompt stays tool-neutral; the agent must run `yarn install`
  itself in the coding container. Re-evaluate after the pilot whether a toolchain hint in the
  `CodingRequest` is needed.
- **Local test baseline on the pilot machine (Windows + Podman on WSL).** Full `mvn verify`
  needs a **rootless** machine with user-mode networking
  (`podman machine set --rootful=false --user-mode-networking`) and
  `-DargLine=-Duser.timezone=UTC` (PostgreSQL 16 rejects the JVM's `Europe/Kiev`); no
  `DOCKER_HOST`, Ryuk or host-override settings are needed (≈4 min 20 s). Always build with
  `-am` (or run `mvn install -DskipTests` first): `-pl` alone picks up stale sibling jars from
  `~/.m2` and reports phantom failures. The rootful machine was unreliable: ports published
  via nftables redirects have no listening socket for WSL's `wslrelay` to forward, and
  VM-IP access worked only until the next break (cause of that break unconfirmed). Under
  rootless, ports published by containers are relayed by `wslrelay` on **IPv6 loopback only**
  (the VM's sshd is relayed on both): probe with `localhost` or `::1`, never `127.0.0.1`
  (`Test-NetConnection 127.0.0.1` reports a false "closed"). Verified on
  untouched `main` and on the phase 0 branch: green except 27 Windows-only failures — 8
  dev-factory unit tests (`CandidateFreezerTest`, `ClaudeCodeCodingRuntimeTest` ×3,
  `ProcessesTest` ×3, `SurefireEvidenceTest`) and 19 in `PrFeedbackIntegrationTest` (its
  setup cannot delete read-only git objects: `AccessDeniedException`) — plus a flake:
  `ExecutionEngineIntegrationTest.reaperReturnsStaleRunningExecutionsToPending` failed in 2 of
  3 full runs (`reapStale(0)` races a same-millisecond update). Phase work is judged against
  this baseline, not against zero failures. Rootless storage is separate from the old rootful
  one (images are pulled again once). **Clean baseline on Linux:** the dev-factory module
  passes 288/288 (16 Docker-gated tests skipped) with no Windows failures, including the
  symlink tests Windows skips. The Docker-gated tests run too once the container can reach
  Podman: copy the `docker` CLI out of `docker:29-cli` into a volume, mount it and the socket
  (`-v /run/user/1000/podman/podman.sock:/var/run/docker.sock`), and set
  `FACTORY_DOCKER_TEST=true`; `DockerWorkloadsTest` (14) and `FeedbackDockerTest` pass this
  way, while `ClaudeCodeDockerTest` and `PiCodingHttpContractTest` need the coding images
  (phase 3). Run it with the repo mounted read-only and a copy built inside
  the container, so Windows `target` directories stay untouched:
  `podman run --rm -v "<repo>:/src:ro" -v "<home>\.m2:/root/.m2" maven:3.9-eclipse-temurin-21 sh -c
  'mkdir /work && cd /src && tar --exclude=target --exclude=.git --exclude=node_modules -cf - . |
  tar -xf - -C /work && cd /work && mvn -B -ntp -o -pl factory-flow-dev-factory -am test'`.
  The app's database-backed tests pass on Linux too (`PrFeedbackIntegrationTest` 20/20, which
  fails 19/20 on Windows): use `-pl factory-app -am` and add `--network host`,
  `-v /run/user/1000/podman/podman.sock:/var/run/docker.sock`,
  `-e TESTCONTAINERS_RYUK_DISABLED=true` and `-e TESTCONTAINERS_HOST_OVERRIDE=localhost`.
- **What the Jest evidence does not prove.** Verification trusts what Jest reports in the
  container. A patch may change `jest.config.js`, `package.json` or setup files (reporters,
  `testMatch`, skipped suites), exactly as a Maven patch may change `pom.xml`. The backstops
  are the exit code, the executed-test floor (at least one test, none skipped-only), and the
  human review of the PR; Factory does not pin a suite list for yarn plans. The same holds
  for the registry: the `.yarnrc` Factory writes into the container home is a default, not an
  enforced setting. A project-level `.yarnrc`/`.npmrc` in a patch takes precedence and the
  freezer does not exclude it, so verification could install `@folio/*` from wherever the patch
  points (a Maven patch can likewise add `<repositories>` to `pom.xml`). Accepted: unlikely,
  verification has no credentials, and the file shows up in the PR diff.
- **Concurrent writes to a shared cache volume (both toolchains).** Nothing serializes the
  starting builds that write `factory-dev-yarn-cache` / `factory-dev-m2-cache`, the root
  `chown -R` in `ensureWritableCache` that runs before each of them, or the `cp -a` seed copies
  of coding and verification containers (`worker-threads: 4`, `max-concurrent-executions: 8`
  allow overlap). Yarn v1 is not safe for concurrent cache writes without `--mutex`, and
  `--mutex` does not cover the `chown`/`cp` races; Maven tolerates them better (atomic
  temp-file renames) and the engine retry covers a vanished file. Unobserved so far because
  executions run one at a time. **Before more than one person uses the flow** (phase 4
  prerequisite, as its own change): an in-process lock per cache volume in `DockerWorkloads`
  held across the starting build and the seed copies.
- **Seed copy size and timeout.** The seed copy has a fixed 120 s timeout and the cache is
  shared by all UI modules: 8 s with only ui-users in it, roughly 6 GB with ui-users,
  ui-inventory and ui-orders, copied into every coding and verification container. Measure in
  phase 4 with several modules; switch to one cache volume per repository, or raise the
  timeout, if the copy approaches the limit.
- **Podman instead of Docker (pilot machine).** The VM's `/var/run/docker.sock` now points
  to the rootless socket (`/run/user/1000/podman/podman.sock`), so the Compose socket mount
  and the Factory container's Docker CLI should work; `podman compose` delegates to Docker
  Compose. Not yet run against the Developer Flow stack. Open items to verify in phase 3:
  rootless user namespaces (`--user <uid>:<gid>` from `workloadUser`, named-volume ownership,
  the root `chown` of the cache volume), the Docker CLI against the Podman compat API for
  every command `DockerWorkloads` uses (`create`/`start`/`exec`/`cp`/`export`, labels,
  `--pids-limit`, `--cap-drop`), `host.docker.internal` in contract tests, and host-side
  `FACTORY_DOCKER_TEST` tests, which call a `docker` executable that is not installed on the
  Windows host (Podman forwards the API to `npipe:////./pipe/docker_engine`, so a plain Docker
  CLI needs no `DOCKER_HOST`). The WSL-backed VM has ~7.9 GiB available (WSL limits it, not
  `podman machine set --memory`); watch peak memory of a ui-users Jest run in phase 3.
