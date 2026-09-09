# POST /reinstall endpoint: Increase limit

- **Key:** MGRENTITLE-172
- **Repository:** mgr-tenant-entitlements
- **Issue Type:** Story
- **Complexity:** Low

---

## Purpose / Overview

As a FOLIO system operator, I want the Manager Tenant Entitlements (MTE) `/reinstall` endpoint to accept up to 100 applications in a single request, so that I can reinstall all applications in a large FOLIO installation without hitting a validation error.

The `POST /reinstall/applications` endpoint currently rejects requests containing more than 25 application descriptors. This limit is hardcoded in the `appReinstallRequestBody.json` JSON Schema (`"maxItems": 25`) and surfaces at runtime as a 400 validation error:

```json
{
  "errors": [
    {
      "message": "size must be between 1 and 25",
      "type": "MethodArgumentNotValidException",
      "code": "validation_error",
      "parameters": [
        {
          "key": "applications",
          "value": "[app-platform-minimal-2.1.0-SNAPSHOT.100200000007531, ...35 apps total]"
        }
      ]
    }
  ],
  "total_records": 1
}
```

Upcoming FOLIO releases ship 40+ applications. Environments with 35+ applications cannot successfully reinstall without this limit being raised. Raising the `appReinstallRequestBody` limit brings the reinstall path in line with the expected scale of production deployments and provides headroom for future growth.

Additionally, verify and ensure consistency across schemas: in `moduleReinstallRequestBody.json`, description strings currently refer to "application ids" instead of "module ids".

---

## Requirements / Scope

### Functional Requirements

1. **Increased limit:** The `POST /reinstall/applications` endpoint must accept a request body containing between 1 and 100 application IDs (inclusive).
2. **Schema update:** The `"maxItems"` constraint in `appReinstallRequestBody.json` must be updated from `25` to `100` (or removed if unbounded, aligning with unrestricted array handling).
3. **Upper boundary accepted:** A request body with 100 application IDs must pass validation and be processed normally.
4. **Empty list rejected:** A request body with 0 application IDs must be rejected with a 400 validation error (`minItems: 1` remains in effect).
5. **Schema consistency:** In `moduleReinstallRequestBody.json`, fix misleading descriptions where "application ids" was copied instead of "module ids".
6. **Code generation:** The project must build cleanly with `mvn clean install` and regenerated OpenAPI DTO classes must compile without errors.

### Out of Scope

- Changes to the underlying reinstall execution logic in `ReinstallService` or `ReinstallController`.

---

## Acceptance Criteria

- **AC1:** A `POST /reinstall/applications` request with more than 25 applications (e.g. 35–40 applications) is accepted and does not fail with validation error.
- **AC2:** A `POST /reinstall/applications` request with an empty `applications` array is rejected with HTTP 400 (`minItems` violation).
- **AC3:** The schema descriptions in `moduleReinstallRequestBody.json` accurately refer to module IDs.
- **AC4:** The project builds cleanly with `mvn clean install` and all unit/integration tests pass.
