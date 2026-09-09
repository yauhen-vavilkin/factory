# Prevent thread exhaustion during bulk export by setting default Quarkus worker thread pool size

- **Key:** MODSIDECAR-208
- **Repository:** folio-module-sidecar
- **Issue Type:** Bug
- **Complexity:** Low

---

## Purpose / Overview

Export of a large number of Instances with Custom profiles in a Central tenant on ECS environments (e.g. ~500K–1.2M Instances) gets stuck in progress (~90%) and causes HTTP 500 errors.

Investigation into sidecar logs during heavy concurrent export operations identified:
```
HttpServerErrorException$InternalServerError: 500 Internal Server Error:
Failed to proxy request because of response error: Connection was closed
...
Failed to parse JWT token: SRJWT07000: Failed to verify a token
...
[RequestForwardingService] Failed to proxy request: Connection was closed
```

Under heavy concurrent load, the default Quarkus worker thread pool can be exhausted, resulting in timeouts, closed connections, and hung asynchronous exports. 

To prevent thread exhaustion and allow operators to tune worker concurrency according to their workload, the default worker thread pool size must be explicitly set to 8 and made configurable via an environment variable `QUARKUS_THREAD_POOL_MAX_THREADS`.

---

## Requirements / Scope

### Functional Requirements

1. **Quarkus Configuration:** In `src/main/resources/application.properties`, configure `quarkus.thread-pool.max-threads` with a default value of `8`.
2. **Environment Variable Override:** The property must be configurable via the environment variable `QUARKUS_THREAD_POOL_MAX_THREADS`, defaulting to `8` if the variable is unset (`${QUARKUS_THREAD_POOL_MAX_THREADS:8}`).
3. **Documentation:** Update `README.md` to document the new `QUARKUS_THREAD_POOL_MAX_THREADS` environment variable in the table of supported configuration options.

---

## Acceptance Criteria

- **AC1:** `quarkus.thread-pool.max-threads` is set in `application.properties` and defaults to `8`.
- **AC2:** Setting `QUARKUS_THREAD_POOL_MAX_THREADS` overrides the worker pool thread limit.
- **AC3:** `README.md` documents `QUARKUS_THREAD_POOL_MAX_THREADS` with default value, description, and required flag.
- **AC4:** The project compiles and passes all checks with `mvn test`.
