# Gracefully complete entitlement for desired state operation in no async mode

- **Key:** MGRENTITLE-161
- **Repository:** mgr-tenant-entitlements
- **Issue Type:** Bug
- **Complexity:** High

---

## Purpose / Overview

When running an entitlement job using the "desired state" approach in synchronous mode (`async: false` / no async mode), entitlement jobs can become permanently stuck in an incomplete / unfinalized state.

This occurs in scenarios such as:
- A downstream module enablement call takes a very long time (e.g. `mod-agreements` or large data migrations).
- Synchronous entitlement request duration approaches or exceeds `FLOW_ENGINE_EXECUTION_TIMEOUT` (default `30m`), or module installer retry attempts exceed execution timeouts.
- When an individual stage fails or times out in non-async mode, the flow engine leaves the flow and application flow entities in `IN_PROGRESS` or inconsistent states instead of cleanly cascading failure.

Expected behavior:
Entitlement flows executed in non-async mode must gracefully handle timeouts, stage failures, and completion states, properly setting the flow and application-flow status to `FAILED` (or `FINISHED`) and cleaning up uncompleted stage executions.

---

## Requirements / Scope

### Functional Requirements

1. **Synchronous Flow Engine Lifecycle:**
   - In `mgr-tenant-entitlements`, review the flow execution path for synchronous operations (`async: false`).
   - Ensure that when a flow completes or fails in non-async mode, the top-level `Flow` and all associated `ApplicationFlow` rows are transitioned to terminal states (`FINISHED`, `FAILED`, or `CANCELLED`).
2. **Failure Handling and Cascading:**
   - When a stage execution fails or throws an exception during non-async processing, capture the failure reason and ensure the flow engine updates the database records cleanly.
   - Do not leave dangling `IN_PROGRESS` records when an execution finishes or errors out.
3. **Timeout & Interruption Management:**
   - Ensure execution timeouts properly propagate to flow finalizers so that the overall tenant entitlement request receives an accurate error response instead of hanging indefinitely.
4. **Consistency with Async Flow Engine:**
   - Ensure non-async flow lifecycle state transitions align with the standard asynchronous flow engine behavior while maintaining transactional integrity.
5. **Testing:**
   - Add unit and integration tests simulating synchronous flow execution with failing stages, verifying that the flow status transitions to `FAILED` with appropriate error details.

---

## Acceptance Criteria

- **AC1:** In non-async entitlement mode, if a stage fails or times out, the flow status is set to `FAILED` rather than remaining in `IN_PROGRESS`.
- **AC2:** When all stages succeed in non-async mode, the flow status transitions to `FINISHED`.
- **AC3:** Error details and failure causes are preserved in the flow stage history.
- **AC4:** The project builds cleanly with `mvn clean verify`.
