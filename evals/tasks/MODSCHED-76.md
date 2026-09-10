# Delay first invocation of SYSTEM timers

- **Key:** MODSCHED-76
- **Repository:** mod-scheduler
- **Issue Type:** Task
- **Complexity:** Medium

---

## Purpose / Overview

As an operator of a FOLIO tenant, I want Kafka-created `SYSTEM` timers to wait briefly before their first execution, so that tenant and module activation can stabilize before timer HTTP calls are made.

When applications are entitled or modules are enabled, Kafka-created `SYSTEM` timers can be scheduled and triggered immediately while internal routing, entitlement state, or downstream module readiness is still stabilizing. The first timer HTTP call often fails with a transient error (e.g. 404 or 500), and for infrequent timers (such as daily or hourly jobs), the next scheduled execution may be far in the future.

This task introduces a configurable initial delay for eligible Kafka-created `SYSTEM` timers.

---

## Requirements / Scope

### Functional Requirements

1. **Configurable initial delay:** Introduce an application configuration property `application.timer.system.initial-delay` with an environment variable override `SCHEDULER_SYSTEM_TIMER_INITIAL_DELAY` (default `0s`).
2. **Configuration binding:** Create or update a Spring `@ConfigurationProperties` class (e.g. `SystemTimerConfigurationProperties`) binding `application.timer.system.initial-delay` as a `java.time.Duration`.
3. **Trigger scheduling logic:** In `JobSchedulingService`:
   - When scheduling a repeating simple trigger for a `SYSTEM` timer (e.g. `timerDescriptor.getType() == TimerType.SYSTEM`), calculate the initial delay.
   - If a valid non-zero initial delay is configured, set the trigger start time (`triggerBuilder.startAt(...)`) to `Instant.now().plus(initialDelay)`.
4. **Scope restrictions:**
   - Do **NOT** apply the initial delay to `USER` timers (`timerDescriptor.getType() == TimerType.USER`).
   - Do **NOT** apply the initial delay to `cron` timers (only delay-based / simple repeating triggers are supported).
5. **Safety check for short intervals:**
   - If the configured initial delay is greater than or equal to the timer's repeat interval (in milliseconds), skip the initial delay to avoid pushing execution beyond its expected cycle.
6. **Documentation:** Update `README.md` to document the new `SCHEDULER_SYSTEM_TIMER_INITIAL_DELAY` environment variable.
7. **Test coverage:** Add unit tests covering:
   - Positive case: `SYSTEM` simple timer has initial delay applied to start time.
   - Negative case: `USER` timer does not receive initial delay.
   - Negative case: Cron-based `SYSTEM` timer does not receive initial delay.
   - Edge case: Initial delay matching or exceeding repeat interval is skipped.

---

## Acceptance Criteria

- **AC1:** A Kafka-created `SYSTEM` delay-based timer starts after `Instant.now() + initialDelay` when `initial-delay` is configured.
- **AC2:** A `USER` timer or a `cron`-based timer executes immediately without initial delay.
- **AC3:** If `initial-delay >= repeatInterval`, the delay is skipped and logged.
- **AC4:** The project builds cleanly and all tests pass with `mvn clean test`.
