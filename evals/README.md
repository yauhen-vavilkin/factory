# FOLIO AI Coding Factory — Evaluation Benchmark Suite

This directory contains a curated benchmark set of **8 real-world FOLIO tasks** from **Eureka Sprint 248**, designed to evaluate autonomous coding agents and factory workflows against human-engineered ground truth solutions.

---

## Benchmark Design & Criteria

Every task in this benchmark satisfies strict evaluation constraints:
1. **Single-Repository Scope:** Each task is 100% self-contained within one repository. It does not require synchronized changes across other services or libraries.
2. **Exactly 1 Ground Truth PR:** Each task was solved and merged via a single PR into `master`.
3. **Verified Backlog Realism:** Tasks represent genuine user stories and bug fixes groomed by the team during sprint planning.
4. **Graded Complexity:** Tasks span three distinct difficulty tiers based on scope, architectural breadth, and cognitive load.

---

## Directory Structure

```
evals/
├── tasks/           # Clean task prompts for coding agents (NO solution hints or PR links)
│   ├── MGRENTITLE-172.md
│   ├── MODSIDECAR-208.md
│   ├── MGRENTITLE-192.md
│   ├── MODSCHED-76.md
│   ├── MODROLESKC-424.md
│   ├── MODROLESKC-427.md
│   ├── MODSCHED-70.md
│   └── MGRENTITLE-161.md
├── solutions/       # Ground truth references and grading rubrics for the evaluation harness
│   ├── MGRENTITLE-172.solution.md
│   ├── MODSIDECAR-208.solution.md
│   ├── MGRENTITLE-192.solution.md
│   ├── MODSCHED-76.solution.md
│   ├── MODROLESKC-424.solution.md
│   ├── MODROLESKC-427.solution.md
│   ├── MODSCHED-70.solution.md
│   └── MGRENTITLE-161.solution.md
└── README.md
```

---

## Task Inventory

| Complexity | Key | Repository | Issue Type | Story Points | PR Diff | Description |
|---|---|---|---|---|---|---|
| 🟢 **Low** | **MGRENTITLE-172** | `mgr-tenant-entitlements` | Story | 1 SP | 3 files (+3/-3) | **Schema Tweak:** Remove/increase maxItems limit on reinstall endpoint in JSON schema. |
| 🟢 **Low** | **MODSIDECAR-208** | `folio-module-sidecar` | Bug | 0.5 SP | 3 files (+3/-0) | **Config / Tuning:** Set default Quarkus worker thread pool size to 8 and document env var. |
| 🟢 **Low** | **MGRENTITLE-192** | `mgr-tenant-entitlements` | Story | 1 SP | 10 files (+25/-11) | **Model / Event Enrichment:** Add `moduleId` to Kafka `SystemUserEvent` and update provider + tests. |
| 🟡 **Medium** | **MODSCHED-76** | `mod-scheduler` | Task | 3 SP | 6 files (+135/-4) | **Service Feature:** Add configurable `initialDelay` for Kafka `SYSTEM` simple timers in Quartz + tests. |
| 🟡 **Medium** | **MODROLESKC-424** | `mod-roles-keycloak` | Bug | 3 SP | 8 files (+99/-61) | **Core Bugfix:** Fix loss of nested capabilities during repeated Kafka event reprocessing + IT tests. |
| 🟡 **Medium** | **MODROLESKC-427** | `mod-roles-keycloak` | Story | 3 SP | 15 files (+722/-4) | **REST API Feature:** Add batch effective capability-set lookup endpoint (`POST /users/capability-sets/query`). |
| 🔴 **High** | **MODSCHED-70** | `mod-scheduler` | Task | 3 SP | 21 files (+946/-11) | **Concurrency & Fault Tolerance:** Retry with exponential backoff for timer execution and prevent overlap. |
| 🔴 **High** | **MGRENTITLE-161** | `mgr-tenant-entitlements` | Bug | 3 SP | 62 files (+1814/-275) | **Engine State Machine:** Graceful completion and failure cascading for desired-state flows in non-async mode. |

---

## Evaluation Workflow

1. **Agent Execution:**
   - Supply the agent with a file from `tasks/<KEY>.md` as the problem description.
   - Point the agent to a checkout of the target repository at the commit **immediately preceding** the merged ground truth PR.
   - Disallow external network access to the ground truth PR.
2. **Grader Verification:**
   - Use the corresponding rubric from `solutions/<KEY>.solution.md`.
   - Run the project's build and automated test suites (`mvn clean test` / `mvn clean verify`).
   - Compare the agent's diff against the ground truth PR diff (inspecting touched files, schema adherence, and edge-case handling).
