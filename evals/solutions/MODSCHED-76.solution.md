# Solution Reference: MODSCHED-76

- **Key:** MODSCHED-76
- **Title:** Delay first invocation of SYSTEM timers
- **Repository:** `folio-org/mod-scheduler`
- **Complexity:** Medium (3 Story Points)

---

## Ground Truth PR

- **PR:** [#252](https://github.com/folio-org/mod-scheduler/pull/252)
- **Status:** Merged into `master`
- **Diff:** 6 files changed (+135 additions, -4 deletions)

---

## Changed Files Inventory

```
NEWS.md
README.md
src/main/java/org/folio/scheduler/configuration/properties/SystemTimerConfigurationProperties.java
src/main/java/org/folio/scheduler/service/JobSchedulingService.java
src/main/resources/application.yml
src/test/java/org/folio/scheduler/service/JobSchedulingServiceTest.java
```

---

## Technical Solution Summary

1. Created `SystemTimerConfigurationProperties`:
   - `@ConfigurationProperties("application.timer.system")`
   - Bound property `initialDelay` (type `Duration`, default `Duration.ZERO`).
2. Configured in `application.yml`:
   ```yaml
   application:
     timer:
       system:
         initial-delay: ${SCHEDULER_SYSTEM_TIMER_INITIAL_DELAY:0s}
   ```
3. In `JobSchedulingService.java`:
   - Injected `SystemTimerConfigurationProperties`.
   - In `getForeverRepeatingTrigger(TimerDescriptor)`:
     - Check `getSystemTimerInitialDelay(timerDescriptor, repeatInterval)`.
     - Only apply if `timerDescriptor.getType() == TimerType.SYSTEM`.
     - Skip if `configuredDelay >= repeatInterval`.
     - Apply via `triggerBuilder.startAt(Date.from(Instant.now().plus(initialDelay)))`.
4. Documented `SCHEDULER_SYSTEM_TIMER_INITIAL_DELAY` in `README.md`.
5. Added comprehensive parameterized and unit tests in `JobSchedulingServiceTest.java`.
6. Updated `NEWS.md`.

---

## Grader Evaluation Rubric

- **Property Binding:** Verify `SystemTimerConfigurationProperties` exists and binds `initialDelay` as `Duration`.
- **Conditionality:** Verify initial delay only applies to `TimerType.SYSTEM` simple timers and skips when `initialDelay >= repeatInterval`.
- **Documentation:** Verify `SCHEDULER_SYSTEM_TIMER_INITIAL_DELAY` is documented in `README.md`.
- **Test Execution:** Run `mvn test -Dtest=JobSchedulingServiceTest` in `mod-scheduler` to verify all test cases pass.
