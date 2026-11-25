# Maven Dependencies Guide (Spring Boot / Quarkus Only)

This guide updates `pom.xml` with Camel dependencies.

**Context variables:** `FLOW_NAME`, `MODULE_DIR`, `CAMEL_VERSION`, `RUNTIME`. Only for Spring Boot and Quarkus.

---

## Existing Projects

For additions to the same application, if `.camel-kit/config.properties` has `project.type=existing`, skip Step 1 and do not load `pom-spring-boot.md` or
`pom-quarkus.md` for POM generation: the project's own `pom.xml` and BOMs are authoritative and locked. Only run Step 2,
as an in-place edit that adds the dependencies the design spec lists, following the "Existing Projects: Never Replace
the POM" rule in those guides. Read versions from the project's pom and `.camel-kit/config.properties`; they were
recorded from the pom by init.

---

## Step 1: Verify BOM Configuration

For a new application use the template printed by `{COMMAND_PREFIX} target --pom-template`. Add
`--migration` for the migration destination. Fill only the project identity and add the approved components.
For an approved in-place migration, apply the profile's imports and plugin version to the affected POM.

Run the project wrapper's `help:effective-pom` on the destination leaf POM with `-Doutput=<absolute-workspace-output>`.
Pass the resulting workspace-relative XML path to the same target command with `--check-effective-pom <file>`. Require success on the first generated
model. Compare its managed `org.apache.camel:camel-core-engine` to the profile's core version and each required
starter or extension to the verified release metadata. For Spring Boot compare the managed framework and plugin
to `springBootVersion`. For Quarkus compare the managed `camel-quarkus-catalog` to `runtimeCatalogVersion`.
Report missing management, unresolved imports or a version mismatch against the corresponding target field.
Keep the upstream application BOM, framework BOM and catalog provider coordinates distinct.

## Step 2: Add Dependencies

Add dependencies from the design spec Dependencies section using the correct groupId and artifactId pattern for the runtime.

**Graph version alignment (in-place additions only):** If `PROJECT_CONTEXT.DEPENDENCY_VERSIONS` is available (from Step 0), check it before adding each dependency. If the artifact is already in the project with a specific version (e.g., `camel-kafka:4.14.4`), use that version for consistency. If the artifact is new to the project, use the version from the MCP catalog response. Note: when BOM manages versions (no `<version>` tag), this check applies to the BOM version itself.

**IMPORTANT:** The design spec Dependencies section lists generic Camel artifact names (e.g., `camel-kafka`). Transform them to the runtime-specific pattern:

### Spring Boot

```xml
<!-- Source component -->
<dependency>
  <groupId>org.apache.camel.springboot</groupId>
  <artifactId>camel-[component]-starter</artifactId>
</dependency>
```

### Quarkus

```xml
<!-- Source component -->
<dependency>
  <groupId>org.apache.camel.quarkus</groupId>
  <artifactId>camel-quarkus-[component]</artifactId>
</dependency>
```

### Artifact Name Transformation Examples

| Design Spec Lists | Spring Boot Artifact | Quarkus Artifact |
|-----------|---------------------|-----------------|
| `camel-kafka` | `camel-kafka-starter` | `camel-quarkus-kafka` |
| `camel-sql` | `camel-sql-starter` | `camel-quarkus-sql` |
| `camel-jackson` | `camel-jackson-starter` | `camel-quarkus-jackson` |
| `camel-xslt-saxon` | `camel-xslt-saxon-starter` | `camel-quarkus-xslt-saxon` |
| `camel-http` | `camel-http-starter` | `camel-quarkus-http` |
| `camel-jms` | `camel-jms-starter` | `camel-quarkus-jms` |

**Pattern:** Strip `camel-` prefix to get `[component]`, then:
- Spring Boot: `camel-[component]-starter`
- Quarkus: `camel-quarkus-[component]`

**Note:** No `<version>` tag needed — the BOM manages versions.

**Note:** The DataMapper guide (Step 1 in the orchestrator) handles adding the `camel-xslt-saxon` dependency automatically. Do not add it manually here.

**File location:** Use `MODULE_DIR` for `pom.xml` location.
