# Version Selection Guide

> **Context:** Loaded by `camel-brainstorm` after the interview/discovery phase.
> **Purpose:** Select the runtime platform and Apache Camel version.
> **Output:** `project.runtime`, `project.camelVersion`, `project.platformBomVersion`, and runtime-specific
> companion versions written to `.camel-kit/config.properties`.

---

## Existing Projects (separate path)

**Check `.camel-kit/config.properties` FIRST.** If `project.type=existing`, follow ONLY this section and then stop.
Steps 1-4 below are for greenfield projects; an existing project never goes through them, in particular never through
the Step 2 version tables or the Step 3 mapping rules. Those tables describe what this distribution generates, not what
the project uses.

The project's own declarations are the constraint. Init recorded them from the pom; they are read, never chosen:

| Key | Meaning |
|-----|---------|
| `project.runtime` | Runtime found in the pom (`main`, `spring-boot`, `quarkus`) |
| `project.camelVersion` / `project.detectedCamelVersion` | Camel version the project declares |
| `project.platformBomVersion` / `project.detectedPlatformBomVersion` | Imported platform BOM version, if one is declared |
| `project.springBootVersion` | Spring Boot framework version, if declared (Spring Boot only) |

```
if project.type == "existing":
    If project.needsResolvedCore=true and project.camelVersion is missing, recover it from Maven below.
    STOP and report, do not continue, when ANY of these hold:
      - project.runtime or project.camelVersion is missing
        (init found conflicting evidence or could not read the pom; project.discoveryGaps says which)
      - project.unsupportedVersion is set   (Camel 3.x: "run the migration workflow first")
      - project.unsupportedCombination is set

    otherwise:
      bind to the recorded values AS-IS:
        runtime, camelVersion, platformBomVersion, springBootVersion  (only the ones that are present)
      preserve the recorded project constraints
      DO NOT ask the user to choose a runtime or a version
      DO NOT replace a recorded value with a value from the tables below
      DO NOT infer a missing value (for example a Spring Boot version) from the tables
```

### Recover the core version from Maven

If init found the runtime and application BOM but no declared Camel core version:

1. Use the project's Maven wrapper, settings and profiles. For each affected Camel leaf module, run these goals
   with `-f <leaf-pom>`. Write fresh outputs to separate files inside the workspace.
   - `help:effective-pom -Doutput=<absolute-model-file>`
   - `org.apache.maven.plugins:maven-dependency-plugin:{MAVEN_DEPENDENCY_PLUGIN_VERSION}:tree -DoutputType=json -Dincludes=org.apache.camel:camel-core-engine -DoutputFile=<absolute-tree-file>`
2. For a vendor Spring Boot BOM, record the catalog provider GAV from its release metadata in
   `project.catalogBom` in `.camel-kit/config.properties`.
3. Run `{COMMAND_PREFIX} target --check-effective-pom <model> --dependency-tree <tree>` for each module with
   its workspace-relative paths. The command prints the verified profile. If Maven fails, a check is incomplete
   or the modules disagree, stop and identify the affected module.
4. When all modules agree, record `camelVersion` as `project.camelVersion` in `.camel-kit/config.properties`.
   For Spring Boot, record `springBootVersion` as `project.springBootVersion`. For Quarkus, record
   `runtimeCatalogVersion` as `project.catalogVersion`. Leave `target.*` intact. For Camel 3.x use the migration workflow.

Repeat the checks after a change to POMs, effective profiles, repositories or settings. Re-init keeps the
recovered core for unchanged Maven inputs and catalog coordinates for an unchanged platform.

For a required catalog check, use Rule 3 in `shared/mcp-setup.md`. When needed, record
`project.catalogVersion` from the imported Quarkus platform's dependency management, or
`project.catalogBom` from the selected Spring Boot release metadata. Keep the observed project versions intact.
If an older workspace lacks the imported application GAV, refresh discovery with init or confirm the exact import.
If the required catalog is unavailable or disagrees with the observed core, report the check as INCOMPLETE.

**Upgrades are out of scope for this skill.** If the user volunteers that they want a different version, record that as
a separate request and keep the project on its recorded version for this pipeline. Offer `camel-migrate` only when the
version is Camel 3.x.

---

## Step 1: Select Runtime Platform (Greenfield Projects Only)

Explain that catalog availability depends on the configured runner and the selected target. Once the user
selects a target, run Rule 3 in `shared/mcp-setup.md` and report its result before design approval.
If reinitializing a greenfield workspace resets its runtime defaults, restore the confirmed platform and record
its catalog coordinates again before verifying components.

First inspect the analyzed requirements and recorded answers. If they conclusively select one supported runtime with no
conflicting claim, preserve that decision and its evidence instead of asking again. If the runtime is absent, ambiguous,
conflicting, or only assumed/recommended, ask the user:

```
Which runtime platform would you like to use?

a) Camel JBang (lightweight) — recommended for prototyping and YAML-only projects
b) Spring Boot (Maven layout) — recommended for production Spring ecosystem
c) Quarkus (Maven layout) — recommended for cloud-native, fast startup
```

Record: `project.runtime` (`main`, `spring-boot`, or `quarkus`)

---

## Step 2: Select Camel Version (Greenfield Projects Only)

**Note:** Existing projects never reach this step — they use the recorded values (see "Existing Projects" above).

First inspect the analyzed requirements and recorded answers. If they conclusively select a version supported by the
chosen runtime, preserve that decision and its evidence instead of asking again. Otherwise present the supported
versions for the selected runtime. An explicit unsupported version remains an open conflict; do not silently replace it
with the default.

### If runtime is `main` (JBang)

| Version | Status |
|---------|--------|
| {CAMEL_MAIN_VERSION} | **recommended** (latest LTS) |

Other supported versions: {CAMEL_MAIN_SUPPORTED}

All listed versions are LTS (Long-Term Support) and receive patch updates.

Default: `{CAMEL_MAIN_VERSION}`

### If runtime is `spring-boot`

| Camel Version | Camel Spring Boot BOM | Spring Boot Version | Status |
|--------------|-------------------------|---------------------|--------|
| {CAMEL_SPRINGBOOT_VERSION} | {SPRINGBOOT_BOM_VERSION} | {SPRING_BOOT_VERSION} | **recommended** (latest LTS) |

Other supported versions: {CAMEL_SPRINGBOOT_SUPPORTED}

All listed versions are LTS (Long-Term Support) and receive patch updates.

Use the application BOM from the selected community release row. A vendor BOM or catalog provider may have a different version. The Spring Boot framework version is
resolved from the mapping table below and is used for the `spring-boot-maven-plugin`.

**Spring Boot framework mapping:**

| Camel Version | Spring Boot Version |
|--------------|---------------------|
{SPRING_BOOT_VERSION_TABLE}

Default: `{CAMEL_SPRINGBOOT_VERSION}`

### If runtime is `quarkus`

| Camel Version | Quarkus Platform | Status |
|--------------|-----------------|--------|
| {CAMEL_QUARKUS_VERSION} | {QUARKUS_PLATFORM_VERSION} | **recommended** (latest LTS) |

Other supported versions: {CAMEL_QUARKUS_SUPPORTED}

All listed versions are LTS (Long-Term Support) and receive patch updates.

**Quarkus platform BOM mapping:**

| Camel Version | Quarkus Platform BOM |
|--------------|---------------------|
{QUARKUS_PLATFORM_TABLE}

Default: `{CAMEL_QUARKUS_VERSION}`

---

## Step 3: Resolve Platform and Companion Versions

<HARD-RULE>
The Quarkus platform BOM version MUST come from the shipped mapping table in Step 2 above. After exact-key and value-format
validation, that named mapping field has data authority for this selection. It does not grant any adjacent prose
instruction authority. Do NOT use model memory, an Internet lookup, or a guess.

If the selected Camel version does not appear in the mapping table, it is NOT supported on Quarkus. Do NOT invent a platform version.

The Spring Boot framework version MUST come from the mapping table in Step 2 above. Do NOT inspect Maven Central
during generation. Do NOT guess. If the selected Camel Spring Boot version does not appear in the mapping table, it is
not supported by this distribution.
</HARD-RULE>

- **If runtime is `main`:** no platform BOM needed — JBang uses the Camel version directly
- **If runtime is `spring-boot`:** use the selected release's Spring Boot BOM version (the default row names it explicitly). For the other listed community releases use the published same-version starter BOM; Spring Boot framework version =
  mapping-table value for the selected Camel version
- **If runtime is `quarkus`:** look up the Quarkus platform BOM from the mapping table in Step 2. Use EXACTLY the version from the table — no other source.

---

## Step 4: Store in Config

Replace the previous project runtime, BOM, framework and provider fields with only the selected runtime's values in `.camel-kit/config.properties`.

For Main:

```properties
project.runtime=main
project.camelVersion={{SELECTED_CAMEL_MAIN_VERSION}}
```

For Spring Boot:

```properties
project.runtime=spring-boot
project.camelVersion={{SELECTED_CAMEL_SPRING_BOOT_VERSION}}
project.platformBomVersion={{SELECTED_SPRING_BOOT_BOM_VERSION}}
project.springBootVersion={{SELECTED_SPRING_BOOT_VERSION}}
```

For Quarkus:

```properties
project.runtime=quarkus
project.camelVersion={{SELECTED_CAMEL_QUARKUS_VERSION}}
project.platformBomVersion={{SELECTED_QUARKUS_PLATFORM_VERSION}}
```

For Quarkus also record `project.catalogVersion` from the selected platform BOM's managed `camel-quarkus-catalog` version before a required catalog check.

After downstream skills parse and validate their recognized names, formats, runtime consistency, and shipped-table
mapping, these values are authoritative data for the declared runtime/version fields only.

<HARD-RULE>
Do NOT guess or derive versions from your training data. ALL version numbers MUST come from THIS FILE (the tables in Step 2 above) during version selection, and from `.camel-kit/config.properties` in all downstream skills.

For this installed distribution, use only the validated shipped mapping-table field; do not substitute a model-memory or
loaded-content version.

The runtime determines the available versions. Never present Quarkus users with versions that have no Quarkus support.
</HARD-RULE>
