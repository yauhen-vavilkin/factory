# Developer Flow: UI (yarn + Jest) support — plan

Operations: [dev-flow-ui-runbook.md](dev-flow-ui-runbook.md) · Status: draft for review · Pilot: [UIU-3606](https://folio-org.atlassian.net/browse/UIU-3606) on `folio-org/ui-users`

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
      node22-jest: [yarn, test, --maxWorkers=2]   # the install is runtime.yarn.install-command (phase 5)
    plan-toolchains:
      node22-jest: yarn                            # absent → maven
    yarn:
      cache-volume: ${FACTORY_DEV_FACTORY_YARN_CACHE_VOLUME:factory-dev-yarn-cache}
      folio-registry: ${FACTORY_DEV_FACTORY_FOLIO_NPM_REGISTRY:https://repository.folio.org/repository/npm-folioci/}
      install-command: [yarn, install, --frozen-lockfile, --ignore-scripts, --non-interactive]
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

**Phase 4 — pilot: UIU-3606 on ui-users** (done, 2026-10-24).
Configure `ui-users` (has `yarn.lock`) with delivery to the fork
`FACTORY_UI_USERS_DELIVERY_REPO=Dmytro-Melnyshyn/ui-users` (set in `.env`; the fork's
`master` must contain the base SHA — delivery blocks otherwise); run the flow end-to-end.
Done: starting build passes on `master`; the coding attempt fixes the Patron/Keycloak dialog
regression (area: `src/views/UserEdit/UserEdit.js`, `src/components/Wrappers/withUserRoles.js`,
introduced by UIU-3586 PRs #3044/#3049) with updated tests; independent verification PASS;
PR opened on the fork; one PR-feedback round works. Record real container timings here.

Result, on the pilot machine (Windows + Podman on WSL, full Compose stack, Pi + CodeMie
`claude-sonnet-5-5`, `master` at `31d7f0f`; the intake read the ticket anonymously, so only
`FACTORY_CONNECTORS_JIRA_BASE_URL` was needed):

| Step | Run 1 (cold yarn cache) | Run 2 |
|---|---|---|
| Starting build: `yarn install --frozen-lockfile` + Jest | 3 min 28 s (install 110 s, Jest 82 s) | included below |
| Intake → coding done | — | 206 s (includes the warm starting build) |
| Independent verification (1639–1640 tests, 0 failures) | — | 86–91 s |
| Trigger → PR on the fork | stopped, see below | 333 s |
| PR-feedback round 1 (comment → code → verify → push) | — | 254 s (code 111 s, verify 127 s, publish 16 s) |

- **Run 1** passed the starting build and verification, but delivery was blocked: Compose
  did not forward `FACTORY_UI_USERS_DELIVERY_REPO` into the Factory container, so the target
  fell back to `folio-org/ui-users`, which Factory refuses by design. Compose now forwards the
  `_SOURCE_REPO` and `_DELIVERY_REPO` variables for `ui-users` and for the four backend
  repositories (the backend eight had the same gap, so any Compose delivery to a fork was
  blocked).
- **Run 2** opened `Dmytro-Melnyshyn/ui-users` PR #1 (3 files, +16 −1): `UserEdit.js` skips the
  Keycloak dialog for a Patron created on a member tenant, `withUserRoles.js` shows it when a
  Patron is changed to another type, plus a changelog entry. Delivery is **automatic**:
  `publish` is an agent step gated by the verification receipt and `authorized: true`, with no
  approval gate before it. The human gates are the repository and coding questions and the PR
  follow-up.
- **Round 1:** a plain PR discussion comment asking for tests (UserEdit and `withUserRoles`)
  produced commit `e1e747c` on the PR branch: two test files (+114 lines), verification PASS
  with 1643 tests (1639 before), 0 failures. Round usage was 651k input and 14k output tokens.
- **Model use:** about 202k–319k input and 6k–8k output tokens per initial attempt.
  `claude-sonnet-5-5` has no entry in `config/developer-pricing.yaml`, so the estimate shows 0;
  add a rate to price it.
- **Agent setup:** in both initial attempts Pi reported it could not run any tests because
  `node_modules` was missing; run 1 still added a test, run 2 added none. Once the comment said
  "run the suite before pushing", the round added and ran its tests. Resolved by phase 5: Factory
  installs the dependencies before the agent starts and the request names the verification
  command.
- **Gateway access:** the CodeMie gateway is reached through `scripts/dev/codemie-bridge`
  (relay container → SSH reverse forward → bridge → `codemie proxy`); see its README. The
  gateway rejects CodeMie CLI versions older than 0.15.8 with HTTP 426.

**Phase 5 — prepared coding workspace** (done).
Found in the phase 4 pilot: in both initial attempts the agent reported it could not run any
test because `node_modules` was missing, and once told to run the suite it did. Agents should
not have to remember a setup step, and a plain `yarn test` starts one Jest worker per CPU,
which froze the 8 GB VM in phase 2. So, for yarn plans:
- **One definition of the install.** The install is its own setting,
  `runtime.yarn.install-command` (default `yarn install --frozen-lockfile --ignore-scripts
  --non-interactive`, tokens validated because they are joined into a shell command). The
  plan shrinks to the test command (`yarn test --maxWorkers=2`). The starting build and
  independent verification run `install && test` as one command built from those two
  settings, and the coding container runs the install alone before the agent starts, so
  coding, the starting build and verification cannot drift apart. The receipt's `argv` is
  that effective command, so changing either setting already invalidates old receipts
  through the existing `VerificationPolicy` comparison; no receipt schema change. Phase 6
  extends the same install step to seed a pinned lockfile.
- **Prepared coding container.** Factory runs the install in every coding container before
  the agent starts (initial attempt, clarification restart and every PR-feedback round) with
  the trusted `.yarnrc` and the seeded cache; about 15 s with a warm seed. A failed install
  stops before any model call. In the initial attempt a download failure asks the engine to
  retry (the same classification as the starting build) and anything else is an environment
  failure; a PR-feedback round has an operator-driven retry, so any install failure ends that
  round before a model call and the operator repeats it.
- **Verification command in the request.** The `CodingRequest` constraints carry
  "verification runs: `<command>`" (the plan's test command: `yarn test --maxWorkers=2`, or
  for Maven `mvn -B -ntp test` versus `mvn … verify`) and whether dependencies are already
  installed. The agent reproduces the real check and stays within the memory limit. The
  install line applies to yarn only (Maven resolves dependencies itself during the build); an
  agent that edits `package.json` still installs the new dependencies itself.

Done, independent of the model: unit tests show the install runs before the agent in the
initial attempt and in a feedback round, that a failing install stops before a model call
(retryable on download errors), that the request carries the command, and that the starting
build, the coding container and verification use the same install command; a gated Docker
test runs the install in the coding image. Then one end-to-end UIU-3606 run, recorded with
its model name: first with `claude-sonnet-5-5` (the phase 4 model, so a change in the
agent's behavior can be attributed to the prepared workspace), then once with
`gpt-5.6-luna-2026-07-09` as a separate data point.

Result (implemented 2026-10-24). Unit tests cover the install order and the failure paths in
both coding paths; the gated fixture test (Linux, real images) runs `PreparedWorkspace`, then a
Claude Code session that finds `node_modules` ready and runs `yarn test` without installing,
then fresh verification (58 s). The full Linux suite is clean (Developer Flow module 317 tests,
`PrFeedbackIntegrationTest` 20/20). A compatibility test found that a stored coding request
without the new constraint fields failed to parse (Jackson 3 rejects a missing primitive), so
`dependenciesInstalled` is a normalized `Boolean`.

End-to-end run on ui-users, UIU-3606, **`gpt-5.6-luna-2026-07-09` (reasoning `max`)**, PR #2 on
the fork. This changed the model as well as the workspace, so on its own it could not show
what caused the improvement; the same-model run below does.
- Starting build 113 s; coding container ready: 32 s for the cache seed copy, then the install
  in the coding container **17 s**; the agent ran from there.
- The agent ran `yarn test --maxWorkers=2` itself (296 suites, 1641 tests passed), the targeted
  tests, and ESLint, and reported them; in both phase 4 initial attempts it had run nothing.
- Independent verification: PASS, 1641 tests (1639 at baseline plus 2 new), 107 s.
- Trigger to PR: 575 s. PR #2: 3 files, +64 −5, including 42 lines of tests.
- Usage: 48 uncached input, 942,891 cache-read, 180,350 cache-write and 15,878 output tokens;
  about $0.08 at the rate card's list prices (my calculation; Factory's own estimate appears on
  the execution page).

Same-model run, `claude-sonnet-5-5` with the phase 4 settings (PR #3, trigger to PR 350 s):
starting build 107 s; coding container ready 26 s later (cache seed copy), install **12 s**.
The agent found `node_modules` ready and ran the `src/views/UserEdit` tests, including the one
it added, and said plainly that it had not run the full `yarn test --maxWorkers=2`; in the
phase 4 initial attempts with this model it ran nothing because dependencies were missing.
Independent verification PASS, 1640 tests (1639 + 1 new), 92 s. 266,558 input and 6,197 output
tokens (the gateway reported no cache split for this model). PR #3: 3 files, +24 −1. So the
change in behavior is attributable to the prepared workspace and the verification-command line,
not to the model. Model quality still differs: Sonnet again left the Patron-to-Staff edit
scenario of the ticket unaddressed (it said so and explained why), which Luna covered.

**Phase 6 — modules without `yarn.lock`.**
The starting build generates a lockfile once and stores it as a Factory artifact (with its
SHA-256 in the readiness record). Coding and verification receive it as an untracked,
excluded file, so all three install the same dependency versions even while npm-folioci
publishes new snapshots. Verification installs with it seeded (frozen when `package.json` is
unchanged from base); the receipt records the lockfile hash.
Done: a no-lockfile fixture verifies with the pinned versions after a newer snapshot is
published; a no-lockfile module runs end-to-end (ui-circulation stands in for the ui-orders or
ui-myprofile named when this phase was planned: ui-myprofile `master` is red and ui-circulation has
the same shape, no lockfile and Jest).

Design (settled before implementation). Spike with yarn 1.22.22 on `node:22.22.0-bookworm`:
`yarn install --frozen-lockfile` **succeeds without creating a lockfile** when none exists, so
today a no-lockfile module installs fresh versions in the starting build, in the coding
container and again in verification, and each stage can see a different npm-folioci snapshot.
`yarn install` without the flag creates `yarn.lock`; with a lockfile present and `package.json`
changed, `--frozen-lockfile` fails ("lockfile needs to be updated") while a plain install keeps
the pinned versions and adds only what is new.
- **Pin once, at the starting build.** If the base checkout has no `yarn.lock`, the starting
  build runs the install **without** `--frozen-lockfile` (the configured install command with
  that one token dropped; nothing new to configure), then reads `/workspace/yarn.lock` out of
  the trusted workload. A lockfile is accepted only if it is UTF-8, starts with
  `# yarn lockfile v1` and is at most 4 MiB (the largest recorded one, `ui-users`, is 0.75 MB).
  A module whose base tracks `yarn.lock`, and Maven modules, are unchanged (tracked lockfile is
  already the pin).
- **One artifact, `dev_lockfile.json`**: `{"state":"PINNED","sha256":…,"content":…}`, or
  `{"state":"NOT_NEEDED"|"NOT_RUN",…}` (plus `packageJsonBlob`, below). `implement` writes it, `continue-implementation` passes
  it through (a clarification restart does not run the starting build again), `verify` and
  `pr-follow-up` read it. `dev_readiness.json` gains `lockfile` (`PINNED`, `TRACKED`, `NONE`)
  and `lockfileSha256`. The artifact is immutable like every other, so every stage of an
  execution sees byte-identical content. Flow version stays 0.9.0, so parked PR follow-ups
  keep running. The engine requires every declared input to exist, so an execution that is
  between `implement` and `verify` (or parked at the PR follow-up) when this change is
  deployed fails on the missing artifact: finish or drain executions first (runbook).
- **Seeded, never tracked.** A pinned lockfile is copied into `/workspace/yarn.lock` of the
  coding container (before the install, so the prepared workspace is the pinned one), of every
  PR-feedback coding container and of the verification container, as a file owned by the
  workload user (copied to a temp path, then `cp`, because `docker cp` creates root-owned
  files and a later non-frozen install must be able to rewrite it). The freezer already
  excludes an untracked `yarn.lock`, so it never reaches a patch, a PR or the receipt's tree.
- **Install mode of verification.** `FROZEN` when the pinned lockfile is seeded and the
  candidate's `package.json` is the one the lockfile was generated for, so any drift is an
  error. `RESOLVE` (the install command without `--frozen-lockfile`) when it differs: pinned
  versions are kept, only added or changed dependencies are resolved. "The one it was generated
  for" is the Git blob id of `package.json` at the base commit, stored in `dev_lockfile.json`
  (`packageJsonBlob`) and compared with the blob in the reconstructed candidate
  (`git ls-files --stage package.json`). It is compared with the pin, not with the candidate's
  own base, because a PR-feedback round's candidate is based on the PR head: a `package.json`
  change from an earlier round would otherwise look "unchanged" and fail the frozen install.
  The same rule (`PinnedLockfile.installMode(checkout)`) chooses the install of every coding
  container before the agent starts, not only verification: a feedback round starts from the PR
  head, and after a round that added a dependency a frozen install there would fail with
  `WORKSPACE_INSTALL` before any model call, on every retry. (Found in review; the live pilot
  stopped one round short of it.) The agent's own installs in the container are not restricted:
  a non-frozen install also keeps the pins.
- **Receipt and policy.** The receipt gains `lockfileSha256` (null when no lockfile was
  pinned); `argv` stays the effective command, so the two install modes give two argv values.
  `VerificationPolicy` accepts a receipt whose `argv` is the frozen variant, or the resolving
  variant only when the receipt carries a `lockfileSha256` (a plain install is legitimate only
  against a pinned lockfile), both built from the current settings, so changing the install
  command still invalidates old receipts. The generated `yarn.lock` is read out of the workload
  only if it is a regular file (a symlink at that path is refused, because code in the workload
  decides what is there). The PR text states the pinned lockfile's short hash.
- **Limit.** Pins hold within one execution because the starting build leaves every tarball in
  the shared yarn cache that coding and verification are seeded from. If the cache volume is
  reset and npm-folioci has since pruned a pinned snapshot, the install fails with a registry
  404 (an environment failure): run the ticket again to pin a new lockfile.

Result (implemented 2026-10-25). Unit tests cover
the install modes and their commands, lockfile validation and digest, seeding before the coding
install in the initial attempt, the clarification restart and a feedback round, a blocked start
on an unusable lockfile, the frozen/resolving choice in verification, the receipt hash, policy
acceptance of both argv variants and the PR line. Docker-gated, Linux harness, real containers:
`PinnedLockfileDockerTest` starts a throwaway npm registry container, the starting build pins
`@folio/pinned-fixture` 1.0.0, 1.1.0 is published, an unpinned install now yields 1.1.0 and
fails the check, and `CandidateVerifier` with the pinned lockfile installs frozen, passes, and
records the lockfile hash (policy matches); a second test shows a seeded `yarn.lock` is owned by
the workload user. The Claude Code yarn fixture (jest, no lockfile) now goes baseline → pinned →
coding → verification through the same path. The first version of the lockfile check required
`# yarn lockfile v1` on the first line; real yarn writes `# THIS IS AN AUTOGENERATED FILE…` first,
which only the gated test could show. Full `mvn verify` on Linux: Developer Flow module 337
tests (21 gated skipped), app module 245, no failures.
End-to-end on a real no-lockfile module: UICIRC-1390 ("Add Automatic renewals section to Loan
policy page") on `ui-circulation` (base `fb569e5`, fork `Dmytro-Melnyshyn/ui-circulation`),
`gpt-5.6-luna-2026-07-09`, reasoning `max`.
- **Run 1 (no PR).** Starting build: "No lockfile found", install without `--frozen-lockfile`,
  lockfile read out and pinned (766 KB, `lockfile: PINNED`, SHA-256 `d07e4cb0…`), Jest passed in
  16 s. Coding container: frozen install against the seeded lockfile, 12 s. The agent then ran
  the module's full `yarn test`, whose script is `jest --ci --coverage --colors`: a coloured
  coverage table per source file, which Pi streams as cumulative partial tool output. The event
  stream passed `EVENT_STREAM_LIMIT` (16 MiB) 27 s into that command, Factory killed the
  session (`RUNTIME_ERROR`, "Command exceeded output limit") and the attempt, 2.3 minutes of
  work, was lost. `ui-users` never hit this because its output is plainer. Fix: the
  verification line of the coding prompt now says the output can be thousands of lines, the
  session has a hard size limit, and to send it to a file and read the tail
  (`(<command>) > /tmp/factory-check.log 2>&1; echo "exit=$?"; tail -n 80 …`); the limit stays.
- **Run 2.** Trigger to PR 5 min 30 s. The same lockfile hash was pinned (no newer snapshot in
  between). The agent ran targeted tests (72) and the full suite (192 suites, 2153 tests), moved
  the section, normalised a blank retry value and updated tests. Independent verification: frozen
  install against the pinned lockfile, PASS, 2153 tests, receipt `lockfileSha256` set. PR #1 on the
  fork: 3 files (+87 −83), no `yarn.lock`, the PR text states the pinned hash. 742k cache-read
  and 227k cache-write tokens, 12.6k output.
- **PR feedback with the pin.** Round 1, inline comment "add unit tests for the retry-period
  normalization": 2 min 20 s from click to push, `normalize.test.js` added (+5 tests), verification
  PASS with 2158 tests, frozen install, receipt `lockfileSha256` `d07e4cb0…` (same pin). Round 2,
  PR comment "raise the minimum `lodash` to `^4.17.21`": the patch changes only `package.json`, so
  the candidate's `package.json` blob differs from the pinned one; verification took the
  resolving path (receipt command `yarn install --ignore-scripts --non-interactive && yarn test
  --maxWorkers=2`, no `--frozen-lockfile`), PASS with 2158 tests, same lockfile hash, no
  `yarn.lock` in the PR (3 commits, 5 files). The first attempt at round 2 stopped at
  `REFRESH_REQUIRED` without any container or model call: the selection came from the snapshot
  taken before round 1 pushed, which the flow rejects by design; "Check PR comments" and a new
  selection fixed it.

**More modules (2026-10-26).** Four more UI repositories registered the same way (`ui-inventory`,
`ui-marc-authorities`, `ui-checkin`, `ui-eholdings`); none has a tracked `yarn.lock`, so each went
through the pin. Same model and settings as above.

| Ticket | Module | Outcome | Verification |
|---|---|---|---|
| UIIN-3294 | ui-inventory | not delivered (flaky upstream test, below) | 1514 of 1515 on the failing attempt |
| UIMARCAUTH-473 | ui-marc-authorities | PR delivered; feedback round 1 pushed, PASS | 189 tests |
| UICHKIN-516 | ui-checkin | PR delivered | 348 tests |
| UIEH-1514 | ui-eholdings | PR delivered | 1273 tests |

- **Output limit on the plan's own command.** ui-inventory's `yarn test` writes 16.3 MB to stderr
  (45 s install, 153 s suite); the 2 MiB command limit killed the starting build with "Command
  exceeded output limit" before any model spend. The starting build and the independent
  verification now consume everything and keep the last `Processes.OUTPUT_LIMIT` bytes of each
  stream (`Workload.executeKeepingTail`); the exit status decides. The limit still kills the Pi
  and Claude event streams, installs and git, where a truncated stream is a corrupt stream. A
  failed starting build now keeps stderr in the readiness output (Jest reports there).
- **Flaky upstream test.** `InstancesList › Reset all` in ui-inventory fails in about a third of
  full runs on unmodified `master` (seen on the baseline, on verification of a correct candidate,
  and twice in two throwaway runs of plain `master`). With one verification attempt and no retry
  about half of the tickets on that module fail (the baseline and the verification must both pass).
  Not handled: the option of re-running only the failed test files once, recorded in the receipt,
  was declined for now.
- **Cache.** After six modules 3.8 GB and 3015 entries (2675 before ui-inventory). Growth per new
  module: +296 MB (ui-inventory), +13 MB, +1 MB, +33 MB. No duplicate name+version entries. Seed
  copy 33–35 s for 3.7 GB (an earlier measurement gave 57 s for 3.4 GB). A feedback round ran
  while another ticket's starting build was writing the cache, with no copy or integrity error
  (one sample).
- **Concurrent starting builds.** Two real tickets started in the same second on the warm cache
  (UIEH-1523 on ui-eholdings, UIIN-3294 on ui-inventory) both delivered with the same lockfile
  pins as their solo runs; the cache did not change (3898228 KB, 3015 entries, every entry has its
  metadata), so nothing was written and nothing was tested about writes. A controlled test with
  throwaway containers and an empty shared cache did test writes: one install alone is fine (90 s);
  two at once (ui-eholdings and ui-inventory) **both fail** with "Extracting tar content … the file
  appears to be corrupt: ENOENT …/cache/v6/…"; the same pair with `--mutex file:/cache/.yarn-mutex`
  both succeed (the second waits: 198 s), and an offline frozen re-install from that cache works.
  So the default install command now carries `--mutex file:<yarn cache path>/.yarn-mutex`
  (`DevRuntimeProperties.Yarn.DEFAULT_INSTALL_COMMAND`, `application.yaml`). It serializes only the
  `yarn install` step. The root `chown -R` before a trusted workload and the `cp -a` seed copy of a
  coding/verification container were not covered and could fail on the lock directory itself;
  they now stay out of the cache root (see the cache lock item under Risks). The command change alters the
  receipt's command, so an execution that verified before the deploy and has not published yet
  fails at delivery with "Verification policy changed"; deploy when none is between `verify` and
  `publish`. End to end through the Factory on a cold cache (an empty volume set through
  `FACTORY_DEV_FACTORY_YARN_CACHE_VOLUME`, removed afterwards): UICHKIN-516 (ui-checkin) and
  UIEH-1514 (ui-eholdings) started in the same second, both starting builds passed with the
  mutex in their command, both were verified (348 and 1273 tests, the mutex in the receipts) and
  delivered in about 11 and 14 minutes. The cold cache ended at 2.7 GB with 2428 entries, none
  without metadata, and the ui-eholdings pin (`22c19417…`) equals the one from the warm-cache runs.
  Staggered, on the warm cache: UICHKIN-516 started at 09:06:25 and entered its coding step at
  09:08:14; UIMARCAUTH-473 (ui-marc-authorities) was started at 09:08:20, so its `chown` and install
  overlapped the first ticket's cache copy, and its own coding and verification copies overlapped the
  first ticket's agent and verification (read from the step times, not measured per file). Both were
  verified (PASS) and delivered; the cache logged no "No such file", "corrupt" or ENOENT, and grew from
  3898228 KB to 4046796 KB. A third ticket started first, UIEH-1523, was blocked in its starting
  build by 26 failing suites: `resizeObserver.disconnect is not a function` in
  `@folio/stripes-components` `PaneResizeContainer.js`; most likely a newer npm-folioci snapshot than
  the earlier runs of that module resolved (it had passed three times; not checked against the
  installed versions). No model spend, no PR.
- **Bridge.** After a CodeMie proxy restart `start-bridge.ps1` could see the old bridge's closing
  listener, skip starting a new one and fail its smoke check with HTTP 000. It now waits until the
  old listener is gone.

## Risks and open questions

- **Cache growth.** The shared yarn cache has no eviction and grows with every module (1–2.6
  GB each, with heavy overlap); it was 3.4 GB on 2026-10-25 after ui-users, ui-circulation and
  the test fixtures. Seeding costs per coding and verification container: the phase 2 spike
  measured about 3 s per GB (8 s for 2.5 GB), but a plain `cp -a` of the real 3.4 GB volume on
  the pilot machine took 57 s (about 17 s per GB). See "Seed copy size and timeout". If it gets
  large, reset it by removing the volume while the stack is stopped; per-repository volumes
  remain an option. Since phase 6 an execution's pinned lockfile also depends on the cache
  keeping its tarballs: after a reset, npm-folioci may have pruned an old snapshot (runbook:
  run the ticket again).
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
- **Coding agent setup.** The pilot showed the agent often does not run `yarn install` itself
  (both initial attempts reported no tests run). Phase 5 removes the dependency on it: Factory
  prepares the workspace and the request names the verification command. Only that command line
  helps Maven tickets (which of `mvn test` / `mvn verify` to run); the install is yarn-only.
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
  The Docker-gated tests that run a mock model endpoint inside the test JVM
  (`ClaudeCodeDockerTest`, `PiCodingHttpContractTest`) also need `--network host` on this
  harness container: the coding containers call back to `host.docker.internal:<port>`, which
  must reach the JVM's own network namespace. Without it every such case fails with
  `RUNTIME_ERROR` after a 60-100 s timeout (a 24-case class then takes half an hour). Run only the
  classes you need (`-Dtest=Class#method`) and keep the full Maven output until the end, because
  piping it through `grep | head` hides results. `PinnedLockfileDockerTest` needs neither: its
  throwaway registry is a container reached by IP.
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
  executions run one at a time (the runbook says to run one UI ticket at a time). **Before more
  than one person uses the flow**, as its own change: an in-process lock per cache volume in
  `DockerWorkloads`, one permit per volume (a semaphore, not a reentrant lock, because the
  workload need not be closed on the thread that opened it), taken before
  `ensureWritableCache` so the `chown -R` is covered, released in the trusted workload's
  `close()` (safe to call twice) so every exit path releases it, with a `cache_lock_waiting`
  progress event so a queued starting build does not look hung. Seed copies only read and need
  no lock. Yarn `--mutex file:` is now in the default install command (see "Concurrent starting
  builds" above), because two concurrent installs into a cold cache were shown to corrupt it; it
  does not replace this lock. Measured 2026-10-26 while one `yarn install --mutex` filled an empty
  volume: the exact `chown -R` / `chmod -R u+rwX` of `ensureWritableCache` failed in 1 of 110 passes
  and a seed-style `cp -a` of the cache root in 1 of 49 filled-cache copies, both with `No such file
  or directory` on `/cache/.yarn-mutex.lock`, the lock directory the mutex creates and removes. A
  watcher that listed the cache every 0.3 s saw no other entry appear and vanish, and none of the
  48 other copies (71 MB up to 2.7 GB, so through the fetch phase) failed. Left alone, a build
  starting during another build's install would fail in the `chown` before model spend, and a
  coding or verification container in the copy (`CacheSeedException`). **Fixed at the source**
  (`DockerWorkloads.writableCacheScript` / `seedScript`): for yarn, the `chown`/`chmod` and the seed
  copy work on the cache's version directory (`v[0-9]*`, i.e. `v6`) and never walk the root, so the
  lock directory is never visited; Maven keeps its commands. Yarn's own `.tmp` is inside `v6`, so it
  is still walked and copied; it was empty at the end and a 0.2 s watcher saw no path in it during
  an install. Replayed with the new commands during cold installs, the loops running only while
  the install ran: 262 and then 14,535 back-to-back `chown`/`chmod` passes, and 32 and then 35
  copies of a cache growing from 72 MB to 2.7 GB, none failed. A yarn release that moves the cache
  to another `vN` directory is covered by the glob.
  The per-volume writer lock is therefore no longer needed for these races; revisit it only if a
  starting build fails in the cache again. Only the cache root is re-owned now, not a `.yarn-mutex`
  file left in it; if the workload uid (the checkout owner) ever changes, look there first for an
  install that cannot take the lock. Decided against, for now: Maven's `file-lock` named locks (the
  resolver setting is untested on these volumes) and the narrower `find … ! -user … chown`
  (the walk dominates, not the writes).
- **Seed copy size and timeout.** The seed copy has a fixed 120 s timeout and the cache is
  shared by all UI modules, copied into every coding and verification container. Measured
  2026-10-25 on the pilot machine: `cp -a` of the 3.4 GB volume took 57 s (about 17 s per GB),
  so the limit is reached at roughly 7 GB, about twice today's size; an earlier estimate of
  15–30 GB, taken from the faster phase 2 spike, was wrong for this machine. In real runs the
  coding container was ready 26–32 s after the starting build, so a workload copy may be faster
  than this test, but that is not measured. Hardening, in this order, as its own change: (1)
  **done**: `runtime.seed-timeout-seconds` (`FACTORY_DEV_FACTORY_SEED_TIMEOUT_SECONDS`, default
  300, `DockerWorkloads` takes it in its constructor) and a `CacheSeedException` whose message
  names the volume, its size, the limit and the setting; the initial attempt reports it as
  `BLOCKED_ENVIRONMENT` before any model call, a feedback round shows it as the round's failure,
  verification fails the step with it; (2) **done** for yarn: the `chown` and the copy stay out of
  the cache root (the cache lock item above); (3) one yarn
  cache volume per repository (`factory-dev-yarn-cache-<repo>`), with a recorded trigger of
  about 5 GB total or real concurrency across modules, accepting duplicate disk for the shared
  stripes tree and a Compose and runbook change. Maven's `repo.local.tail` (zero-copy seeding)
  is skipped: the Maven cache is empty here, so there is no measured problem.
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
