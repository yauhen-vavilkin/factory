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
