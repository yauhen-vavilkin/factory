# Solution Reference: MODROLESKC-424

- **Key:** MODROLESKC-424
- **Title:** [TLS ECS Trillium BF] Check out fails with 403 Unauthorized error for ECS request item
- **Repository:** `folio-org/mod-roles-keycloak`
- **Complexity:** Medium (3 Story Points)

---

## Ground Truth PR

- **PR:** [#383](https://github.com/folio-org/mod-roles-keycloak/pull/383)
- **Status:** Merged into `master`
- **Diff:** 8 files changed (+99 additions, -61 deletions)

---

## Changed Files Inventory

```
.gitignore
NEWS.md
src/main/java/org/folio/roles/integration/kafka/CapabilityEventProcessor.java
src/main/java/org/folio/roles/mapper/entity/PermissionEntityMapper.java
src/main/java/org/folio/roles/service/permission/FolioPermissionService.java
src/test/java/org/folio/roles/integration/kafka/CapabilityEventProcessorTest.java
src/test/java/org/folio/roles/it/NestedCapabilitySetIT.java
src/test/java/org/folio/roles/service/permission/FolioPermissionServiceTest.java
```

---

## Technical Solution Summary

1. In `FolioPermissionService.java`:
   - Changed `expandPermissionNames(Collection<String> permissionNames)` return type from `List<Permission>` to `List<String>`.
   - Replaced entity mapping logic with a `LinkedHashSet<String>` accumulator.
   - Preserved unresolved permission names: names declared in sub-permission lists are retained in `expandedNames` even if `findByPermissionNameIn` returns no stored `PermissionEntity`.
2. In `CapabilityEventProcessor.java`:
   - Updated `createCapabilitySetDescriptor(...)` to consume the returned `List<String>` directly without redundant stream conversions or mapping through DTOs.
3. In `PermissionEntityMapper.java`:
   - Removed obsolete entity-to-DTO conversion methods for collections.
4. In `FolioPermissionServiceTest.java`:
   - Added unit test `positive_unresolvedSubPermissionNameIsKept()` and `positive_unresolvedNestedSubPermissionNameIsKept()`.
5. In `NestedCapabilitySetIT.java`:
   - Added integration test `handleKafkaCapabilityEvent_positive_parentSetKeepsNestedDummyCapabilityOnReprocessing()`.
6. Updated `NEWS.md`.

---

## Grader Evaluation Rubric

- **Method Signature & Return Type:** Verify `FolioPermissionService.expandPermissionNames` returns `List<String>` of permission names.
- **Unresolved Name Preservation:** Confirm that permission names declared in the hierarchy are preserved even when the repository has no matching entity.
- **Loop Prevention:** Verify loop-breaking / visited set tracking is maintained.
- **Unit Test Execution:** Run `mvn test -Dtest=FolioPermissionServiceTest,CapabilityEventProcessorTest`.
- **Integration Test Execution:** Run `mvn failsafe:integration-test -Dit.test=**/NestedCapabilitySetIT.java`.
