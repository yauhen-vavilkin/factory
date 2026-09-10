# Solution Reference: MGRENTITLE-192

- **Key:** MGRENTITLE-192
- **Title:** Add moduleId to MTE system-user entitlement events
- **Repository:** `folio-org/mgr-tenant-entitlements`
- **Complexity:** Low (1 Story Point)

---

## Ground Truth PR

- **PR:** [#301](https://github.com/folio-org/mgr-tenant-entitlements/pull/301)
- **Status:** Merged into `master`
- **Diff:** 10 files changed (+25 additions, -11 deletions)

---

## Changed Files Inventory

```
NEWS.md
src/main/java/org/folio/entitlement/integration/kafka/model/SystemUserEvent.java
src/main/java/org/folio/entitlement/utils/SystemUserEventProvider.java
src/test/java/org/folio/entitlement/integration/kafka/SystemUserModuleEventPublisherTest.java
src/test/java/org/folio/entitlement/utils/SystemUserEventProviderTest.java
src/test/resources/json/events/folio-app6/folio-module2/system-user-update.json
src/test/resources/json/events/folio-app6/folio-module2/system-user.json
src/test/resources/json/events/folio-app6/folio-module3/system-user-deprecated.json
src/test/resources/json/events/folio-app6/folio-module3/system-user.json
src/test/resources/json/events/folio-app6/folio-module4/system-user.json
```

---

## Technical Solution Summary

1. In `SystemUserEvent.java`:
   - Added field: `private String moduleId;`
2. In `SystemUserEventProvider.java`:
   - Updated `getSystemUserEvent(...)` to pass `moduleDescriptor.getId()` into `SystemUserEvent.of(...)`.
3. In `SystemUserModuleEventPublisherTest.java` and `SystemUserEventProviderTest.java`:
   - Updated unit tests to assert `moduleId` presence and value.
4. In test JSON event fixtures:
   - Added `"moduleId": "<module-id-with-version>"` across test resource files.
5. Updated `NEWS.md`.

---

## Grader Evaluation Rubric

- **Model Check:** Verify `SystemUserEvent` contains `moduleId` string field.
- **Provider Check:** Verify `SystemUserEventProvider` populates `moduleId` using `moduleDescriptor.getId()`.
- **Test Fixture Check:** Verify test JSON event resources are updated with `moduleId`.
- **Test Execution:** Run `mvn test -Dtest=SystemUserEventProviderTest,SystemUserModuleEventPublisherTest` to verify tests pass.
