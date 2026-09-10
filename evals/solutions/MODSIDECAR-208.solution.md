# Solution Reference: MODSIDECAR-208

- **Key:** MODSIDECAR-208
- **Title:** Export of ~500K Instances with Custom profile stuck in progress in Central tenant on ECS Trillium Sprint testing environment
- **Repository:** `folio-org/folio-module-sidecar`
- **Complexity:** Low (0.5 Story Points)

---

## Ground Truth PR

- **PR:** [#386](https://github.com/folio-org/folio-module-sidecar/pull/386)
- **Status:** Merged into `master`
- **Diff:** 3 files changed (+3 additions, 0 deletions)

---

## Changed Files Inventory

```
NEWS.md
README.md
src/main/resources/application.properties
```

---

## Technical Solution Summary

1. In `src/main/resources/application.properties`, added:
   ```properties
   quarkus.thread-pool.max-threads=${QUARKUS_THREAD_POOL_MAX_THREADS:8}
   ```
2. In `README.md`, added an entry to the environment variables table:
   - Variable: `QUARKUS_THREAD_POOL_MAX_THREADS`
   - Default: `8`
   - Required: `false`
   - Description: Maximum number of threads in the Quarkus worker thread pool.
3. Updated `NEWS.md` with release notes entry.

---

## Grader Evaluation Rubric

- **Property Check:** Verify `quarkus.thread-pool.max-threads` is configured in `application.properties` with fallback value `8` and environment variable expression `${QUARKUS_THREAD_POOL_MAX_THREADS:8}`.
- **Documentation Check:** Verify `README.md` documents `QUARKUS_THREAD_POOL_MAX_THREADS`.
- **Build Verification:** Run `mvn test` in `folio-module-sidecar` to verify configuration syntax is valid.
