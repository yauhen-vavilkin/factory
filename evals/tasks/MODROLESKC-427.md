# Add a batch effective capability-set lookup endpoint

- **Key:** MODROLESKC-427
- **Repository:** mod-roles-keycloak
- **Issue Type:** Story
- **Complexity:** Medium

---

## Purpose / Overview

As Notification Center (and other consuming services), I want to query the effective capability sets for multiple users in a single HTTP request so that notification conditions and access checks can be validated without making multiple role, user, and capability lookup calls per user.

Notification Center needs this lookup for nightly validation of up to 10,000 users, as well as single-user validation when notification conditions are created or edited. The endpoint must return effective capability-set assignments derived from both direct user assignments and role inheritance while remaining performant and bounded for batch use.

---

## Requirements / Scope

### Functional Requirements

1. **New REST Endpoint:**
   - Expose `POST /users/capability-sets/query` in `mod-roles-keycloak`.
   - Protected by endpoint capability following module authorization conventions.
2. **Request Schema:**
   - `userIds` (array of UUIDs, required, minItems: 1, maxItems: 500).
   - `capabilitySetNames` (array of strings, optional whitelist filter).
3. **Validation & Limits:**
   - If `userIds` is missing or empty, return `400 Bad Request`.
   - If `userIds` exceeds 500 items, return `413 Payload Too Large`.
4. **Resolution Logic:**
   - For each user ID, find all capability sets assigned directly to the user (via `user_capability_set`).
   - Find all capability sets assigned to any role assigned to the user (via `user_role` -> `role_capability_set`).
   - Union and deduplicate the set names for each user.
   - If `capabilitySetNames` filter is supplied, filter results to only names matching the whitelist. Unknown whitelist names should produce no matches and must not fail the request.
   - If `capabilitySetNames` is omitted, return all effective capability set names for the user.
5. **Response Schema:**
   - Return `{ "userCapabilitySets": [ { "userId": "<UUID>", "capabilitySetNames": ["..."] } ] }`.
   - Every requested user must be represented in the response. If a user has no matching capability sets, return an empty `capabilitySetNames` array (`[]`).
   - Do NOT expose capability IDs, application IDs, raw permissions, or assignment provenance.
6. **Tenant Isolation:**
   - All queries must be strictly scoped to the tenant header (`x-okapi-tenant`).
7. **Performance & Query Design:**
   - Use set-oriented database queries (e.g. leveraging SQL `UNION` with appropriate indexes) to avoid individual queries per user.
8. **Documentation & Tests:**
   - Update OpenAPI YAML specification with schemas and response codes (200, 400, 413, 500).
   - Add comprehensive unit tests and integration tests covering positive batches, empty results, whitelist filtering, duplicates, and boundary limits.

---

## Acceptance Criteria

- **AC1:** `POST /users/capability-sets/query` with valid `userIds` returns `200 OK` with effective capability set names.
- **AC2:** Both directly assigned capability sets and role-inherited capability sets are included.
- **AC3:** Duplicate assignments (set assigned both directly and through roles) are deduplicated in the output.
- **AC4:** Supplying `capabilitySetNames` limits the output to only whitelisted names.
- **AC5:** A user with no capability sets receives an empty array `[]`.
- **AC6:** Requests exceeding 500 `userIds` receive `413 Payload Too Large`.
- **AC7:** Requests with empty or missing `userIds` receive `400 Bad Request`.
- **AC8:** The project compiles cleanly and all tests pass with `mvn clean verify`.
