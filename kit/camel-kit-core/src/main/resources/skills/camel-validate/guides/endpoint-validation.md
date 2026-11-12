# Endpoint & Runtime Validation Guide

> **Context variables provided by master SKILL.md:**
> - `CAMEL_VERSION` — from `.camel-kit/config.properties`
> - `RUNTIME` — project runtime from `.camel-kit/config.properties` (affects route file location)
> - `PLATFORM_BOM` — resolved from `CAMEL_VERSION` + `RUNTIME` via the version mapping table in `skills/shared/mcp-setup.md`
> - `ROUTE_FILES` — exact runtime/module-aware relative route paths from the validation inventory
> - `PROPS_FILE` — exact properties path matching the current route's module
>
> **Version mapping:** When calling MCP catalog tools, translate `CAMEL_VERSION` + `RUNTIME` to the correct `camelVersion` and `platformBom` parameters using the version mapping table in `skills/shared/mcp-setup.md`.

## Stage 2: Endpoint URI Validation (MCP Enhanced)

For every exact path in `ROUTE_FILES`, extract all endpoint URIs and validate them with its matching `PROPS_FILE`.

### 2.1 Extract Endpoints

Parse route YAML and extract all component URIs:

```
Extracting endpoints from {ROUTE_FILE}...

Found endpoints:
  - kafka:{{kafka.topic.input}}
  - sql:{{sql.insert}}
  - http:{{api.endpoint}}
```

### 2.2 Validate URIs with MCP

The pinned `camel_validate_route` schema requires both `uri` and `route`. For each endpoint, pass that actual URI and
the full current route; never send a null, empty, or dummy field. Require the top-level `uri` to echo the submitted URI
and its top-level `errors` to be absent or empty. Ignore the aggregate `valid` field because route-content processing can
overwrite it. When present, `uriValidations` is only supplementary best-effort route-extraction evidence and may omit
endpoint expressions.

**If tool call succeeds:**

```
== ENDPOINT URI VALIDATION (MCP) ==

Validating URIs against Camel {{CAMEL_VERSION}} catalog...

Endpoint 1: kafka:{{kafka.topic.input}}
  MCP Tool: camel_validate_route
  Params: { "uri": "kafka:topic", "route": "[full current route content]", "camelVersion": "{{CAMEL_VERSION}}", "platformBom": "{{PLATFORM_BOM}}", "runtime": "{{RUNTIME}}" }

  MCP catalog/URI result: ✅ VALID
  - Component: kafka exists
  - Path parameter: topic (valid)
  - Suggestions: Consider adding groupId for consumer

Endpoint 2: sql:{{sql.insert}}
  MCP Tool: camel_validate_route
  Params: { "uri": "sql:INSERT INTO orders", "route": "[full current route content]", "camelVersion": "{{CAMEL_VERSION}}", "platformBom": "{{PLATFORM_BOM}}", "runtime": "{{RUNTIME}}" }

  MCP catalog/URI result: ✅ VALID
  - Component: sql exists
  - Endpoint URI and options are valid in the bound catalog; SQL grammar is not proven by this tool
  - Warning: Ensure dataSource bean is configured (Configured means: a verified Forage registration, YAML bean,
    `camel.beans.dataSource` definition, or rung-2 scalar configuration — see `skills/shared/forage.md`.)

Endpoint 3: http://{{api.endpoint}}
  MCP Tool: camel_validate_route
  Params: { "uri": "http://api.example.com", "route": "[full current route content]", "camelVersion": "{{CAMEL_VERSION}}", "platformBom": "{{PLATFORM_BOM}}", "runtime": "{{RUNTIME}}" }

  MCP catalog/URI result: ✅ VALID
  Combined endpoint validation: ❌ FAIL
  - Component: http exists
  - Security: External non-local plain HTTP violates security checklist rule 2
  - Required fix: Use https:// (plain HTTP is allowed only for localhost)
```

**If tool call fails (fallback):**

```
== ENDPOINT URI VALIDATION (Manual) ==

Validating component existence...

✅ kafka - component exists
✅ sql - component exists
❌ http://api.example.com - external non-local plain HTTP is FAIL; localhost HTTP is exempt
```

---

## Stage 3: Runtime Handoff

`/camel-validate` is a static gate: it never starts the routes, runs a build or downloads dependencies, and the Camel
CLI has no dry-run option for `camel run` (no `--check` flag exists). Do not run anything in this stage. Every concern
a runtime start would surface is covered elsewhere — record where:

| Runtime concern | Covered by |
|---|---|
| YAML parsing and DSL structure | Stage 1 (`schema-validation.md`) |
| Component existence and endpoint options | Stage 2 above |
| Property placeholders defined in `PROPS_FILE` | `quality-checks.md` §7.2 Property Completeness |
| Bean references and `camel.beans.*` definitions | `quality-checks.md` §7.3 Bean Definitions |
| Startup, dependency resolution and connection failures | the verification loop of `/camel-execute` (Step 3.5), recorded as the `Verification:` line of `execution-report.md`; `/camel-debug` outside a pipeline |

Show the handoff, quoting the `Verification:` value from `execution-report.md` when the run is pipeline-scoped:

```
== RUNTIME VALIDATION ==

Not run by /camel-validate (static gate; `camel run` has no check mode).
Runtime verification: {PASS | PARTIAL | FAIL | NOT_RUN} per execution-report.md
```

In a project-scoped run there is no execution report: print `Runtime verification: not available — run the routes
through the /camel-execute verification loop or /camel-debug` instead.
