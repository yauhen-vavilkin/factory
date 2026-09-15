# Make sidecar web client timeouts configurable through environment variables

- **Key:** MODSIDECAR-207
- **Repository:** folio-module-sidecar
- **Issue Type:** Bug
- **Complexity:** Low
- **Provenance:** Realistically derived for Developer Flow validation from the open FOLIO Jira issue
  MODSIDECAR-207 ("WebClientConfiguration idle timeout settings not configurable via
  QUARKUS_REST_CLIENT_READ_TIMEOUT env var", status In Refinement, snapshot 2026-09-15).
  Not part of the 8-task benchmark; no ground-truth solution exists. The functional scope and the
  acceptance criteria below are derived from the issue text; the configuration contract is left
  open as a declared decision (`evals/decisions/MODSIDECAR-207.json`).

---

## Purpose / Overview

A FOLIO operator uploading a 27 MB file to data import sees the upstream request closed after
60 seconds. Changing Kong timeouts and the sidecar variables `QUARKUS_REST_CLIENT_READ_TIMEOUT`,
`QUARKUS_REST_CLIENT_CONNECT_TIMEOUT`, `QUARKUS_REST_CLIENT_SEND_TIMEOUT` and `REQUEST_TIMEOUT` had
no effect. The sidecar logs show the egress web client is created with
`connectTimeout=60000, keepAliveTimeout=60, idleTimeout=0, readIdleTimeout=0, writeIdleTimeout=0`.
The reporter notes that `WebClientConfiguration` builds Vert.x web clients, which are not the Quarkus
REST client, so the `QUARKUS_REST_CLIENT_*` variables do not apply.

The sidecar already reads these values from `web-client.<client>.timeout.*` configuration properties,
but no environment variables are defined or documented for them, so operators cannot tune them.

---

## Requirements / Scope

1. Operators can configure the connect, keep-alive, idle, read-idle and write-idle timeouts of the
   sidecar's ingress and egress web clients through environment variables.
2. The configuration contract (variable names and semantics) follows the task decision.
3. Defaults stay unchanged when the variables are not set.
4. README.md documents every new or newly honoured variable.

### Out of Scope

- Kong, gateway or module-side timeout changes.
- Changing the default timeout values.

---

## Acceptance Criteria

- **AC1:** The connect, keep-alive, idle, read-idle and write-idle timeouts of the ingress and egress web clients can be set through environment variables, using the configuration contract chosen in the task decision.
- **AC2:** When none of these variables is set, the web clients keep the current defaults (connect 60000 ms, keep-alive 60 s, idle/read-idle/write-idle 0).
- **AC3:** README.md documents each variable with its default value and meaning.
- **AC4:** Unit tests cover the new configuration, and the project compiles and passes `mvn test`.
