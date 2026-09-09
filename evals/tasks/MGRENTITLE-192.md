# Add moduleId to MTE system-user entitlement events

- **Key:** MGRENTITLE-192
- **Repository:** mgr-tenant-entitlements
- **Issue Type:** Story
- **Complexity:** Low

---

## Purpose / Overview

As Manager Tenant Entitlements (MTE), I want system-user entitlement events to include the full module ID so downstream services (such as `mod-users-keycloak`) can identify the originating module and correlate entitlement processing acknowledgements.

Currently, `SystemUserEvent` payloads published to Kafka only include `name`, `type`, and `permissions`. Downstream services need the full module ID (including the version, e.g. `folio-module2-2.1.0`) to correlate events with specific module descriptors during installation and upgrade workflows.

---

## Requirements / Scope

### Functional Requirements

1. **Model update:** Add a `moduleId` field (type `String`) to `SystemUserEvent`.
2. **Provider update:** In `SystemUserEventProvider`, populate `moduleId` with the module descriptor ID (which includes the version, e.g. `moduleDescriptor.getId()`).
3. **All event lifecycles supported:** Ensure `moduleId` is populated for all system-user operations:
   - Create
   - Update (both `new` and `old` values)
   - Delete / Deprecation (`old` value)
4. **Backward compatibility:** Preserve existing fields (`name`, `type`, `permissions`) and their semantics.
5. **Testing:** Update unit tests and test JSON event resources to assert that `moduleId` is correctly populated.

---

## Acceptance Criteria

- **AC1: Event contains module ID**
  - Given a module descriptor containing a system user definition.
  - When MTE creates or updates the system user event.
  - Then the published `SystemUserEvent` contains `moduleId` matching `moduleDescriptor.getId()`.

- **AC2: All event operations supported**
  - Given system-user CREATE, UPDATE, or DELETE operations.
  - When events are emitted.
  - Then the corresponding event objects carry the full module ID.

- **AC3: Tests and build**
  - All existing and updated unit tests pass (`mvn test`).
