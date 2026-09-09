# Solution Reference: MODSCHED-70

- **Key:** MODSCHED-70
- **Title:** Retry/backoff for transient timer execution failures
- **Repository:** `folio-org/mod-scheduler`
- **Complexity:** High (3 Story Points)

---

## Ground Truth PR

- **PR:** [#255](https://github.com/folio-org/mod-scheduler/pull/255)
- **Status:** Merged into `master`
- **Diff:** 21 files changed (+946 additions, -11 deletions)

---

## Changed Files Inventory

```
NEWS.md
README.md
src/main/java/org/folio/scheduler/configuration/RetryConfiguration.java
src/main/java/org/folio/scheduler/configuration/properties/TimerRetryConfigurationProperties.java
src/main/java/org/folio/scheduler/service/OkapiHttpRequestExecutor.java
src/main/java/org/folio/scheduler/service/TimerExecutionService.java
src/main/resources/application.yml
src/test/java/org/folio/scheduler/service/OkapiHttpRequestExecutorTest.java
src/test/java/org/folio/scheduler/service/TimerExecutionServiceTest.java
... (Additional test fixtures and support classes)
```

---

## Technical Solution Summary

1. **Retry Configuration Properties:**
   - Created `TimerRetryConfigurationProperties` binding `application.timer.retry.*` (max attempts, initial delay, multiplier, max delay).
   - Documented environment variables (`SCHEDULER_TIMER_RETRY_MAX_ATTEMPTS`, `SCHEDULER_TIMER_RETRY_INITIAL_DELAY`, etc.) in `README.md`.
2. **Spring Retry Integration:**
   - Configured `RetryTemplate` or `@Retryable` with `ExponentialBackOffPolicy` and `SimpleRetryPolicy`.
   - Identified retryable exceptions (transient HTTP 5xx, network timeouts/I/O errors, 404/408 if routing pending).
   - Excluded client errors (400, 401, 403) from retry.
3. **Execution Layer Enhancement:**
   - In `OkapiHttpRequestExecutor` / `TimerExecutionService`, executed the timer HTTP request through the retry policy.
   - Added structured logging for each retry attempt, capturing timer ID, tenant ID, attempt index, and delay.
   - Preserved final error handling so failures are reported and logged rather than silently swallowed.
4. **Concurrency Safety:**
   - Applied Quartz `@DisallowConcurrentExecution` to timer job classes to ensure that long-running retry loops do not overlap with subsequent trigger fires of the same timer.
5. **Tests:**
   - Added comprehensive unit tests in `OkapiHttpRequestExecutorTest` and `TimerExecutionServiceTest` testing successful retry, exhausted retry, and non-retryable status codes.
6. Updated `NEWS.md`.

---

## Grader Evaluation Rubric

- **Retry Properties:** Verify properties class exists with configurable backoff and max attempts.
- **Selective Retry:** Confirm 4xx client errors (400, 401, 403) are non-retryable, while transient 5xx / timeouts are retried.
- **Concurrency Control:** Verify overlapping executions for the same job are prevented (e.g. `@DisallowConcurrentExecution`).
- **Structured Logging:** Verify retry attempts log attempt count and timer context.
- **Test Execution:** Run `mvn test` in `mod-scheduler`.
