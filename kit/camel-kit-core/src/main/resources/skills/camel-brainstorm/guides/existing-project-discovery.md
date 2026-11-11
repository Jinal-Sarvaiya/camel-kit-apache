# Existing Project Discovery

Use this guide when adding new capabilities to an existing Camel 4 application (`project.type=existing` in config.properties).

## Core Principle

**Discover first, then ask.** The project graph, config, and existing code have already answered many questions. Don't re-ask what you already know.

## Discovery Process

### Step 1: Load Existing Project Metadata

Read `.camel-kit/config.properties` and extract:

- `project.type` — should be `existing`
- `project.detectedCamelVersion` — the Camel version in use
- `project.runtime` — spring-boot, quarkus, karaf, or main
- `project.primaryDsl` — yaml, xml, java, or groovy
- `project.routeCount` — number of existing routes
- `project.hasTests` — whether tests exist
- `project.unsupportedVersion` — flag if Camel 3.x detected
- `project.detectedPlatformBomVersion` — platform BOM version

### Step 2: Load Project Graph

Read `.camel-kit/project-graph.json` to understand:

- **Existing routes** — identify route IDs, source files, components in use
- **Maven dependencies** — what Camel components are already available
- **Java classes** — custom processors, beans, configurations
- **Configuration files** — application.properties, application.yml
- **Test files** — existing test coverage

### Step 3: Validate Compatibility

Before proceeding, check for blockers:

```text
if project.unsupportedVersion == "true":
    STOP: "This project uses {detectedCamelVersion}, which is not supported.
           Camel Kit requires Camel 4.x. Please run /camel-migrate to upgrade first."

if project.unsupportedCombination is set:
    STOP: report its value. Do not convert the DSL or change the runtime to make the project fit.

if project.discoveryGaps is set:
    INSPECT the source for each listed gap, then ask the user only about what is still unclear.

if detectedCamelVersion == null:
    WARN: "Could not detect Camel version from pom.xml.
           Verify the project uses Camel 4.x before continuing."
```

### Step 4: Understand Existing Architecture

Analyze the graph to answer:

1. **What components are already in use?** (Check MAVEN_ARTIFACT nodes)
2. **What are the existing route patterns?** (REST APIs, file watchers, timers, message queues)
3. **What's the error handling strategy?** (Look for errorHandler configuration)
4. **What's the test approach?** (Spring Boot Test, Quarkus Test, CitrusFramework)
5. **Are there custom beans/processors?** (Check JAVA_CLASS nodes)

### Step 5: Socratic Interview (Ask Only Unresolved Questions)

**DO NOT ask** what you already know from discovery:

- ❌ "What Camel version are you using?" — you detected it in config
- ❌ "Do you prefer YAML or Java DSL?" — you know the primaryDsl
- ❌ "What runtime are you on?" — it's in the config
- ❌ "Do you have existing routes?" — you counted them in the graph
- ❌ "Do you have tests?" — project.hasTests tells you

**DO ask** what you cannot discover:

- ✅ "What new capability do you want to add to this integration?"
- ✅ "Which existing routes should the new capability integrate with?"
- ✅ "Should the new routes follow the same error handling pattern as route X?"
- ✅ "Will this new capability reuse any existing beans or processors?"
- ✅ "What's the expected load/throughput for the new capability?"
- ✅ "Should the new capability use the same configuration approach (e.g., same application.properties keys)?"

### Step 6: Define Integration Points

For existing projects, the design spec MUST include:

1. **Requested Additions** — what new routes/components/capabilities to add
2. **Integration Points** — how new routes connect to existing routes (direct:, seda:, event bus, etc.)
3. **Affected Files** — which existing files will be modified (if any)
4. **Unchanged Behavior** — explicit list of routes/features that must remain unchanged
5. **Compatibility Constraints** — must not upgrade Camel version, must not change DSL type, must not break existing tests

## Interview Template

Use this question flow:

### Understanding the Request

```
1. What new capability do you want to add?
   (Be specific: new REST endpoint, new integration, new transformation, etc.)

2. How does this new capability relate to existing routes?
   - Extends an existing route
   - Runs independently but shares config/beans
   - Replaces part of an existing route (specify which part)
   - Completely independent
```

### Scope Boundaries

```
3. Should I modify any existing routes, or only add new ones?
   (If modifying, specify which routes and why)

4. Are there any existing routes that must NOT be affected?
   (List critical routes to preserve)

5. Should new routes follow the same patterns as existing routes?
   (Error handling, logging, metrics, transaction management)
```

### Technical Details

```
6. [Only if not already in pom.xml] Do you need any new Camel components?
   (Check graph first - only ask if the component isn't already a dependency)

7. Should the new capability reuse existing beans/processors, or create new ones?
   (Reference specific beans by name if reusing)

8. What's the expected interaction with existing tests?
   (Add new tests, update existing tests, or both)
```

### Version and Compatibility

```
9. [Skip this question - you already know from config]
   Camel version: {detectedCamelVersion}
   Runtime: {runtime}
   Primary DSL: {primaryDsl}

   The new capability will use the same stack.
```

### Constraints

```
10. Are there any constraints I should know about?
    - Must not change dependency versions
    - Must not modify certain configuration files
    - Must maintain backward compatibility with existing APIs
    - Must follow specific naming conventions
```

## Output Requirements

When assembling the design spec for an existing project, load `guides/design-assembly.md` and ensure the spec includes:

- **Existing Project Context** section summarizing discovered metadata
- **Integration Strategy** section explaining how new capabilities fit with existing routes
- **Compatibility Guarantee** section listing what will NOT change
- **Affected vs. Unaffected Files** clearly distinguished
- **Dependency Impact** section (will new components be added? will versions stay the same?)

## Anti-Patterns

❌ **Don't assume existing code is correct** — verify component usage even if it's already in the project
❌ **Don't modify unrelated routes** — scope changes to approved additions only
❌ **Don't upgrade versions silently** — existing projects stay on their detected versions
❌ **Don't convert DSL** — if the project uses YAML, stay in YAML; if Java, stay in Java
❌ **Don't ask redundant questions** — use discovered metadata to skip answered questions

## Example: Good vs. Bad Interview

### Bad (Re-asks Everything)

```
Agent: "What Camel version are you using?"
User: "You should already know that from the pom.xml."

Agent: "Do you prefer YAML or Java routes?"
User: "You just read my existing YAML routes..."

Agent: "What runtime are you on?"
User: "Why are you asking me questions you can discover yourself?"
```

### Good (Discovers First, Asks Unresolved)

```
Agent: "I've analyzed your project:
- Camel 4.8.0 on Spring Boot 3.3.4
- 5 existing YAML routes using rest, timer, and http components
- Error handling via errorHandler with deadLetterChannel
- Tests using Spring Boot Test

What new capability would you like to add to this integration?"

User: "I need a new REST endpoint that calls an existing database bean."

Agent: "I see you have a 'userDatabaseBean' in your config. Should the new endpoint reuse that bean?"

User: "Yes, exactly."

Agent: "Should the new endpoint follow the same error handling pattern as your existing /api/users route?"

User: "Yes, same pattern."
```

## Transition to Design

After the interview:

1. Load `guides/version-selection.md` — **but bind to detected version, don't ask the user to choose**
2. Load `camel-design/` guides as usual for component selection and EIP patterns
3. Load `guides/design-assembly.md` and ensure the existing-project sections are populated
4. Present the design spec for approval
5. Transition to `/camel-plan` (chained mode) or STOP (standalone mode)

## Red Flags — STOP If You Catch Yourself:

- "Let me ask about your Camel version..." — NO, you detected it
- "Do you use YAML or Java routes?" — NO, you saw the primaryDsl
- "I'll upgrade to the latest Camel version..." — NO, existing projects stay on their version
- "I'll convert this to Java for better type safety..." — NO, don't change the DSL
- "Let me modify this unrelated route to make it better..." — NO, only approved scope
- "I'll just add this dependency, it's small..." — NO, verify dependencies don't conflict

---

**Remember:** Existing projects have context. Use it. Don't make the user repeat information you can discover.
