# FOLIO context pack

Orientation for working inside FOLIO (The Future Of Libraries is Open), an open-source
library services platform built as microservices behind an API gateway. You are almost
certainly working in a single `mod-*` back-end repository; read this pack before
touching code.

## What FOLIO modules are

- FOLIO server-side "modules" are defined by behavior, not packaging: an HTTP server
  speaking a REST-styled JSON protocol, plus a `ModuleDescriptor.json` that declares
  the module's id, the interfaces it provides (`provides`), and the interfaces it
  requires (`requires`). Any technology stack is possible; folio-org modules are
  mostly Java (Spring Way, RMB/Vert.x, folio-vertx-lib).
- Naming: back-end modules `mod-*`; front-end modules `ui-*` / `stripes-*`.
- Interface versions are `major.minor` (software adds a patch part). A requirement on
  `3.2` accepts `3.2` or any higher minor of major `3`, and nothing else. Only one
  module providing a given interface can be enabled for a tenant at a time.

## ModuleDescriptor.json

- Location: template at `descriptors/ModuleDescriptor-template.json`, rendered by the
  Maven build into `target/ModuleDescriptor.json` (token replacement; Spring-based
  modules use `@artifactId@-@version@` tokens). Schema:
  `okapi-core/src/main/raml/ModuleDescriptor.json` in the okapi repository.
- Fields a coding agent must know:
  - `id`: `<name>-<semver>` — unique; changing behavior means changing version.
  - `provides[]`: interface entries with `id`, `version`, `handlers[]` of
    `{ "methods": [...], "pathPattern": "...", "permissionsRequired": [...] }` —
    this is the routing/permission contract for the gateway.
  - `requires[]`: interface ids (with versions) this module depends on.
  - `launchDescriptor`: how the module runs (Docker image, `dockerArgs` incl.
    `HostConfig.Memory` in bytes and `%p` port binding, `env[]`). Java modules must
    set `JAVA_OPTIONS` (at least `-XX:MaxRAMPercentage=66.0`); DB-using modules the
    standard `DB_HOST/DB_PORT/DB_USERNAME/DB_PASSWORD/DB_DATABASE` set.
- After editing, validate the template against the Okapi schema (z-schema / ajv) and
  keep `jq '.'` clean. CI publishes the rendered descriptor to the FOLIO Registry.

## Okapi and the X-Okapi headers

- In the Okapi deployment model, Okapi is the API gateway and service registry. It
  validates the auth token, then forwards the request to the module with these
  headers: `X-Okapi-Token` (auth token carrying tenant/user ids and permissions),
  `X-Okapi-Tenant` (tenant id), `X-Okapi-User-Id`, `X-Okapi-Url` (base URL of the
  gateway for follow-up calls), `X-Okapi-Request-Id`, `X-Okapi-Permissions`.
- Okapi always adds `X-Okapi-Url` to requests it proxies. A module that needs to call
  another module must address the base URL from `X-Okapi-Url` and pass the X-Okapi
  headers along (at minimum `X-Okapi-Token`; easiest is to forward all of them).
  At login (no token yet) the client sends `X-Okapi-Tenant` instead.
- Handlers may also be filters (phases `auth`, `pre`, `post`) and have a `type`
  (`request-response`, `headers`, `request-only`, `redirect`, ...); the response
  status code controls the pipeline (2xx continue, 3xx redirect, 4xx/5xx abort).

## Multi-tenancy

- Every request is on behalf of a tenant. Tenants are managed via
  `/_/proxy/tenants`; modules are enabled per tenant; installs may pass tenant
  parameters (`loadReference=true` loads reference data, `loadSample=true` sample
  data).
- A module that needs storage provides the `_tenant` **system interface**
  (`POST /_/tenant`, and in v2.0 also `GET|DELETE /_/tenant/{id}`). Okapi calls it
  whenever the module is enabled for a tenant (or upgraded); the module creates or
  migrates its per-tenant schema there and may load reference/sample data.
- Per-tenant storage conventions: RMB modules define tables in
  `src/main/resources/templates/db_scripts/schema.json` (CQL queries are translated
  to Postgres JSON); Spring-way modules use the `TenantService` and Liquibase support
  from `folio-spring-base`. Data is isolated per tenant even in a shared PostgreSQL.
- Modules are shared across tenants: never store tenant-specific state outside the
  tenant-scoped storage; always derive the tenant from the request context.

## Eureka deployments (Kong, Keycloak, sidecars)

- Current FOLIO releases also ship the "Eureka" deployment model, where the Okapi
  gateway role is replaced by: **Kong** as the main API gateway; **Keycloak** as the
  authentication/authorization entrypoint; manager services
  (`mgr-applications`, `mgr-tenants`, `mgr-tenant-entitlements`) for application
  registration, tenant lifecycle and entitlements; and one `sc-*` sidecar container
  in front of each `mod-*`, providing Keycloak-aware access, multi-tenant request
  handling, discovery-based routing and forwarding to the module.
- What this means for module code: the platform-facing concerns (routing, auth,
  tenant resolution at the edge) live in the gateway/sidecar layer, while the module
  keeps implementing its documented interfaces. Do not assume which gateway fronts
  you — follow the repository's existing filters/base library for how tenant and user
  context are read, and do not invent new gateway couplings.

## Anatomy of a typical mod-* repository

    ├── Dockerfile                  # container image used by the LaunchDescriptor
    ├── Jenkinsfile                 # CI steps (build, api-lint/api-doc workflows)
    ├── descriptors/
    │   ├── ModuleDescriptor-template.json
    │   └── DeploymentDescriptor-template.json
    ├── doc/                        # extra module docs (beyond the top-level README)
    ├── ramls/                      # RAML + JSON schemas + examples (RAML modules)
    │   └── raml-util/              # git submodule of shared raml files
    ├── reference-data/             # data referenced by sample data
    ├── sample-data/                # sample records loadable at tenant init
    └── src/                        # the module code

- API descriptions: RAML modules keep them under `ramls/`; OpenAPI (OAS) modules
  under e.g. `src/main/resources/openapi`. The reference API docs on dev.folio.org
  are generated from these files — they are the contract, not an afterthought.
- Schema conventions: `$ref` values in schemas are relative pathnames; in RAML files
  a `type`'s declared value is the path to the schema relative to the RAML file.
- Build/CI: Maven for Java modules (Java 17 or 21); descriptor token filtering; api
  linting and schema linting run in CI; `NEWS.md` (back-end) / `CHANGELOG.md`
  (front-end) list per-release changes.

## Working rules in folio-org repositories

1. Read the module's README first, then the RAML/OpenAPI files under `ramls/` (or
   the OAS directory) before editing any endpoint, DTO, or descriptor. The API files
   are the source of truth that other modules and the UI compile against.
2. Any change to an endpoint's shape, fields, or semantics is an interface change:
   bump versions (`provides[].version`, module id) following the major.minor rules,
   and update schemas/examples/descriptors in the same change.
3. Keep multi-tenancy invariants: tenant from request context, storage per tenant,
   no cross-tenant leakage; reference/sample data loads only via the tenant
   interface parameters.
4. Follow the existing stack of the repository (Spring Way with folio-spring-base,
   RMB, or folio-vertx-lib) — do not mix idioms.
5. Run the module's own build and tests (`mvn` for Java) and validate descriptor
   edits (`jq`, z-schema/ajv against the Okapi schemas) before declaring done.
