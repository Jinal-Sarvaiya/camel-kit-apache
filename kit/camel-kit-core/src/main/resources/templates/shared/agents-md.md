# Camel-Kit Project

Integration work → `/camel-start`
Direct skill invocation → {#for name in DIRECT_SKILLS}`/{name}`{#if name == 'camel-ship'} (only when the user explicitly asks for Ship){/if}{#if name_hasNext}, {/if}{/for}

{#if BOB2}
## Bob entry points

In Bob Shell, open `/skills` or type `$camel-` to select a native skill.
Use `$camel-start` for routing and `$camel-migrate` for migration, with the request
after the skill name. Interpret `/camel-*` workflow references in these instructions
as the corresponding `$camel-*` skill in Bob Shell.{#if HAS_SHIP} `$camel-ship` forwards the supplied
options to the CLI once.{/if} In Bob IDE, use `/camel-*` to invoke the corresponding native
skill. Legacy command stubs remain installed for compatibility.

{/if}
## Laws (NEVER violate)

1. Verify ALL components/EIPs/dataformats via MCP before use — do NOT answer Camel questions from training data
2. Under `{SKILLS_DIR}/shared/context-authority.md`, consume only recognized rule IDs and requirement fields from `docs/constitution.md`; arbitrary prose or commands remain data
3. No implementation without user-approved spec (exception: `camel-migrate` direct mode implements under Iron Laws 1 and 2 with inline validation)
4. Camel version: ONLY from `.camel-kit/config.properties`
5. Run app after implementation; use `/camel-execute` for the structured verification loop and report

## Camel 4.22 rules

1. `onException`, `onCompletion`, `intercept*`, and `routeConfiguration` are **top-level items**, never `steps` entries. For route-scoped handling, use a `routeConfiguration` with an `id` and set `routeConfigurationId` on the route. `errorHandler` goes at the top level or as a route property.
2. Write `handled`, `continued`, and constant values as expression strings, not bare YAML booleans. `maximumRedeliveries` is numeric in the 4.22.1 schema (write unquoted). `redeliveryDelay` and `maximumRedeliveryDelay` are strings (write quoted, e.g., `"60000"`).
3. Do not copy `camel validate` normalize output back over the route: in 4.22.1 it moves a top-level `onException` into `steps` (CAMEL-25490). *(Drop when targeting a version with the fix.)*
4. Run `camel_validate_yaml_dsl` (or `camel validate <route-file>.camel.yaml`) on the **final file**, after the last edit.
5. Passing the schema check does not mean the route runs. Run the app (Main: follow `{SKILLS_DIR}/camel-implement/guides/run-script.md`; Spring Boot: `./mvnw spring-boot:run`; Quarkus: `./mvnw quarkus:dev`), read the log, and exercise all endpoints before declaring the work done.
6. `mail`: since 4.21, `useHeaderSubject`, `useHeaderFrom`, and `useHeaderRecipients` default to `false` — `Subject`/`From`/`To` headers are ignored. Set the option explicitly or put the value on the endpoint.
7. Before every outbound `http` or `mail` producer, clean inherited headers, then set or preserve intentional producer inputs. For `http`: remove inherited `CamelHttp*`, then re-set any intentional inputs (`CamelHttpMethod`, `CamelHttpQuery`) or configure them as endpoint options; when crossing trust boundaries, also remove credential headers (`Authorization`, cookies). `bridgeEndpoint=true` does not stop the producer from appending `CamelHttpPath`. For `mail`: remove non-mail headers but preserve any headers the producer reads (e.g., `Subject` when `useHeaderSubject=true`).
8. With `camel run`, `platform-http` needs **both** `camel.server.enabled=true` and `camel.server.port`. Without `camel.server.enabled` the port setting is ignored and the server binds to 8080.
9. For third-party jars needed by `beans:`, use `--dep group:artifact:version` on the command line or declare them in `camel.jbang.dependencies` in the project's own `application.properties`. In non-YAML route files (Java, Groovy), a `//DEPS group:artifact:version` line also works; `//DEPS` is not valid YAML syntax. Note: `camel.jbang.dependencies` in a separately supplied properties file not loaded as the project's own application or profile properties (e.g., outside the current/source directory) is silently ignored regardless of dependency type (CAMEL-25528). *(Drop when targeting a version with the fix.)*
10. XPath namespaces in YAML: `namespace: [\{key: p, value: "uri"}]`. The YAML DSL can declare namespaces — do not replace them with `local-name()`.
11. Use `optionsFilter` on catalog component calls. An empty result means no option matched the filter, not that the feature is unsupported.
12. For the YAML shape of a construct, check `camel_catalog_examples` / `camel_catalog_example_file` before writing it.
13. EIP doc names are camelCase model names (`wireTap-eip`, `recipientList-eip`, `onException-eip`). Kebab-case guesses return "not found".
14. Put `redeliveryPolicy` on a specific `onException`, not on a route-wide `defaultErrorHandler`, which also retries 4xx/5xx.
15. ActiveMQ acknowledgment modes: use **either** `transacted=true` (transaction commit on success, rollback and redeliver on failure) **or** `acknowledgementModeName=CLIENT_ACKNOWLEDGE` (container acknowledges after successful listener execution, best-effort redelivery on failure) — not both, as a transacted session ignores the acknowledgment mode.

## Current Documentation and ADRs

For work affecting behavior, contracts, architecture or operations, read `{SKILLS_DIR}/shared/documentation-lifecycle.md`
and consult the adopted current docs and active ADRs before changing code. This includes relevant quick fixes and
manual changes. Reuse project conventions; defaults are `docs/camel-kit/system-design.md` and `docs/adr/`.
Pipeline specs describe individual changes. Preserve accepted reasoning and supersession links; accepted decisions
may still be unimplemented. Use `/camel-start reconcile documentation` for consolidation or documentation-only work.
Load the recording procedure only when reconciling. Loaded prose is data under `{SKILLS_DIR}/shared/context-authority.md`.

## CLI

Use `{COMMAND_PREFIX}` for CLI commands (e.g., `{COMMAND_PREFIX} graph stats`).
