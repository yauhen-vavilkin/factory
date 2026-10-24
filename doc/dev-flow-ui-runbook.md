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
- **Several tickets.** Run one UI ticket at a time for now: concurrent starting builds share one
  writable yarn cache and nothing serializes them (see the risks in the plan).

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
4. The module must use Jest through `@folio/jest-config-stripes` and have a `yarn.lock`
   (modules without one wait for phase 6). BigTest/Karma modules are not supported.
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
```

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

- Modules without `yarn.lock` (phase 6); BigTest/Karma modules; visual or E2E checks; lint;
  tickets that span a backend and a UI repository.
- Jest tests can be flaky (`ui-users` has one that times out now and then); the flow does not
  re-run them.
- Delivery has no approval gate; the control is the `authorized` flag and the fork-only rule.
- The shared yarn cache is not locked against concurrent writers, and the 120 s seed copy limit has
  not been tested with several large modules (plan, risks).
