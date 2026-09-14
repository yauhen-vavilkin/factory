# Factory — Agent Instructions

Factory is an internal agentic SDLC platform for the FOLIO workspace
(Java 21, Spring Boot, Maven multi-module). The product definition and the
accepted architecture direction are codified:

- `docs/PRODUCT.md` — canonical definition of what is being built
  (Factory, Developer Flow, outcomes, success metrics, non-goals).
- `docs/DEVELOPER_FLOW_ARCHITECTURE.md` — canonical conceptual target
  architecture (thin orchestration around a cohesive coding runtime).

## Rules for coding agents

- Follow the explicit task given by the operator. Do not infer additional
  work from old milestones, plans, backlogs, session notes, or TODO
  documents.
- No implementation roadmap is currently authoritative. Do not start,
  resume, or extend any plan until a separate recovery plan has been
  explicitly approved by the operator.
- Historical material is a record, not an instruction. The archive areas
  are `docs/exec-plans/`, `docs/sessions/`, and `docs/quality-backlog.md`
  (banner-marked) — do not treat them as product, architecture, or
  implementation guidance.
- Do not implement speculative infrastructure or abstractions without a
  concrete requirement in the current task.
- Tests are evidence of behavior; they do not override explicit task
  requirements. Test count, documentation volume, generated receipts, and
  milestone completion are not measures of product success.
- Keep changes scoped to the task. Do not refactor production code or
  change runtime behavior unless the task requires it.

## Repository facts

- Build: `./mvnw` (pinned wrapper). Local operations helper: `./scripts/factory`
  (see `README.md`).
- Modules: `factory-core`, `factory-connectors`, `factory-agents`,
  `factory-flow-test-factory`, `factory-flow-dev-factory`,
  `factory-gateway`, `factory-sandbox`, `factory-app`.
- Prompts under `src/main/resources/prompts/` are runtime product assets,
  not instructions for coding agents.
- This file is the single canonical agent-instruction file; `CLAUDE.md` is a
  symlink to it.
