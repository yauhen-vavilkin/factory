# Pi integration contract — implement only this boundary

The attached [accepted decision](inputs/PI_DECISION.md) is the source for pinned runtime choices. The selective recheck and its limits are in [SOURCES.md](SOURCES.md). This file specifies the small integration, not a new harness.

## Runtime and configuration

Pin **`@earendil-works/pi-coding-agent@0.85.1`** in the image's dependency lock; subsequent builds use `npm ci`. Use the complete runtime, not `pi-agent-core`. Pin a supported Node patch meeting the package's recorded requirement (>=22.19.0); record actual Node/JDK/Maven/Pi versions, platform and OCI image identity. Do not upgrade/install Pi at task runtime. Only JDK21 is needed initially.

Private environment:

```text
HOME=/home/agent
PI_CODING_AGENT_DIR=/state/pi
TMPDIR=/state/tmp
PI_OFFLINE=1
PI_TELEMETRY=0
FACTORY_MODEL_TOKEN=<Factory-generated expiring run token>
```

`PI_OFFLINE` does not disable model requests. Generate fresh settings/models files for every attempt, with no inherited developer configuration. Mount authoritative settings/model/instruction files read-only even though sessions and other state are writable. Project trust stays disabled. [P1, P3](SOURCES.md)

Launch direct argv, cwd `/workspace/repo`, never shell-interpolate task text:

```text
/opt/pi/node_modules/.bin/pi
--mode rpc
--provider factory-zai
--model <configured exact service ID>
--thinking <configured level>
--no-approve --no-extensions --no-skills --no-prompt-templates
--no-themes --no-context-files
--tools read,bash,edit,write,grep,find,ls
--append-system-prompt <contents of the small Factory policy as one argument>
--session-dir /state/pi/sessions
```

Keep Pi's native prompt. Append only task ownership, scope, no external writes and reporting policy. Explicit approved repository guidance may be read as text from the base and hashed; no automatic project extension/config loading. Start without curated skills.

Use custom provider `factory-zai`, `api: openai-completions`, gateway base URL, and `apiKey: "$FACTORY_MODEL_TOKEN"`. Reject executable `!command` configuration. Initial upstream/model intent is Z.ai General API / `glm-5.3-flash`; its exact account availability remains a preflight question, not a reason for automatic replacement. Coding Plan is a separate explicitly selected billing/endpoint profile, never an implicit default.

Use accepted compatibility settings from P1, including Z.ai thinking format and assistant reasoning/tool-history replay. Verify serialized wire fields through fake requests and the live preflight, rather than assuming generic OpenAI compatibility.

Initial caps: context 65,536; output 16,384; native compaction enabled with reserve 16,384 and keep-recent 20,000; Pi agent retries 3 with base delay 2,000ms; provider retries 0 and timeout 300,000ms. These are configuration caps, not claims about provider maxima. Fail clearly when unsupported. Gateway and Factory must not add another model retry layer.

## RPC lifecycle

One Pi process handles one task attempt. Connect stdout/stderr readers before writing; incrementally decode UTF-8 and frame JSONL across arbitrary Docker chunks. Correlate command responses by ID; record unknown event types without misclassifying them as completion. Bound buffers and artifact growth without dropping required evidence silently.

```text
get_state → verify provider/model/config
prompt(task JSON) → response acknowledges admission only
consume events → agent_settled
get_state + get_session_stats + get_last_assistant_text
collect session file / native events
close stdin → await bounded exit → stop entire coding container
export/freeze → independent verifier
```

`agent_end` can precede retry/compaction; it is not terminal. A clean exit or `prompt.success=true` is not task success. Missing settled/completion evidence yields an explicit interrupted/error result. Do not wait forever for the process to exit after EOF. [P1–P2](SOURCES.md)

Cancellation: `clear_queue`, then `abort`; allow 5 seconds, SIGTERM with another 5 seconds, then stop/kill the **whole container workload**, including detached descendants. Apply a monotonic overall deadline externally. Do the final whole-workload stop even after normal Pi completion; snapshot only after writers are gone. Keep already received events if final stdout does not flush.

## Minimal trusted gateway, not a platform

**Concrete topology:** two narrow per-run gateway containers are managed by Factory with the sandbox. The coding gateway attaches to a private internal coding network and an egress network; it holds the provider key and run token and serves the fixed model route plus approved Maven routes. Preparation, frozen export and verification attach to a separate private dependency network and its dependency-only gateway, which has no provider key, run token or model route and serves only approved Maven routes. Neither internal network includes the DB or control API, and no routing/NAT forwarding is enabled through a sandbox. The coding gateway's optional host binding is loopback-only for the explicit doctor, so no gateway port is externally reachable. Use the existing Java distribution for a narrow gateway process where practical; no new general-purpose service framework is justified.

The upstream provider key is supplied only to the trusted gateway, never to Pi or target builds. Factory creates an unguessable run token, expected model, expiry and shared attempt/call/output budgets before launch. All requests—including extra requests initiated through bash—consume the same run budget. Reject wrong/expired tokens and excess requests. Revoke access on completion/cancellation; no externally reachable administration API is needed.

Gateway supports only a fixed `POST /v1/chat/completions` route to the configured upstream, preserving streaming SSE and provider error bodies. Disable transport retries and unsafe redirects. Enforce configured model and output cap; preserve approved reasoning/history fields. Capture request identity/timing/requested model/reported model/raw usage, not authorization headers or secret-bearing full payloads. Do not implement protocol translation, model routing, caching, billing reconciliation or an LLM loop.

For the real Maven profile, reuse this small gateway's HTTP plumbing for **fixed read-only GET/HEAD artifact-prefix routes** to the few approved Maven roots. Generate trusted Maven mirror settings covering dependency and plugin repositories. No CONNECT, arbitrary upstream URL, write method, open forward proxy or artifact storage/cache. Redirects must stay within approved public origins; traversal and private-address targets are rejected. These routes are a bounded implementation choice to preserve P1's approved-mirror policy without building a dependency-proxy platform.

Disable unused IPv6 routing for the initial local profile and test the actual platform: direct Internet, host/control-plane/DB, LAN/metadata and unapproved destinations must be unreachable from coding. Environment proxy variables and `internal:true` alone are not proof. A platform that cannot enforce the selected boundary is an explicit local capability blocker; do not quietly grant broad egress.

Default limits: 2 CPU, 4GiB RAM, 512 PIDs; per-stream retained logs 16MiB, run evidence 1GiB; task 60 minutes, coding 30 minutes, ordinary short command 300 seconds, build 900 seconds, at most 40 upstream attempts including retries/compaction. Whole-task budgets bound Pi shell work; no custom Pi shell dispatcher is needed. Use a disk-use/free-space stop guard locally; this is explicitly not a hard filesystem quota (DEFERRED.md). The gateway expiry also closes access after the parent JVM disappears; this limits spend but does not prove immediate shell cancellation after a host crash. Startup reconciliation stops retained owned containers before allowing another task.

## Minimal telemetry

Write append-only local JSONL while consuming events, under the existing persistent run root, and publish references through the existing ArtifactStore. No new accounting tables, event bus, fsynced replay spool or SPA.

For each gateway attempt: execution/attempt/request ID, requested/reported model, nonsecret effective parameters, start/end/duration, HTTP/error boundary, request-sent status, raw usage when reported. For Pi: native retry/compaction/session/tool events, command text redacted, observed durations and output references. Pi bash may merge streams and omit a numeric exit status; leave it unknown. Factory verifier separately records stdout, stderr and actual exits.

Compute one authoritative usage aggregate from gateway attempts. Native stats are an independent cross-check, not an additive second total. Streaming usage is cumulative: retain the final/most complete value, do not sum chunks. Preserve separate compaction usage where attributable. Do not fabricate per-Pi-turn correlation when absent; gateway requests remain observable and attributable to the run. Missing/cache/cost fields stay null; custom-model cost=0 is not evidence of free calls.

A small optional operator rate file plus a snapshot/hash in the run is sufficient for estimated cost. Use BigDecimal and verified non-overlapping usage units. With missing rates/usage show UNPRICED/PARTIAL and known subtotal, not a false total. Keep all failed/retried attempts in totals. Explicit unpriced operation is allowed; no invented real tariffs.

Failure-stage labels: PREPARE, PROVIDER, PI_PROTOCOL, PI_RUNTIME, SANDBOX, EXPORT, VERIFY, STORAGE. They identify observed boundaries, not automatically proven root causes. RPC/session logs are potentially sensitive: restrictive permissions, bounded retention and redacted export. Do not require hidden chain-of-thought.
