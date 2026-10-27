# Developer Flow for UI (yarn + Jest) — operations runbook

What to do when running Developer Flow on `ui-*` modules. The design and the pilot
measurements are in [dev-flow-ui-support.md](dev-flow-ui-support.md); general execution
recovery (stuck `RUNNING` rows, draining, retention) is in [runbook.md](runbook.md). The
machine-specific parts below assume the pilot setup: Windows, Podman on WSL, the CodeMie gateway.

## 1. Start and check

```powershell
# CodeMie gateway for the coding agent (browser SSO sign-in when the session is gone, about daily)
.\scripts\dev\codemie-bridge\start-bridge.ps1 -State $env:LOCALAPPDATA\factory-codemie `
    -CodemieHome $env:LOCALAPPDATA\factory-codemie\codemie
# The Factory and PostgreSQL (with Docker instead of Podman: `docker compose`); run it again after changing .env
podman compose up -d --build
```

Run both from an ordinary terminal. A shell started by a CodeMie/Claude wrapper exports
`CODEMIE_*` and `ANTHROPIC_*` variables that override the CLI's profile lookup; unset them first.

Healthy means: `curl localhost:8080/api/status` answers 200, and the bridge script ends with
"a default-network container reaches the bridge … (401)". Stop the gateway side with
`start-bridge.ps1 -Stop`. See [scripts/dev/codemie-bridge/README.md](../scripts/dev/codemie-bridge/README.md).

## 2. Run a ticket

```bash
curl -s -X POST localhost:8080/api/triggers/manual -H 'Content-Type: application/json' \
  -d '{"flowId":"dev-factory","payload":{"issueKey":"UIU-3606"}}'
```

Follow it at `http://localhost:8080/executions` (or `GET /api/executions/<id>`). A normal UI run
takes 6–10 minutes: starting build (about 2 min, warm cache), coding container prepared (about
30 s), the agent, independent verification (about 1.5 min). Intake reads the Jira issue
anonymously, so `FACTORY_CONNECTORS_JIRA_BASE_URL` alone is enough.

- **Delivery is automatic.** After a passing verification, an authorized target gets a branch
  `factory/<issue>-<execution>` and a pull request on the configured repository, with no approval
  step. Only fork targets are allowed; `folio-org/*` is refused.
- **PR feedback.** The execution waits at the PR follow-up. On its page: **Check PR comments**,
  select the comments, **Fix selected comments**. One round (code, verify, push) takes about 4
  minutes. Closing the PR without merging cancels the execution; merging completes it.
- **Several tickets.** Installs are serialized by a lock in the shared yarn cache, and two tickets at
  once have worked on a warm cache. On a fresh or reset cache run one at a time; the other cache
  races are in the risks of the plan.

## 3. Add a UI repository

1. `application.yaml`, under `factory.dev-factory`: a `repositories.<key>` entry (`source-repo`,
   `base-branch`, `build-image: node:22.22.0-bookworm`, `verification-plan: node22-jest`,
   `jira-projects`) and a `delivery.targets.<key>` entry with `authorized: true`. The plan,
   its yarn toolchain and `runtime.yarn.install-command` already exist and are shared.
2. `docker-compose.yml`: forward `FACTORY_<KEY>_SOURCE_REPO` and `FACTORY_<KEY>_DELIVERY_REPO`.
   Compose passes only the variables it lists; an unlisted override is silently ignored and the
   delivery target falls back to the upstream repository.
3. `.env`: `FACTORY_<KEY>_DELIVERY_REPO=<your-fork>/<repo>`. The fork's base branch must contain
   the base commit (sync the fork first), or delivery is blocked.
4. The module must use Jest through `@folio/jest-config-stripes`. A `yarn.lock` is optional: if the
   base commit has none (most FOLIO UI modules), the starting build generates one and Factory
   pins it for that execution (readiness shows `lockfile: PINNED` and its SHA-256; the PR text
   repeats the hash). The pin is not part of the PR. BigTest/Karma modules are not supported.
5. Recreate the container (`podman compose up -d`) and run the ticket.

## 4. Model and gateway

The coding agent is Pi through the local bridge. In `.env`:

```dotenv
FACTORY_DEV_FACTORY_CODING_RUNTIME=pi
FACTORY_PI_PROVIDER=codemie
FACTORY_PI_MODEL=gpt-5.6-luna-2026-07-09      # or claude-sonnet-5-5
FACTORY_PI_BASE_URL=http://host.docker.internal:4102/v1
FACTORY_PI_API=openai-completions
FACTORY_PI_AUTHENTICATION=api-key
FACTORY_PI_API_KEY=<content of %LOCALAPPDATA%\factory-codemie\bridge-client-key>
FACTORY_PI_REASONING_EFFORT=max                # Luna profile; leave unset for Sonnet
FACTORY_PI_MAX_OUTPUT_TOKENS=65536             # Luna profile; default 16384
FACTORY_PI_IMAGES=true                         # checked with the Luna model; see "Ticket images" below
```

**Ticket images.** With `FACTORY_PI_IMAGES=true` (Pi) or `FACTORY_CLAUDE_CODE_IMAGES=true` (Claude Code) the images
attached to the Jira ticket (png, jpeg, gif, webp; videos and other files are skipped) go to the agent with
the task. Set the flag only for a model that reads images; otherwise Pi swaps each image for a text placeholder
and Claude Code may fail at the gateway. The list of images is fixed at intake; every coding attempt (first,
after a decision, after a retry) downloads those files again from Jira into the workload. An image added
to the ticket afterwards is not seen, and one deleted afterwards is reported to the agent as not provided.
The audit timeline shows `attachments_staged` with file names, sizes and SHA-256, never the content. A file that
cannot be downloaded, is larger than `FACTORY_DEV_FACTORY_ATTACHMENT_MAX_BYTES` (3 MiB), is not really an image or
exceeds `FACTORY_DEV_FACTORY_ATTACHMENT_MAX_IMAGES` (default 10 in Jira order; 0 = no limit, at most 20 are listed
from the ticket) is left out, and the prompt tells the agent which one and why. Each staged image is repeated
several times in the agent's event stream, so the output budget grows with them (5 times their size); many
large images mean a large buffer in memory. Images are untrusted ticket content: the prompt says so, and they
add no permission (the workload holds only the gateway key, delivery is fork-only, a person reviews the PR).
PR follow-up rounds do not resend them.

Changing any of these needs `podman compose up -d`. A model without an entry in
`config/developer-pricing.yaml` shows no cost estimate (usage is still recorded). To rotate the
client key: delete `bridge-client-key`, run `start-bridge.ps1 -Stop`, start it again with
`-UpdateEnv .env` (plus the same `-State` and `-CodemieHome` as in section 1), then recreate the Factory.

## 5. When something goes wrong

| Symptom | Cause | Action |
|---|---|---|
| Starting build fails, `BASELINE_FAILED` / `BLOCKED_ENVIRONMENT` | Upstream `master` is red, a suite fails to load, or a flaky test failed | Read the output in the readiness artifact. Red or unloadable suites need an upstream fix; for a flake, run the ticket again |
| "Starting build hit a network/download failure" or "Workspace dependency install hit a network/download failure" | npm registry timeout or reset | Nothing: the engine retries (3 attempts) and then escalates to the review inbox |
| "Workspace dependency install failed before model spend: …" | The yarn error is quoted; usually an out-of-date lockfile | Fix the lockfile upstream. No model call was made |
| "Starting build could not pin the dependency lockfile: …" | The generated `yarn.lock` is larger than 4 MiB or is not yarn v1 text | The message says which. No model call was made; a lockfile that large needs a decision (raise the limit in `PinnedLockfile`) |
| Install fails with a registry `404` for a package that is in the pinned lockfile | The yarn cache volume was reset and npm-folioci has since pruned that snapshot | Run the ticket again: the new starting build pins a fresh lockfile |
| "Copying the dependency cache volume '…' (N GB) into a new workload failed (limit N s)" | The shared cache grew until the copy into a coding or verification container no longer fits the limit (about 17 s per GB on the pilot machine) | Raise `FACTORY_DEV_FACTORY_SEED_TIMEOUT_SECONDS` (default 300) and recreate the container, or reset the cache (section 6). No model call was made |
| Delivery blocked: "requires a configured user-owned GitHub fork" | The delivery repository resolved to `folio-org/…` | Set `FACTORY_<KEY>_DELIVERY_REPO`, make sure Compose forwards it (section 3), recreate the container |
| Delivery blocked: base commit not in the fork | The fork is behind upstream | Sync the fork's base branch, run the ticket again |
| "Configure factory.dev-factory.runtime.coding.pi …" | Pi settings missing or empty | Fix `.env`, recreate the container |
| Agent step fails; gateway returns HTTP 426 | CodeMie CLI older than 0.15.8 | `codemie self-update`; the bridge script checks the version |
| Agent cannot reach the gateway (timeouts, connection refused) | A bridge link is down, or the SSO session expired | Run `start-bridge.ps1` again; it restarts what is down and signs in again |
| Bridge answers 401 to the agent | `FACTORY_PI_API_KEY` differs from `bridge-client-key` | Run `start-bridge.ps1 -UpdateEnv .env`, recreate the Factory |
| Podman VM freezes or `podman` stops answering during Jest | Jest ran too many workers for the VM's memory | `wsl --terminate podman-machine-default`, `podman machine start`. Keep `--maxWorkers=2` in the plan; raising `.wslconfig` memory is your decision |
| Verification FAIL | `failureKind` tells: `CANDIDATE` (test assertions failed, repairable), `ENVIRONMENT` (registry/Docker/timeouts), `UNKNOWN` (e.g. a Jest timeout) | Only `CANDIDATE` is offered for repair; for `ENVIRONMENT` repeat the check |
| Execution `RUNNING` with no progress | A stale lease or a lost workload | See "Recovering a stuck execution" in [runbook.md](runbook.md); leftover containers carry the label `factory.dev-execution=<id>` |

Logs: `podman logs faif-factory-1` (secrets are redacted) and the audit timeline on the execution
page. The bridge logs only method, path and status.

## 6. Reset and clean up

- Yarn cache (grows with every module, no eviction): stop the stack, `podman volume rm factory-dev-yarn-cache`.
  The next starting build pays a cold install (about 2 minutes for `ui-users`).
- Gateway relay: `podman rm -f factory-codemie-relay` (the script recreates it).
- Fork branches from finished or cancelled runs:
  `gh api -X DELETE repos/<fork>/git/refs/heads/factory/<issue>-<execution>`.

## 7. Known limits

- Only the ticket's attachments are passed on (an image shown in the description is one of them, but the agent is not told where it appears); an image behind a link is not fetched.
- BigTest/Karma modules; visual or E2E checks; lint; tickets that span a backend and a UI
  repository.
- **Upgrading to the lockfile pin (phase 6).** The flow steps `implement`, `continue-implementation`,
  `verify` and `pr-follow-up` now exchange `dev_lockfile.json`, and the engine requires every declared
  input to exist. An execution that is already past `implement`, or waiting at the PR follow-up, when the
  new version is deployed fails with "requires artifact 'dev_lockfile.json' which does not exist". Finish
  or cancel those executions first (merge or close their PRs), then deploy.
- A pinned lockfile only fixes versions inside one execution. A new execution of the same ticket
  pins again and can see newer npm-folioci snapshots.
- Jest tests can be flaky (`ui-users` has one that times out now and then; ui-inventory's
  `InstancesList › Reset all` fails in about a third of full runs, so about half of its tickets fail
  in the starting build or in verification); the flow does not re-run them. Run the ticket again.
- The starting build and the independent verification keep only the last 2 MiB of each output
  stream; a very verbose suite is not a failure.
- Delivery has no approval gate; the control is the `authorized` flag and the fork-only rule.
- `yarn install` takes a lock in the shared cache (`--mutex`), so two starting builds no longer corrupt
  it. The `chown` before a build and the cache copy into a coding or verification container work only
  on yarn's version directory, so they do not meet the lock or yarn's temporary entries. Two tickets
  at once have worked on a warm cache, (started together) on a cold one, and staggered (ui-marc-authorities
  started 6 s after ui-checkin entered its coding step; both delivered). With six modules the cache is
  about 4 GB and the seed copy takes about 35 s against a 300 s limit.
- If a starting build still fails in the cache `chown`, or a container in the cache copy, with "No such
  file or directory", run the ticket again and tell whoever maintains the flow.
- **Upgrading to the install lock.** The install command is part of the verification receipt. An
  execution that verified before the deploy and has not published yet fails at delivery with
  "Verification policy changed"; deploy when none is between `verify` and `publish`.
