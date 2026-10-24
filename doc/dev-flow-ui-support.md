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
      build-image: node:22-bookworm                # ships yarn 1.22; source is copied in
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
    coding:
      pi:          { yarn-image: ${FACTORY_PI_YARN_IMAGE:factory-dev-pi:0.85.1-node22} }
      claude-code: { yarn-image: ${FACTORY_CLAUDE_CODE_YARN_IMAGE:factory-dev-claude-code:2.1.287-node22} }
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
`YARN` joins `Toolchain`; `JestJunitEvidence` (same XML hardening as `SurefireEvidence`, size cap, freshness by mtime),
receipt/policy fields, Jest failure classification, `PullRequestRenderer` evidence lines.
Done: unit tests with recorded `junit.xml` fixtures — pass, assertion failure, suite load error
(the ui-myprofile case), zero tests, stale file, oversized/malicious XML.

**Phase 2 — yarn workloads.**
Toolchain-aware cache, build-output and freezer seams (with `MavenCache`/`MavenBaselineOutput`
behind them, signatures kept), then yarn cache volume and seeding, trusted `.yarnrc`/env, yarn build-output parser (progress,
summary, transient detection → engine retry), freezer exclusions.
Done: `DockerWorkloadsTest`-style tests for the yarn mounts/env; freezer tests prove untracked
`yarn.lock`/`artifacts/` never enter a candidate; cache spike result recorded here (see risks).

**Phase 3 — images and Compose.**
`factory-dev-pi:0.85.1-node22` and `factory-dev-claude-code:2.1.287-node22` (the starting
build and verification use the official `node:22-bookworm` image directly); Compose builds/checks the yarn coding image for the
selected runtime alongside the Maven one.
Done: images build without credentials; version checks pass; `FACTORY_DOCKER_TEST=true`
contract tests cover a small yarn fixture repo end-to-end (baseline → code → verify).

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

- **Cache size and seeding cost.** Yarn caches are 1–2.6 GB per module and the shared cache
  grows with every module. Copying it into each container (the Maven pattern) may cost more
  than it saves. Spike in phase 2: read-only seed copy vs. per-repository volumes vs. a
  `yarn-offline-mirror` of tarballs. Decide on measured container times.
- **Flaky tests** fail verification as `UNKNOWN`/`CANDIDATE`. Accepted for the pilot; the
  operator can request another check. Revisit (e.g. bounded re-run of failed suites only) with
  real data.
- **Container limits.** `--pids-limit 512` and no memory cap vs. Jest's parallel workers and
  jsdom memory on large modules (ui-inventory). Check in phase 3 Docker tests.
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
  one (images are pulled again once).
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
