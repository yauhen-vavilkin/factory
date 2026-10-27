# CodeMie gateway bridge (Windows + Podman on WSL)

Lets the Developer Flow's coding containers call the CodeMie gateway on a machine where
Podman runs in a WSL VM. There, published ports and `host.docker.internal` reach the VM, not
Windows, so a Windows-side `codemie proxy` is not reachable from containers.

```
coding container -> host.docker.internal:4102   relay container in the VM (socat)
                 -> VM 127.0.0.1:4101           sshd remote forward (ssh -R, outbound from Windows)
                 -> Windows 127.0.0.1:4101      bridge.js: checks the client key, swaps it for the daemon key
                 -> Windows 127.0.0.1:4001      `codemie proxy`: injects the SSO credential
                 -> CodeMie gateway
```

The container only ever sees the generated client key, never the SSO session. No inbound
firewall rule, `.wslconfig` change or Podman machine setting is needed.

## Use

Prerequisites: Podman machine running, `node`, `ssh`, and the CodeMie CLI 0.15.8 or newer
(`npm install -g @codemieai/code`; the gateway answers HTTP 426 to older clients).

```powershell
.\scripts\dev\codemie-bridge\start-bridge.ps1 -UpdateEnv .\.env   # start, sign in via browser if asked
.\scripts\dev\codemie-bridge\start-bridge.ps1 -Stop               # stop everything
```

The script is idempotent. The client key is generated once in
`%LOCALAPPDATA%\factory-codemie\bridge-client-key` (`-State` changes the folder; keep it outside
the repository). To rotate it, delete that file, run the script with `-Stop`, start it again and
re-run with `-UpdateEnv`. `-UpdateEnv` writes `FACTORY_PI_BASE_URL` and `FACTORY_PI_API_KEY`;
recreate the Factory container afterwards (`docker compose up -d`) so it re-reads `.env`.
The SSO session lasts about a day. Re-running the script checks it with `codemie profile status`; when
the session is missing or expired, the script opens the browser sign-in (`codemie profile login`), then
restarts the proxy and the bridge, because they hold the old credential.
By default the script uses the CLI's own `~/.codemie` profile; `-CodemieHome <dir>` keeps a
dedicated profile and its SSO credential elsewhere, for example
`-State $env:LOCALAPPDATA\factory-codemie -CodemieHome $env:LOCALAPPDATA\factory-codemie\codemie`.

Matching Factory settings for Pi (the gateway serves the OpenAI-style route; for Claude Code
use `http://host.docker.internal:4102` as `FACTORY_CLAUDE_CODE_BASE_URL` with bearer-token
authentication):

```dotenv
FACTORY_DEV_FACTORY_CODING_RUNTIME=pi
FACTORY_PI_PROVIDER=codemie
FACTORY_PI_MODEL=claude-sonnet-5-5
FACTORY_PI_API=openai-completions
FACTORY_PI_AUTHENTICATION=api-key
FACTORY_PI_BASE_URL=http://host.docker.internal:4102/v1
FACTORY_PI_API_KEY=<bridge-client-key>
```

## Check the path

From Windows, `curl http://127.0.0.1:4101/` must answer 401 (up, protected). From a
default-network container, without a key `http://host.docker.internal:4102/` answers 401, and
with `Authorization: Bearer <key>` `/v1/models` answers 200:

```powershell
podman run --rm --entrypoint curl docker.io/library/factory-dev-pi:0.85.1-java21-node22 -s -o /dev/null -w '%{http_code}' http://host.docker.internal:4102/
```

## Things to know

- The relay listens on all VM interfaces, so any container in the VM can reach the bridge, and
  only holders of the client key get through. Undo it with `podman rm -f factory-codemie-relay`.
- The script clears `CODEMIE_*` and `ANTHROPIC_*` variables for the processes it starts. A shell
  started by a CodeMie/Claude wrapper exports them, and they override the CLI's profile lookup.
- It tests the bridge on IPv4 loopback only. The VM's listeners are relayed to Windows on IPv6
  loopback, which would otherwise look like a running bridge.
- Never print or pass the key as a command-line argument, and keep it out of `podman logs`.
