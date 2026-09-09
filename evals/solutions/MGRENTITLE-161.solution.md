# Solution Reference: MGRENTITLE-161

- **Key:** MGRENTITLE-161
- **Title:** Gracefully complete entitlement for desired state operation in no async mode
- **Repository:** `folio-org/mgr-tenant-entitlements`
- **Complexity:** High (3 Story Points)

---

## Ground Truth PR

- **PR:** [#297](https://github.com/folio-org/mgr-tenant-entitlements/pull/297)
- **Status:** Merged into `master`
- **Diff:** 62 files changed (+1814 additions, -275 deletions)

---

## Changed Files Inventory Highlights

```
NEWS.md
src/main/java/org/folio/entitlement/service/flow/ApplicationFlowService.java
src/main/java/org/folio/entitlement/service/flow/FlowService.java
src/main/java/org/folio/entitlement/service/flow/FlowStageService.java
src/main/java/org/folio/entitlement/service/flow/FlowCompletionService.java
src/main/java/org/folio/entitlement/service/flow/stage/DatabaseLoggingStage.java
src/main/java/org/folio/entitlement/service/flow/stage/AbstractFlowFinalizer.java
src/main/java/org/folio/entitlement/service/flow/stage/FlowFinalizerStageAwareStatusProvider.java
... (Repositories, entities, Liquibase migrations, and flow unit/integration test suites)
```

---

## Technical Solution Summary

1. **State Machine Finalization in Non-Async Mode:**
   - In non-async flow execution, stage failures, timeout conditions, or exceptions during module enablement were causing the overall flow to hang in `IN_PROGRESS` without executing finalizers.
   - Refactored `FlowCompletionService` and `FlowFinalizerStageAwareStatusProvider` to reliably evaluate whether all active stages in the flow have concluded.
   - When running in synchronous mode, errors encountered during module HTTP calls (such as long-running tenant installs or timeout thresholds) immediately transition the parent `ApplicationFlow` and root `Flow` entities to `FAILED`.
2. **Transaction & Context Management:**
   - Ensured `DatabaseLoggingStage` captures stage failures and persists failure status before propagation, avoiding lost error context.
   - Fixed lifecycle handling in `AbstractFlowFinalizer` so that final status updates and post-completion synchronization hooks execute within safe transaction boundaries.
3. **Database Changes:**
   - Added appropriate timestamps and status indexes via Liquibase to track flow completion states accurately.
4. **Comprehensive Test Suite:**
   - Added extensive unit and integration tests covering flow timeouts, failing stages in non-async mode, and status assertions for `Flow` and `ApplicationFlow`.
5. Updated `NEWS.md`.

---

## Grader Evaluation Rubric

- **Terminal State Guarantee:** In synchronous mode (`async: false`), verify that a failed stage transitions both the `ApplicationFlow` and `Flow` entities to `FAILED`.
- **Error Preservation:** Verify error messages and stack traces from the failing stage are captured on the entity.
- **No Hanging In-Progress States:** Check that flow finalizers always execute on completion or failure.
- **Test Execution:** Run `mvn test` in `mgr-tenant-entitlements`.
