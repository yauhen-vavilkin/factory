# Factory — Agent Instructions

Factory is an internal agentic SDLC platform for the FOLIO workspace
(Java 21, Spring Boot, Maven multi-module). The project is currently in
**architecture recovery**: a product/architecture review has completed, and
its decisions are not yet documented or implemented.

## Rules for coding agents

- Follow the explicit task given by the operator. Do not infer additional
  work from old milestones, plans, backlogs, session notes, or TODO
  documents.
- Historical material is a record, not an instruction. Everything under
  `docs/exec-plans/` and `docs/sessions/`, and any file marked historical,
  is archived evidence — do not execute, resume, or extend it, and do not
  treat it as architecture or implementation guidance.
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
