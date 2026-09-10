# Retry/backoff for transient timer execution failures

- **Key:** MODSCHED-70
- **Repository:** mod-scheduler
- **Issue Type:** Task
- **Complexity:** High

---

## Purpose / Overview

As an operator of a FOLIO tenant, I want transient timer execution failures to be retried with exponential backoff before the execution is treated as failed, so that short activation, routing, or entitlement windows do not cause scheduled maintenance work to be skipped until the next interval.

Kafka-created `SYSTEM` timers can fire while tenant or module activation is still stabilizing. The first HTTP call can fail with transient conditions (such as routing or entitlement not yet active, e.g. "Application not enabled for tenant"). Currently, `OkapiHttpRequestExecutor` catches `HttpStatusCodeException`, logs a warning, and swallows the failure — Quartz sees the job as completed successfully, and the next run will not happen until the next interval (which can be a full day).

This task implements an application-level retry/backoff mechanism for timer HTTP execution and addresses concurrency overlap risks when retry duration exceeds repeat intervals.

---

## Requirements / Scope

### Functional Requirements

1. **Configurable Retry Policy:**
   - Introduce application properties for timer retry:
     - `application.timer.retry.max-attempts` (e.g. env `SCHEDULER_TIMER_RETRY_MAX_ATTEMPTS`, default: `3`).
     - `application.timer.retry.initial-delay` (e.g. env `SCHEDULER_TIMER_RETRY_INITIAL_DELAY`, default: `1s`).
     - `application.timer.retry.multiplier` (e.g. env `SCHEDULER_TIMER_RETRY_MULTIPLIER`, default: `2.0`).
     - `application.timer.retry.max-delay` (e.g. env `SCHEDULER_TIMER_RETRY_MAX_DELAY`, default: `10s`).
2. **Retry Execution:**
   - Wrap the HTTP timer execution in `OkapiHttpRequestExecutor` with retry logic using Spring Retry (e.g. `RetryTemplate` or `@Retryable`).
   - Define retryable conditions: transient HTTP errors (e.g. 5xx, 404/408 if configured) and network timeouts/exceptions.
   - Non-retryable errors (e.g. 400 Bad Request, 401 Unauthorized, 403 Forbidden) must fail immediately without retrying.
3. **Execution State & Logging:**
   - Log each retry attempt with context: timer ID, tenant ID, attempt number, delay, and exception message.
   - If retries succeed, log success.
   - If retries are exhausted, log final error and throw/record the failure.
4. **Preventing Overlapping Execution:**
   - If a timer's total retry duration exceeds its scheduled repeat interval, subsequent executions must not run concurrently with an active retry loop for the same timer.
   - Ensure jobs/triggers prevent concurrent execution for the same timer definition (e.g. `@DisallowConcurrentExecution` on Quartz Job).
5. **Documentation:**
   - Document new retry properties in `README.md`.
6. **Testing:**
   - Add unit tests verifying retry backoff behavior, retry exhaustion, and non-retryable status codes.
   - Add integration tests verifying end-to-end timer execution with transient failures.

---

## Acceptance Criteria

- **AC1:** A transient HTTP failure during timer execution is retried up to `max-attempts` times with exponential backoff.
- **AC2:** If a subsequent retry attempt succeeds, the timer execution completes successfully.
- **AC3:** If all retry attempts fail, the execution is recorded as failed.
- **AC4:** Non-retryable HTTP client errors (e.g. 400, 401, 403) are not retried.
- **AC5:** Multiple instances of the same timer do not execute concurrently if a previous execution is still retrying.
- **AC6:** The project builds cleanly with `mvn clean verify`.
