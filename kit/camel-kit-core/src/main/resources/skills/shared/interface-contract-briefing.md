# Interface Contract Discovery Briefing

> **Context:** Included in controller-issued DISCOVERY tasks for Ship workers and loaded by camel-brainstorm.
> **Purpose:** Per-boundary contract discovery rules applicable to all discovery paths.

---

## Interface Contract Rules

For each integration boundary (source and destination), establish the interface contract status separately:

### Ask One Boundary at a Time

1. **For the source** (where data comes from):
   - Do you have an interface definition, will you provide one later, should we design it, or is it not applicable?

2. **For the destination** (where data goes):
   - Do you have an interface definition, will you provide one later, should we design it, or is it not applicable?

### Analyze Supplied Material First

Before asking about a boundary's contract, check if the user already provided:
- OpenAPI specification
- AsyncAPI specification
- WSDL document
- JSON Schema
- XSD schema
- Documented message contract

If found, read it, extract constraints, record status as "existing", and skip the question for that boundary.

### Four Possible Statuses

**Status: existing**
- Request artifact details: file path, URL, or inline content
- Request contract type: OpenAPI/AsyncAPI/WSDL/JSON Schema/XSD/other
- Request compatibility constraints or version requirements
- Read and establish: schema version, required vs. optional fields, data type constraints, breaking vs. non-breaking policies
- Record the artifact reference and all constraints

**Status: pending**
- Record dependency: "[source/destination] contract must be provided before execution"
- Note: "Detailed field-level design decisions remain open"
- Do NOT invent a placeholder contract
- Defer DataMapper or field-level transformations that depend on this contract

**Status: designed**
- Propose contract definition based on requirements
- Include in design specification for user approval before implementation
- ONLY for user-controlled endpoints (never redesign external system interfaces)
- Specify contract type and present proposed schema

**Status: not-applicable**
- Record rationale for why no formal contract is needed
- No further action required for this boundary

### Record Both Boundaries

Capture separately:
- `flow.contracts.source`: status, artifact, type, constraints, dependencies
- `flow.contracts.destination`: status, artifact, type, constraints, dependencies

Both values transfer into the design specification under each flow's Source and Sink sections.

---

## Supported Contract Artifacts

- OpenAPI (REST APIs)
- AsyncAPI (async/event-driven APIs)
- WSDL (SOAP services)
- JSON Schema
- XSD (XML schemas)
- Documented message contracts

---

## DataMapper Coordination

If field mapping is needed (Question 7a in greenfield-interview.md):
- Check if source contract status is "pending"
- Check if destination contract status is "pending"
- If EITHER is pending, defer DataMapper interview
- Note the dependency in the design spec

---

## Ship DISCOVERY Tasks

When generating DISCOVERY prompts for Ship workers, include:
1. This briefing's per-boundary rules
2. Grouped question format (Ship's style)
3. Controller-owned oversight boundaries
4. Structured result schema for contract status

Ship workers return the typed ledger requested by the controller and never own persistence, approval, or stage transitions.
