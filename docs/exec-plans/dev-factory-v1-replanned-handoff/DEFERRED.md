# Deferred scope and explicit amendments

Deferred does not mean implemented, safe by default or permanently unnecessary. None of these items may silently reappear as a prerequisite for the first Low.

| Classification | Deferred work | Trigger to reconsider |
|---|---|---|
| **DEFER UNTIL EVIDENCE** | Distributed fencing/heartbeat overhaul, crash-atomic cross-store publication, flow-version recovery, automatic conversation resume, blob/GC subsystem | A reproduced failure cannot be contained by single execution, no paid replay and preserved outputs |
| **DEFER UNTIL EVIDENCE** | Immutable Maven seeds, shared cache optimization, artifact repository service, full dependency reproducibility | Repeated dependency latency/drift materially harms measured runs |
| **DEFER UNTIL EVIDENCE** | Universal telemetry schema/tables, fsynced replay spool, AS_RUN/REPRICED ledger, provider billing reconciliation | Missing accounting evidence prevents a concrete decision or audit |
| **DEFER UNTIL EVIDENCE** | OpenCode fallback adapter; Pi fork/SDK bridge; new skills/prompt systems | Reproduced Pi-specific NO-GO or measured context/tool failure, not a missing key |
| **DEFER UNTIL MULTI-REPO** | Generic repository inference, wider catalogs, automatic profile intelligence, JDK17/Node/frontend support | A named next task needs it; keep current working M1 mechanisms |
| **DEFER UNTIL PR DELIVERY** | Independent LLM review, Factory-level fix loop, GitHub publication/approval, Jira updates | The local candidate/verification path is useful; deliver only the verified candidate with explicit authorization |
| **DEFER UNTIL HOSTILE/MULTI-TENANT EXECUTION** | VM-grade isolation for all workloads, hard filesystem quotas across backends, concurrent tenants, comprehensive network/policy engine | Untrusted arbitrary repositories, stronger guarantees or multiple operators |
| **DEFER UNTIL TESTCONTAINERS TASK** | Disposable per-run VM/daemon lifecycle, nested-container networking and Ryuk profiles | A selected task has mandatory Docker-backed tests; until then INCOMPLETE, not a passing skipped suite |

## What is deliberately changed from the old specification

- **Preserve M0/M1; do not implement old M2–M5 wholesale.** The custom Spring AI loop is legacy, not something to productionize first.
- **Retries:** Pi owns its native retries; provider SDK, gateway and outer coding-step retries are zero/disabled as specified. The old Factory model-retry layer is not reimplemented for Pi.
- **Networking:** Pi cannot use the old `network=none` coding policy. It uses a controlled gateway; the provider key remains outside coding. This is a required topology change, not permission for unrestricted egress.
- **Dependencies:** fresh private resolution through fixed approved routes replaces mandatory offline seed replay. The versioned contract represents that mode honestly. No fake dependencySeedHash and no arbitrary upstream proxy.
- **Telemetry:** raw/native events, gateway usage and a local summary replace the old multi-table accounting platform. Optional configured rates are snapshotted once; unknown cost is permitted explicitly.
- **Outcome:** Pi's `agent_settled`/exit status is runtime completion, not final SUCCESS. Factory independently verifies. Old technical COMPLETED/VERIFIED_LOCAL labels are not blindly reused as quality claims.

## Scope amendments to the Pi input (not silent corrections)

The attached input remains the runtime decision. This plan changes implementation order/width, not the chosen runtime or RPC protocol:

1. Only JDK21 is implemented initially; JDK17 waits for a named task.
2. Reuse a Java test driver for early RPC proof, then the actual Factory flow. Do not build a separate standalone product and integrate it later.
3. Replace three mandatory live tiny-fixture rehearsals before FOLIO with scripted mechanical qualification, one bounded live preflight, then the real Low. Repeated real runs occur in M4; extra tiny live probes need a specific failure hypothesis.
4. Approved dependency-mirror access is implemented with a few fixed GET/HEAD routes alongside the model gateway, not a dependency-management platform. Coding still has no arbitrary egress or upstream key.
5. Hard disk quotas are deferred for this local, known-repository profile. Implement explicit disk-use/free-space monitoring and whole-workload stop, report overshoot and residual risk. Do not claim hard storage containment; stronger requirements need a supported runner.
6. Native project-trust disabling, cancellation, raw usage, fresh verification and the required scripted failure cases remain pre-live safeguards. They are not removed for speed.

## Non-deferred safeguards

No host Docker socket/developer HOME/SSH agent/upstream key in coding; no arbitrary upstream routing; pinned runtime; actual whole-workload stop; preserved partial candidate; independent checks and honest missing evidence; no secret-bearing public export; no external production writes. These are part of the selected boundary, not a later security campaign.
