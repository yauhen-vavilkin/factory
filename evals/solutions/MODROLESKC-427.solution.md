# Solution Reference: MODROLESKC-427

- **Key:** MODROLESKC-427
- **Title:** Add a batch effective capability-set lookup endpoint
- **Repository:** `folio-org/mod-roles-keycloak`
- **Complexity:** Medium (3 Story Points)

---

## Ground Truth PR

- **PR:** [#384](https://github.com/folio-org/mod-roles-keycloak/pull/384)
- **Status:** Merged into `master`
- **Diff:** 15 files changed (+722 additions, -4 deletions)

---

## Changed Files Inventory

```
NEWS.md
src/main/java/org/folio/roles/controller/UserCapabilitySetController.java
src/main/java/org/folio/roles/repository/CapabilitySetRepository.java
src/main/java/org/folio/roles/service/capability/UserCapabilitySetService.java
src/main/resources/swagger.api/mod-roles-keycloak.yaml
src/main/resources/swagger.api/schemas/capability-set/userCapabilitySetsRequest.json
src/main/resources/swagger.api/schemas/capability-set/userCapabilitySetsResponse.json
src/test/java/org/folio/roles/controller/UserCapabilitySetControllerTest.java
src/test/java/org/folio/roles/it/UserCapabilitySetIT.java
src/test/java/org/folio/roles/service/capability/UserCapabilitySetServiceTest.java
... (JSON test fixtures)
```

---

## Technical Solution Summary

1. **OpenAPI Specification:**
   - Defined `POST /users/capability-sets/query` in `mod-roles-keycloak.yaml`.
   - Created `userCapabilitySetsRequest.json` schema (`userIds` array, optional `capabilitySetNames`).
   - Created `userCapabilitySetsResponse.json` schema (`userCapabilitySets` array).
2. **Database Query Optimization:**
   - In `CapabilitySetRepository.java`, added a set-oriented native SQL or JPQL query finding effective capability sets for users via UNION of `user_capability_set` and `user_role` -> `role_capability_set`.
3. **Service Layer:**
   - In `UserCapabilitySetService.java`, implemented `findUserCapabilitySets(UserCapabilitySetsRequest request)`:
     - Enforces max 500 user IDs limit.
     - Maps results per requested `userId`.
     - Defaults missing users to empty `capabilitySetNames` array.
4. **Controller:**
   - In `UserCapabilitySetController.java`, implemented the generated interface endpoint.
5. **Tests:**
   - Unit tests in `UserCapabilitySetControllerTest` and `UserCapabilitySetServiceTest`.
   - Integration tests in `UserCapabilitySetIT` with Testcontainers.
6. Updated `NEWS.md`.

---

## Grader Evaluation Rubric

- **Endpoint Existence:** Verify `POST /users/capability-sets/query` is defined in OpenAPI and implemented in `UserCapabilitySetController`.
- **Batch Boundary:** Verify request with >500 users is rejected (HTTP 413) and empty `userIds` is rejected (HTTP 400).
- **Deduplication:** Verify capability sets assigned through multiple roles/direct paths are deduplicated.
- **Whitelist Filtering:** Verify `capabilitySetNames` filters output when provided.
- **Empty Output for Users Without Sets:** Verify all input `userIds` are in the response with empty array when no matches.
- **Test Execution:** Run `mvn test -Dtest=UserCapabilitySetControllerTest,UserCapabilitySetServiceTest`.
