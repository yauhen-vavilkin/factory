# Local operation contract

This is the verified M3 operator path for one local Pi execution. Reuse the existing M0/M1 launcher behavior and names; the Pi-specific commands below now cover gateway qualification, evaluation admission, observation, and bounded export. [R3](SOURCES.md)

## Configuration

Keep one gitignored root `.env`, mode 0600, and `.env.example` without secrets. Existing environment wins over the file, then documented defaults; print a redacted effective configuration. Parse dotenv as data, not `eval`. Never pass the whole host environment or `.env` to target containers.

Proposed public settings:

```dotenv
FACTORY_CODING_RUNTIME=pi
FACTORY_MODEL_PROVIDER=factory-zai
FACTORY_MODEL_BASE_URL=https://api.z.ai/api/paas/v4
FACTORY_MODEL_ID=glm-5.3-flash
FACTORY_MODEL_API_KEY=
FACTORY_MODEL_THINKING=high
FACTORY_MODEL_CONTEXT_TOKENS=65536
FACTORY_MODEL_MAX_OUTPUT_TOKENS=16384
FACTORY_ALLOW_UNPRICED=true
```

The model ID is accepted research input, **not a verified entitlement**. Base URL is the trusted upstream, not the URL exposed to Pi. The generated Pi configuration points at its per-run gateway. Any existing equivalent operator setting should be mapped once, not duplicated as a conflicting second default. Conflicting legacy Pi/Anthropic/harness values fail clearly or are reported as inactive.

Use General API by default as selected in the Pi decision. An operator may select the distinct Coding Plan endpoint/profile deliberately; do not infer billing/access from a key or switch products silently. No actual price is supplied here. Optional `FACTORY_PRICING_FILE` points to a small operator-owned rate configuration; every run retains its applicable nonsecret snapshot.

Pi sees only the fixed local coding-gateway URL, so `factory-sandbox/pi-models.json` carries the explicit Z.AI compatibility fields (`system` role, Z.AI thinking format and tool streaming). Do not remove them when changing the gateway or upstream URL; URL-based compatibility detection cannot see through this route. Preparation, frozen export and independent verification use a separate dependency-only gateway with Maven GET/HEAD routes and no provider key, run token or model route. The coding gateway's host binding is loopback-only for the explicit doctor; it is not an externally reachable service.

Put fixed runtime version, compaction/retry defaults, resource limits and trusted mirror routes in one versioned Pi profile. They need not become dozens of required `.env` variables. Initially only coding uses a model, so there is no reason to configure analysis/review/fix models. CLI reasoning for the implementation agent and Pi's runtime thinking level are separate settings.

## Tested path required by M3

```bash
cp .env.example .env
# Edit .env with the chosen provider account and service ID.
./scripts/factory init
./scripts/factory infra up
./scripts/factory start --mode pi
./scripts/factory smoke --pi --scripted
./scripts/factory doctor --live
./scripts/factory run-eval MODSIDECAR-208 --run-key low-001
./scripts/factory show <execution-id> --watch
./scripts/factory export <execution-id> --out .factory/exports/<execution-id>
./scripts/factory stop
```

`infra up` wraps Compose; preserve its persistent DB and loopback bindings. The image is built/pulled explicitly before task spend, then reused by immutable identity. `start --mode pi` uses the host JVM, the Pi flow and the Pi-specific inbox event; it does not need an Anthropic key and makes no hidden paid probe. The provider key is mapped from legacy `GLM_API_KEY` only when the new `FACTORY_MODEL_API_KEY` is empty, and is passed only to the fixed coding gateway. Offline mode remains separate and key-free. The coding and dependency gateways are removed on a clean stop and on startup failure; the dependency gateway has no provider credentials.

`smoke --pi --scripted` runs actual pinned Pi/Docker against a deterministic fake upstream. It cannot contact the paid provider even if `.env` contains a key.

`doctor --live` is a separately logged, explicit billable qualification: text and tool→result→final roundtrip through the selected Pi/gateway configuration, actual wire options and usage. Limit one probe to 4 upstream attempts, 2,048 output tokens/request and 5 minutes. A fresh valid probe for the same configuration may be reused; don't repeatedly spend on it during unrelated code edits. A provider error is a provider blocker, not Pi NO-GO. Missing reported-model metadata stays unknown; a reported mismatch fails unless an explicit approved alias mapping explains it.

`run-eval` is a thin deterministic adapter: read the complete `evals/tasks/<id>.md`, combine it with a trusted curated repo/base/profile/check mapping, set the requested runKey, and call existing atomic admission. It does not execute another agent or bypass the Factory flow. Fail duplicate/missing mappings; don't auto-consume all eight tasks or scan `evals/solutions` into prompts. Ordinary YAML/JSON submission remains supported.

`show` exposes execution stage, Pi activity, request/tool counts, usage coverage, durations, result and artifact paths. A console summary/existing API page is enough. `export` copies the run's allowed artifacts, not arbitrary host paths. Both handle an interrupted or failed execution.

## Local limitations accepted explicitly

One active task, manual restart reconciliation, no automatic paid replay, public approved repositories, Java21/unit profile, no PR/Jira writes. Cold dependencies can be slower; correctness is not advertised as bit-reproducible across mutable SNAPSHOT downloads.

Use real CPU/RAM/PID settings and a disk guard: sample per-run writable use and host free space, stop on the approved 8GiB run threshold or insufficient free space, and record overshoot. This does not guarantee a hard filesystem quota or hostile-workload containment. Logs/artifacts have separate bounded storage. Hard quotas/VM isolation require an appropriately supported runner and are deferred, not claimed.

Restart must preserve completed outputs. On interruption, stop/retain known labelled resources and require an explicit new run after diagnosis. Do not hide unknown provider charges or missing tail events after a hard crash. No global Docker pruning or automatic DB resets.
