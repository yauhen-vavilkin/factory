# Fix lost nested capabilities during event reprocessing

- **Key:** MODROLESKC-424
- **Repository:** mod-roles-keycloak
- **Issue Type:** Bug
- **Complexity:** Medium

---

## Purpose / Overview

In multi-tenant consortium environments (e.g. Trillium ECS Bugfest), users assigned with specific capability sets (such as `UI-Checkout - manage`) encounter unexpected HTTP 403 Forbidden errors when executing workflows like check-out:

```json
{
  "errors": [
    {
      "type": "ForbiddenException",
      "code": "authorization_error",
      "message": "Access Denied"
    }
  ],
  "total_records": 1
}
```

Investigation revealed that when capability set Kafka events are reprocessed (for example during application re-entitlement or module upgrade), nested dummy capabilities inherited from child capability sets are lost from the parent capability set.

In `FolioPermissionService.expandPermissionNames(...)`, when traversing sub-permissions, permission names that do not yet have corresponding stored `PermissionEntity` rows in the database (e.g. permissions provided by another module that has not yet been processed) are currently dropped during entity conversion. Because the declared hierarchy in module descriptors is the source of truth, unresolved permission names must be preserved in the expanded list rather than omitted.

---

## Requirements / Scope

### Functional Requirements

1. **Preserve unresolved permission names in hierarchy traversal:**
   - In `FolioPermissionService.expandPermissionNames(Collection<String> permissionNames)`, refactor the method signature and implementation so it returns `List<String>` of permission names rather than `List<Permission>` entities.
   - When expanding permissions recursively, all reachable sub-permission names declared in the hierarchy must be retained in the returned collection, even if a permission name has no stored database record at the time of query.
   - Avoid infinite loops when traversing circular or mutually-referencing permission sets.
2. **Caller adjustment:**
   - In `CapabilityEventProcessor.createCapabilitySetDescriptor(...)`, update the usage of `folioPermissionService.expandPermissionNames(...)` to work directly with the returned list of permission names.
   - Clean up unused mapper methods in `PermissionEntityMapper` if no longer needed.
3. **Testing:**
   - Update unit tests in `FolioPermissionServiceTest` to verify that unresolved sub-permission names are preserved across nested levels.
   - Add/update an integration test (e.g. in `NestedCapabilitySetIT`) verifying that sending a repeated capability event retains nested dummy capabilities in the parent capability set.

---

## Acceptance Criteria

- **AC1:** Reprocessing a capability set event twice preserves nested sub-permissions in parent capability sets.
- **AC2:** Permission names declared as sub-permissions are not dropped when their owning module permissions have not yet been stored in the database.
- **AC3:** All unit tests and integration tests pass without regression (`mvn test`).
