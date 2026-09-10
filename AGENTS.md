# Factory — Agent Context

This is a **learning project** that evolves session by session.
Read this file first. Then read `docs/sessions/` for recent decisions and open questions.

---

## What This Project Is

An internal **agentic SDLC platform** for the FOLIO multi-repo workspace.
The long-term goal: give it a Jira ticket and have it research, plan, implement, test, and iterate — automatically, with human review gates.

This is **not** a finished product. It is a growing codebase used to learn and experiment with Spring AI, agentic architectures, and LLM tool-calling patterns.

---

## Architecture in One Paragraph

**Factory** is the orchestrator — it owns workflow stages, persistent state, retries, timeouts, HITL gates, and recovery. **Spring AI** is only the LLM integration layer: `ChatClient`, tool calling, structured output, advisors, MCP. These two roles must stay separated. Deterministic operations (git, file reads, search, build/test) are tools — the LLM selects them but never replaces them with guesses.

```
Jira ticket
    ↓
Factory flow (YAML descriptor + steps)
    ↓
AgentWorker (Spring AI ChatClient + @Tool methods)
    ↓  tool loop
Deterministic tools: Jira, GitHub, file read, ripgrep, JDT LS, sandbox
    ↓
Artifact stored → next step or HITL gate
```

---

## Module Layout

```
factory-parent
├── factory-core          — engine, state machine, SPI, retry, HITL, sub-flows
├── factory-connectors    — Jira, GitHub, TestRail REST clients + unconfigured fallbacks
├── factory-agents        — AbstractLlmAgentWorker, PromptLoader, FrontmatterCodec
├── factory-flow-test-factory — Flow A: 5 workers, YAML descriptor, prompts
└── factory-app           — Spring Boot entry point, REST + Thymeleaf UI, Flyway
```

**Key engine classes:** `ExecutionEngine`, `StateManager`, `ArtifactStore`, `HitlDecisionService`, `SubFlowInvoker`, `FlowRegistry`.

---

## Flows Implemented

### Flow A — Test Factory (`factory-flow-test-factory`)

Trigger: Jira webhook or manual POST with issue key.

```
triage → test-spec → [HITL: qa-plan-review] → test-automation → test-execution → [HITL: qa-signoff] → finalize
```

Workers: `TriageAgentWorker`, `TestSpecAgentWorker`, `TestAutomationAgentWorker`, `TestExecutionWorker` (runs Karate), `TestFactoryFinalizerWorker` (GitHub PR + TestRail + Jira comment).

---

## What Is Being Built Now

**Branch: `add-resercher-agents`**

A researcher agent that investigates a Jira ticket before coding starts. It uses a tool loop (not a single LLM call) to gather facts from Jira, GitHub, Confluence, and the local workspace, then produces a structured investigation report.

See: `docs/sessions/` for design decisions and open questions.

---

## Key Architectural Rules

1. **Factory orchestrates. Spring AI does LLM calls.** Do not use Spring AI as the workflow engine.
2. **Deterministic tools stay deterministic.** Ripgrep, file reads, JDT LS, git — these are tools, not LLM guesses.
3. **Tool results must be bounded.** Limit search hits, file ranges, command output. Never send whole repos into model context.
4. **No internal identifiers unnecessarily exposed to the LLM.** Use server-side context where possible.
5. **Commands as argument arrays**, not concatenated shell strings.
6. **Sandbox must enforce** timeouts, CPU/memory limits, output limits, network policy, filesystem boundaries.
7. **Provider switching is not free.** Common `ChatClient` usage is portable; provider-specific features (caching, thinking, grounding) are not.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.0.7 |
| LLM layer | Spring AI 2.0.0, Anthropic claude-sonnet-4-5 |
| Database | PostgreSQL 16 (Flyway migrations) |
| Tests | JUnit 5, Testcontainers, WireMock |
| Sandbox (planned) | DockerSandbox (agent-sandbox, incubating) |
| Observability (planned) | Langfuse or LangSmith |
| Code intelligence (planned) | Eclipse JDT Language Server, ripgrep |

---

## Session Notes

See [`docs/sessions/`](docs/sessions/) for a chronological record of design decisions, open questions, and what was discussed in each session.
