# Sources and evidence boundary

## What was and was not checked

The uploaded Pi decision and original specification were read. GitHub was inspected again through the connector: remote `main` had advanced to **`9f46a1d9f52b96558116452e6b15f24ba8e650bb`**, and M1 was already reported accepted. Relevant updated source and the current task were read, rather than assuming the old research revision remained current. Local uncommitted work was not available.

Pi's pinned RPC/settings/models documentation was selectively rechecked; RPC settled/queue semantics and noninteractive project-trust handling agree with the accepted decision. This was not a new comparative runtime study. Package installation, its complete source, provider entitlement and runtime behavior were not independently executed.

A read-only `git ls-remote` attempt from the working container failed because GitHub DNS resolution was unavailable. Maven and Docker executables were not found there. Therefore **no Factory compilation, Docker execution, Pi runtime test or live provider call is claimed**. The handoff's tests are future acceptance work. ZIP/text/reference integrity checks concern only this planning package.

## User-provided decision sources

- **P1:** [PI_DECISION.md](inputs/PI_DECISION.md), verbatim copy of `FACTORY_IMPLEMENTATION_INPUT(1).md`. SHA-256: `c53ff8263e1994e74103582610b2733acfa92bd181d1e23b55b62791462b28db`. Authoritative HYBRID/Pi selection; exact IDs/flags/configuration are accepted research input, subject to the implementation qualification gates. Explicit sequencing/scope amendments are in DEFERRED.md.
- **B1:** `dev-factory-v1-implementation-specification.md`, supplied earlier in this conversation. SHA-256: `191f47e939be6790d129bc589aa83337dcc622b78d5819adebb71bfd4b98b5a0`. Historical audit at `4ce0ba7d29e34b287f1664ea246a86dd50d34213`, including pre-solution base and SmallRye verification design. Old M2–M5 scope is superseded. Not copied into this archive to avoid making every fresh session ingest the obsolete plan.

## Rechecked repository references

All links below are pinned to the inspected remote revision.

| ID | Source | Supports |
|---|---|---|
| R1 | [Committed status](https://github.com/yauhen-vavilkin/factory/blob/9f46a1d9f52b96558116452e6b15f24ba8e650bb/docs/exec-plans/dev-factory-v1/STATUS.yaml) | M1 ACCEPTED, accepted commit and reported validations. |
| R2 | [Committed M1 report](https://github.com/yauhen-vavilkin/factory/blob/9f46a1d9f52b96558116452e6b15f24ba8e650bb/docs/exec-plans/dev-factory-v1/reports/M1.md) | Existing implementation, preserved changes, tests and grouped-test interference; report, not an independently executed test. |
| R3 | [Current Quickstart](https://github.com/yauhen-vavilkin/factory/blob/9f46a1d9f52b96558116452e6b15f24ba8e650bb/docs/QUICKSTART.md) | Working documented M0/M1 commands; host JVM/Compose; current live key guard; Pi commands not present in this documentation. |
| R4 | [FileInboxTrigger](https://github.com/yauhen-vavilkin/factory/blob/9f46a1d9f52b96558116452e6b15f24ba8e650bb/factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/inbox/FileInboxTrigger.java) | Current event type is hardcoded file.inbox; admission emits exact SHA and resolved intent. |
| R5 | [DockerSandboxService](https://github.com/yauhen-vavilkin/factory/blob/9f46a1d9f52b96558116452e6b15f24ba8e650bb/factory-sandbox/src/main/java/org/folio/factory/sandbox/core/DockerSandboxService.java) | Blocking exec, shallow clone, global Maven volume, unbounded builders and wait without process termination; source inspection only. |
| R6 | [CodingWorker (lines 1–240 inspected)](https://github.com/yauhen-vavilkin/factory/blob/9f46a1d9f52b96558116452e6b15f24ba8e650bb/factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/worker/CodingWorker.java) | Direct CodingHarness dependency, pending-preparation guard, export/recovery ownership; not a full re-audit. |
| R7 | [ExecutionContractFactory](https://github.com/yauhen-vavilkin/factory/blob/9f46a1d9f52b96558116452e6b15f24ba8e650bb/factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/contract/ExecutionContractFactory.java) | Mandatory seed hash and all-residual-unknowns freeze guard require explicit Pi-profile adaptation. |
| R8 | [TrustedProfileCatalog](https://github.com/yauhen-vavilkin/factory/blob/9f46a1d9f52b96558116452e6b15f24ba8e650bb/factory-flow-dev-factory/src/main/java/org/folio/factory/devfactory/profile/TrustedProfileCatalog.java) | Current profile fixes ARM64/image/wrapper/NONE-network assumptions; generic verify-named plan actually invokes test. |
| R9 | [Clean MODSIDECAR-208 task](https://github.com/yauhen-vavilkin/factory/blob/9f46a1d9f52b96558116452e6b15f24ba8e650bb/evals/tasks/MODSIDECAR-208.md) | Full functional requirements and AC1–AC4. |
| R10 | [Existing dev-factory descriptor](https://github.com/yauhen-vavilkin/factory/blob/9f46a1d9f52b96558116452e6b15f24ba8e650bb/factory-flow-dev-factory/src/main/resources/flows/dev-factory.yaml) | coding → finalize, file.inbox and retry max_attempts 3; keep separate from new Pi flow. |

The claimed base `c13e0383d9283ef554c195cf5357bb3c6eeb4e65` and historical dependency availability are carried from B1, not newly live-built here. The implementation must prove that checkout and its baseline. No base SHA is invented for any other task.

## Current authoritative documentation consulted

- **P2:** [Pi RPC at v0.85.1](https://github.com/earendil-works/pi/blob/v0.85.1/packages/coding-agent/docs/rpc.md). Settled versus low-level end, prompt acknowledgment, queue clearing, stats/events and cumulative streaming usage.
- **P3:** [Pi settings at v0.85.1](https://github.com/earendil-works/pi/blob/v0.85.1/packages/coding-agent/docs/settings.md). Project trust, compaction and separate native/provider retry settings.
- **P4:** [Pi model configuration at v0.85.1](https://github.com/earendil-works/pi/blob/v0.85.1/packages/coding-agent/docs/models.md). Custom provider reference. Not every compatibility field in P1 is restated in this documentation; test the accepted wire settings rather than claiming this page verifies all of them.
- **D1:** [Docker security](https://docs.docker.com/engine/security/) and [bridge networks](https://docs.docker.com/engine/network/drivers/bridge/). Background for daemon privileges and network configuration. The exact proposed gateway topology still needs local negative tests; these docs are not proof that the implementation is isolated.
- **S1:** [Official Codex model guidance](https://developers.openai.com/codex/models) and [subagent guidance](https://developers.openai.com/codex/subagents), which currently redirect to ChatGPT Learn. They support configurable model/reasoning selection and Medium as a balanced starting point. Our per-milestone recommendations are engineering judgments, not vendor guarantees or measured estimates of the user's allowance.
- **Z1:** [Z.ai Chat Completion reference](https://docs.z.ai/api-reference/llm/chat-completion). Consulted for the API surface. Exact account/model availability, reasoning behavior and prices remain live/operator checks.

## Design decisions versus inspected facts

The new flow identity/event, fresh-resolution preparation mode, fixed artifact-prefix routes, minimal local ledger, soft disk guard and four-milestone sequence are **this re-plan's decisions**, not claims that existing code already provides them. New class/command/profile names elsewhere in this package are implementation targets. Current source locations are documented to avoid repeating the whole architecture investigation.

The ten requested quality questions are addressed by: early real-Pi proof (M2); first FOLIO at M3; a core-change burden of proof; a runtime-neutral boundary; disk-backed prompts/reports; optional Jira/PR; one .env/launcher path; explicit deferred triggers; and request/tool/verification evidence before the first paid task. No guarantee of a particular LOC count, elapsed implementation time or coding success rate is made.
