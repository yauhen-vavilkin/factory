# Solution Reference: MGRENTITLE-172

- **Key:** MGRENTITLE-172
- **Title:** POST /reinstall endpoint: Increase limit
- **Repository:** `folio-org/mgr-tenant-entitlements`
- **Complexity:** Low (1 Story Point)

---

## Ground Truth PR

- **PR:** [#300](https://github.com/folio-org/mgr-tenant-entitlements/pull/300)
- **Status:** Merged into `master`
- **Diff:** 3 files changed (+3 additions, -3 deletions)

---

## Changed Files Inventory

```
NEWS.md
src/main/resources/swagger.api/schemas/appReinstallRequestBody.json
src/main/resources/swagger.api/schemas/moduleReinstallRequestBody.json
```

---

## Technical Solution Summary

1. In `src/main/resources/swagger.api/schemas/appReinstallRequestBody.json`, removed `"maxItems": 25` from the `applications` array property to lift the restriction.
2. In `src/main/resources/swagger.api/schemas/moduleReinstallRequestBody.json`, fixed the description string from `"A collection of application ids to reinstall"` to `"A collection of module ids to reinstall"`, and item description from `"List of application ids"` to `"List of module ids"`.
3. Updated `NEWS.md` with release notes entry.

---

## Grader Evaluation Rubric

- **Schema Check:** Inspect `appReinstallRequestBody.json`. Verify that `"maxItems": 25` is removed or updated to at least `100`.
- **Consistency Check:** Verify `moduleReinstallRequestBody.json` descriptions refer to module IDs.
- **Build Verification:** Run `mvn clean compile` in `mgr-tenant-entitlements` to confirm OpenAPI code generator regenerates DTOs without compilation errors.
- **Test Verification:** Run `mvn test` to verify existing tests pass.
