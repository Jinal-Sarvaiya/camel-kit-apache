# POM Structure Guide — Camel on Spring Boot

> **Context:** Loaded when generating or migrating a `pom.xml` for Apache Camel on Spring Boot.
> **Purpose:** Define the exact POM structure. Every element has a reason — do NOT deviate.

---

## Existing Projects: Never Replace the POM

<HARD-RULE>
When adding to an existing application, if `.camel-kit/config.properties` has `project.type=existing`, the project already owns its `pom.xml`. Do NOT copy the
template over it, do NOT replace its placeholders, and do NOT change its parent, BOMs, plugins, Camel version, platform
BOM version or Spring Boot version. Skip "POM Template File" below entirely.

Edit the existing `pom.xml` in place, and only to add the dependencies the approved design spec lists under "Affected
Files". Add each one by hand with the `<groupId>`/`<artifactId>` form this project already uses, and add no `<version>`
unless the project's own BOM does not manage that artifact. A version, when needed, must be the one the project already
uses for the same Camel release train (`project.camelVersion` / `project.platformBomVersion`), never one from a shipped
version table. If the dependency cannot be added without changing a locked version, STOP and report it.
</HARD-RULE>

---

## POM Template File

<HARD-RULE>
Use `{COMMAND_PREFIX} target --pom-template` (add `--migration` for a migration destination). Only
fill in `[PROJECT_GROUP_ID]`, `[PROJECT_ARTIFACT_ID]`, `[PROJECT_VERSION]` and `[PROJECT_NAME]` in its output.
Add dependencies from the approved design. Verify the effective model as described in `maven-dependencies.md`.
</HARD-RULE>

## Reference

This guide is based on the official Apache Camel Spring Boot examples:
`https://github.com/apache/camel-spring-boot-examples`

---

## Rule 1: No Parent POM

<HARD-RULE>
Do NOT use `spring-boot-starter-parent` as a parent POM.

```xml
<!-- WRONG — never do this -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.5</version>
</parent>
```

The selected application BOM manages starters. The template imports it first, then the Camel core
BOM for core modules such as `camel-test-spring-junit5` and the Spring Boot dependencies BOM for
framework starters. These three versions come from the target profile.
</HARD-RULE>

## Rule 2: Spring Boot Maven Plugin with Explicit Version

Since there is no parent POM, the plugin version MUST be explicit:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <version>${spring-boot-version}</version>
            <executions>
                <execution>
                    <goals>
                        <goal>repackage</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

The `repackage` goal creates the executable fat JAR. Without it, `java -jar` won't work.

---

## Rule 3: Jakarta Namespace Only

Camel 4.x and Spring Boot 3.x use Jakarta EE 10. Never use `javax.*` packages:

| Wrong (javax) | Correct (jakarta) |
|------------------|---------------------|
| `javax.jms:javax.jms-api` | `jakarta.jms:jakarta.jms-api` |
| `javax.persistence:javax.persistence-api` | `jakarta.persistence:jakarta.persistence-api` |
| `javax.servlet:javax.servlet-api` | `jakarta.servlet:jakarta.servlet-api` |

In most cases, you don't need to add Jakarta APIs explicitly — the BOM or Spring Boot starters pull them in.

---

## Migration-Specific Notes

When migrating from Fuse/Blueprint/Camel 2.x:

1. **Remove the old parent** — Fuse BOMs, Spring Boot parent, any `org.jboss.fuse` parent
2. **Use the selected repositories** — retain the operator-configured vendor repository when the destination needs it
3. **Replace component dependencies** — `camel-core` -> `camel-spring-boot-starter`, `camel-blueprint` -> remove (not needed on Spring Boot), `camel-jms` -> `camel-jms-starter`
4. **Update Java namespace** — `javax.*` -> `jakarta.*` in all source code AND dependencies
5. **Remove version tags** from dependencies managed by the BOM
