# Greenfield Interview Guide

> **Context:** Loaded by `camel-brainstorm` when the project type is greenfield.
> **Purpose:** Socratic interview to understand business needs, systems, flows, and constraints.
> **Output:** Structured answers ready for `design-assembly.md` to produce the design spec.

---

## Interview Rules

1. Ask **ONE question at a time**. Wait for the user's response before proceeding.
2. Prefer **multiple choice** when possible, open-ended when necessary.
3. **Listen** — extract all information from each answer, don't re-ask what's already been said.
4. Analyze the user's supplied material first. If it conclusively resolves an item, record its evidence and do not
   re-ask it. Ask only about unresolved conflicts, ambiguities, assumptions, or untaken material decisions.
5. Never leave a required category unresolved. Mark conditional categories as "N/A" with a concrete rationale when
   their condition is not met. A complete requirements document may need zero clarification questions.

---

## Question 1: Project Name

```
What is the name of this integration project?

This should be a clear, descriptive name (e.g., "E-Commerce Order Fulfillment Integration").
```

Record: `project.name`

---

## Question 2: Business Purpose

```
What is the business purpose of this integration?

- What business problem does it solve?
- What business value does it deliver?
- Who are the primary beneficiaries?

Example: "Automate order fulfillment by connecting the e-commerce platform to the warehouse,
reducing manual data entry and enabling same-day processing."
```

If the purpose is vague, probe:
```
Can you tell me:
- What manual process or problem exists today?
- What will be different/better after this integration?
- Who will benefit from this change?
```

Record: `project.purpose`, `project.value`, `project.stakeholders`

---

## Question 3: Systems Landscape

```
What systems or applications need to be integrated?

Just list the system names and types — we'll define how they connect next.

Example:
- E-commerce Platform (Shopify)
- Warehouse Management System (SAP)
- Customer Database (PostgreSQL)
- Email Service (SendGrid)
```

Record: `systems[]` with name, type, role (source/target/both)

---

## Question 4: Integration Goals and Flow Naming

```
What integrations do you need to build?

For each integration, describe:
- What data needs to move
- Why it needs to move
- What should happen with it

Example:
- "New orders need to reach the warehouse for fulfillment"
- "Order status updates need to sync back to e-commerce"
- "Customers should receive email confirmations when orders ship"
```

After receiving goals, suggest flow names using kebab-case (`^[a-z][a-z0-9]*(-[a-z0-9]+)*$`):

```
Based on your requirements, I suggest these flows:

1. order-to-warehouse: New orders reach the warehouse for fulfillment
2. warehouse-status-sync: Order status updates sync back to e-commerce
3. shipment-notification: Email confirmations sent when orders ship

Do these flow names work? (yes/modify)
```

If user provides names with spaces, uppercase, or special characters — auto-correct and confirm.

Record: `flows[]` with name, description, source_system, target_system

---

## Per-Flow Deep Dive

For EACH flow identified, ask the following questions in sequence. This replaces the old separate flow interview — all
flows are designed in a single brainstorm session.

### Question 5: Flow Intent and Data (per flow)

```
For flow "[flow-name]":

What data does this flow process, and what is the goal?
- Data type (e.g., "Order events", "Customer records")
- Format (e.g., JSON, XML, CSV)
- Goal (e.g., "Store in database", "Send to queue")
```

Record: `flow.dataType`, `flow.dataFormat`, `flow.goal`

### Question 6: Source System (per flow)

```
For flow "[flow-name]":

Where does the data come from?
- System name (e.g., "Shopify", "Kafka topic")
- Technology (e.g., Kafka, REST API, File, Database)
- Trigger (e.g., "New messages on topic", "Polling every 5s", "HTTP POST")
```

After response: note source for MCP component verification.

Record: `flow.source.system`, `flow.source.technology`, `flow.source.trigger`

### Question 7: Transformations (per flow)

```
For flow "[flow-name]":

What transformations or business rules are needed?

a) Parse JSON/XML
b) Validate fields
c) Filter messages
d) Enrich with data from another system
e) Transform/map message format (field mapping)
f) Route by condition (different destinations)
g) None — passthrough

Select all that apply.
```

If user selects (e) and format pair is XML/JSON combinations: note for DataMapper interview.
If user selects (f): note for multi-path routing question.
If "none"/passthrough: skip Question 7a.

Record: `flow.transformations[]`

### Question 7b: Source Interface Contract (per flow)

Analyze supplied material first. If the user already provided a source contract artifact (OpenAPI, AsyncAPI, WSDL, JSON Schema, XSD, documented message contract), read it, record the status as "existing", extract constraints, and skip this question for the source.

```
For flow "[flow-name]" source (where data comes from):

Do you have an interface definition, will you provide one later, should we design it, or is it not applicable?

a) Existing definition available now — I'll provide it
b) Definition will be supplied later
c) We should design it together
d) Not applicable (no formal contract needed)
```

**For "existing definition available now":**
```
Please provide the contract artifact:
- File path, URL, or inline content
- Contract type (OpenAPI, AsyncAPI, WSDL, JSON Schema, XSD, other)
- Any compatibility constraints or version requirements
```

Read the supplied contract and establish:
- Schema version and compatibility requirements
- Required vs. optional fields
- Data type constraints
- Breaking vs. non-breaking change policies

**For "definition will be supplied later":**
```
Noted. The source contract is pending.

This means:
- Detailed field-level design decisions will remain open
- The implementation plan will record this dependency
- You'll need to provide the contract before execution begins
```

Record the dependency without inventing a placeholder contract. If DataMapper is needed (Question 7a), defer it until the pending contract arrives.

**For "we should design it together":**
```
I'll propose a [contract-type] definition based on your requirements.

This will be included in the design specification for your approval before implementation.
Since this is a source you control, we can design it to fit the integration needs.

The contract proposal will be created during design assembly. No artifact exists yet.
```

Record status as "designed" with no artifact reference yet. The proposal will be carried into the design specification for approval.

If field mapping is needed (Question 7a), it will be deferred until the proposed contract is available during design assembly. The mapping will remain deferred if the other endpoint is still pending at that time.

Note: Only propose designing contracts for systems under your control. External system interfaces cannot be redesigned.

**For "not applicable":**
```
Understood — no formal contract needed for this source.
```

Record: `flow.contracts.source` with status (existing/pending/designed/na), artifact reference, constraints, and dependencies

### Question 7c: Destination Interface Contract (per flow)

Analyze supplied material first. If the user already provided a destination contract artifact, read it, record the status as "existing", extract constraints, and skip this question for the destination.

```
For flow "[flow-name]" destination (where data goes):

Do you have an interface definition, will you provide one later, should we design it, or is it not applicable?

a) Existing definition available now — I'll provide it
b) Definition will be supplied later
c) We should design it together
d) Not applicable (no formal contract needed)
```

**For "existing definition available now":**
```
Please provide the contract artifact:
- File path, URL, or inline content
- Contract type (OpenAPI, AsyncAPI, WSDL, JSON Schema, XSD, other)
- Any compatibility constraints or version requirements
```

Read the supplied contract and establish:
- Schema version and compatibility requirements
- Required vs. optional fields
- Data type constraints
- Breaking vs. non-breaking change policies

**For "definition will be supplied later":**
```
Noted. The destination contract is pending.

This means:
- Detailed field-level design decisions will remain open
- The implementation plan will record this dependency
- You'll need to provide the contract before execution begins
```

Record the dependency without inventing a placeholder contract. If DataMapper is needed (Question 7a), defer it until the pending contract arrives.

**For "we should design it together":**
```
I'll propose a [contract-type] definition based on your requirements.

This will be included in the design specification for your approval before implementation.
Since this is a destination you control, we can design it to fit the integration needs.

The contract proposal will be created during design assembly. No artifact exists yet.
```

Record status as "designed" with no artifact reference yet. The proposal will be carried into the design specification for approval.

If field mapping is needed (Question 7a), it will be deferred until the proposed contract is available during design assembly. The mapping will remain deferred if the other endpoint is still pending at that time.

Note: Only propose designing contracts for systems under your control. External system interfaces cannot be redesigned.

**For "not applicable":**
```
Understood — no formal contract needed for this destination.
```

Record: `flow.contracts.destination` with status (existing/pending/designed/na), artifact reference, constraints, and dependencies

### Question 8: Sink System (per flow)

```
For flow "[flow-name]":

Where should the processed data go?
- System name (e.g., "PostgreSQL", "Fulfillment queue")
- Technology (e.g., SQL, Kafka, HTTP POST)
- Action (e.g., "INSERT INTO orders", "POST to /api/fulfillment")
```

After response: note sink for MCP component verification.

Record: `flow.sink.system`, `flow.sink.technology`, `flow.sink.action`

### Question 8a: Multi-Path Routing (conditional)

ONLY if user indicated multiple destinations or conditional routing.

```
For the conditional routing in "[flow-name]":

What are the routing conditions?
- Condition 1: [condition] → [destination]
- Condition 2: [condition] → [destination]
- Default: [destination]
```

Record: `flow.routing.conditions[]`

### Question 8b: Routing Target Contracts (conditional)

ONLY if Question 8a revealed multiple routing targets. For each distinct routing destination that was NOT already covered by Question 7c:

Analyze supplied material first. If a contract artifact for this routing destination was already provided, read it, record status as "existing", extract constraints, and skip this question for that destination.

Reuse any answers already given in Question 7c. If Question 7c recorded a destination contract, apply it to the matching routing branch and skip asking about that branch.

```
For the [destination-name] branch:

Do you have an interface definition for this destination, or should we design one?

Interface definitions include OpenAPI, AsyncAPI, WSDL, JSON Schema, XSD, or documented message contracts.

a) Existing definition available now — I'll provide it
b) Definition will be supplied later
c) We should design it together
d) Not applicable (no formal contract needed)
```

**For each "existing definition available now" answer:**
```
Please provide:
- The contract artifact (file path, URL, or inline content)
- Contract type (OpenAPI, AsyncAPI, WSDL, JSON Schema, XSD, other)
- Any compatibility constraints or version requirements
```

Read and establish: schema version, required vs. optional fields, data type constraints, breaking vs. non-breaking policies.

**For "definition will be supplied later":**
Record dependency: "Destination contract for [branch-name] must be provided before execution. Field-level design decisions remain open."

**For "we should design it together":**
Only for user-controlled endpoints. Propose contract definition in design spec for approval.

**For "not applicable":**
Record rationale for why no formal contract is needed.

Record: `flow.routing.conditions[].contract` for each branch with status (existing/pending/designed/na), artifact references, constraints, and dependencies.

The design specification must carry each routing branch's contract status separately so pending schemas on one branch and existing contracts on another are both preserved in the approved design.

### Question 7a: Data Transformation Detail (conditional)

ONLY if field mapping needed AND format pair is XML/JSON combinations.

Before proceeding, determine which mappings can be collected now:

**Contract status mapping decisions:**
- **Status "existing" with artifact** - Proceed with mapping, pass artifact to DataMapper
- **Status "designed" with artifact** - Proceed with mapping, pass artifact to DataMapper
- **Status "designed" without artifact yet** - Defer mapping. Note: "Field mapping depends on [source/destination] contract design approval. Kit will propose schema in design spec."
- **Status "pending"** - Defer mapping. Note: "Field mapping depends on pending [source/destination] contract"
- **Status "not-applicable"** - Proceed with schema-less mapping (Groovy with manually described fields)

**For single-destination flows:**
- Check source contract (`flow.contracts.source` from Q7b) and destination contract (`flow.contracts.destination` from Q7c)
- Apply above mapping decisions for each contract status
- Proceed if BOTH endpoints allow mapping (existing with artifact, designed with artifact, or not-applicable)
- Defer if EITHER endpoint requires deferral (pending or designed without artifact)

**For conditional routing flows:**
- Check source contract (`flow.contracts.source` from Q7b)
- For EACH routing branch, check that branch's destination contract (`flow.routing.conditions[].contract` from Q8b)
- Apply mapping decisions per branch
- Collect mapping for each branch where BOTH source and destination allow mapping
- Defer mapping for branches where either endpoint requires deferral
- Record each deferred mapping dependency separately: "Field mapping for [branch-name] depends on [pending contract / design approval for contract]"

If source contract is "pending" or "designed without artifact", defer ALL mappings for this flow.

```
For the data transformation in "[flow-name]":

I'll need to understand the field mappings. Can you describe:
- Source fields and their paths
- Target fields and their paths
- Any transformation logic (concatenation, formatting, default values)

Or if you have example source and target messages, share those.
```

Pass any existing contract artifacts from Questions 7b, 7c, and 8b into DataMapper. The artifacts contain:
- Source schema (from Q7b if status is "existing" or "designed")
- Destination schema (from Q7c for single-destination, or from Q8b for each routing branch if status is "existing" or "designed")

Load `camel-design/guides/datamapper-interview.md` and `shared/datamapper-canonicalize.md` for detailed field mapping. When schema artifacts are provided, skip the schema request steps and use the provided schemas directly. Ask only for information not already available in the contract artifacts.

Record: `flow.datamapper.mappings[]`

After completing DataMapper (or noting deferred mappings), continue to Question 9 (Error Handling).

### Question 9: Error Handling (per flow)

```
For flow "[flow-name]":

What should happen when errors occur?

a) Dead Letter Channel — failed messages sent to error queue
b) Retry with backoff — retry N times, then DLQ
c) Log and continue — log error, keep processing
d) Stop route — halt on error

Recommended: (b) with maximumRedeliveries=3, redeliveryDelay=1000ms,
backOffMultiplier=2, useExponentialBackOff=true
```

Resilience sub-questions (ask if applicable):
- Source/sink involves external HTTP/REST API → circuit breaker?
- Source is message broker or user mentioned deduplication → idempotent consumer?
- Flow writes to multiple external systems → transactions?

If any resilience patterns needed, load `camel-design/guides/resilience-interview.md`.

Record: `flow.errorHandling`, `flow.resilience`

---

## Cross-Cutting Questions (after all flows)

### Question 10: Performance (conditional)

ONLY if user mentioned high volume, real-time, latency, Kubernetes, scale.

```
You mentioned performance requirements. Can you specify:
- Expected throughput (messages/second or messages/minute)
- Latency target (end-to-end processing time)
- Deployment target (local, VM, Kubernetes)
```

Record: `project.performance`

### Question 11: Security (conditional)

ONLY if user mentioned security, PII, compliance, credentials.

```
You mentioned security/compliance requirements. Can you specify:
- Authentication method (OAuth2, API keys, certificates)
- Sensitive fields to protect (PII, financial data)
- Compliance standards (GDPR, SOC 2, HIPAA)
```

Record: `project.security`

### Question 12: Monitoring (conditional)

ONLY if user mentioned monitoring, metrics, logging, tracing.

```
You mentioned monitoring requirements. Can you specify:
- Metrics platform (Prometheus, Micrometer, custom)
- Logging requirements (structured logging, log levels)
- Distributed tracing (OpenTelemetry, Jaeger)
```

Record: `project.monitoring`

### Question 13: Constraints

```
Are there any other constraints or requirements we haven't covered?

- Technology preferences (e.g., "must use Kafka for messaging")
- Team constraints (e.g., "no Java code, YAML only")
- Timeline requirements
- Or type "none" to use standard Apache Camel best practices.
```

Record: `project.constraints`

### Question 14: Scope Boundaries

```
Which useful, adjacent capabilities are we explicitly not building in this iteration, and why?

For each exclusion, name the capability and the concrete reason for leaving it out.
Or type "none" if no deliberate exclusions remain after the interview.
```

Record: `project.notDoing` as a list of excluded capabilities and reasons. If the user answers "none", record
`None identified` rather than inventing exclusions.

---

## MCP Component Verification

After the interview, for EACH flow, verify all source and sink components via MCP catalog:

1. Call `camel_catalog_component_doc` for each source/sink technology. Pass the selected profile’s `catalog` arguments from `shared/mcp-setup.md` on every call, and establish the catalog-version binding there (Iron Law 1).
2. Confirm the component exists and note exact option names

This verification happens BEFORE assembling the design spec. Failed verification = the design spec is wrong.

---

## Handoff

After the interview and component verification, proceed to:
1. `guides/version-selection.md` — select Camel version
2. `guides/design-assembly.md` — assemble the design spec from interview answers

Pass all recorded data to the assembly guide.
