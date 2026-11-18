# Security Model Registration Guide

**Issue:** [#131](https://github.com/apache/camel-kit/issues/131)  
**Status:** Pending External Registration  
**Purpose:** Register the Camel Kit security model on security.apache.org and camel.apache.org/security/

## Overview

The Camel Kit security model exists in [`docs/security-model.md`](security-model.md) and is referenced in
[`SECURITY.md`](../SECURITY.md). To complete the registration per Q10 of the security model, it must be added to two
external Apache sites.

## Required External Changes

### 1. Apache Security Site Registration

**Repository:** https://github.com/apache/security-site  
**File:** `scripts/project-coordinates.json`  
**Action:** Add Camel Kit entry under Apache Camel section

#### JSON Entry to Add

Add this entry to the `camel.projects` array in `scripts/project-coordinates.json`:

```json
{
  "name": "Apache Camel Kit",
  "security_model_link": "https://github.com/apache/camel-kit/blob/main/docs/security-model.md",
  "security_model_source": "https://raw.githubusercontent.com/apache/camel-kit/main/docs/security-model.md"
}
```

**Note:** Use `"name"` (not `"product"` or `"bughunter"`). The PMC entry already supplies the reporting contact.
Validate the entry against `scripts/project-coordinates.schema.json` before submitting.

#### Expected Result

Camel Kit will appear on https://security.apache.org/projects/ alongside:
- Apache Camel K
- Apache Camel Quarkus
- Apache Camel Karaf
- Apache Camel Kamelets
- Apache Camel Kafka Connector

#### PR Template for apache/security-site

**Title:** `Register Apache Camel Kit security model`

**Description:**
```markdown
Add Apache Camel Kit to the security projects registry.

Camel Kit is a sub-project of Apache Camel that provides AI-assisted workflows for designing,
implementing, and verifying Apache Camel integrations. The security model covers both Kit
(CLI, skills, graph) and Knowledge (MCP server and index).

**Security Model:** https://github.com/apache/camel-kit/blob/main/docs/security-model.md  
**Tracking Issue:** https://github.com/apache/camel-kit/issues/131

This registration completes the ASF registry step of Q10 of the Camel Kit security model and aligns
with other Camel sub-projects that maintain their own security models (Camel K, Camel Quarkus, Camel
Karaf, Camel Kamelets, Camel Kafka Connector).

Refs apache/camel-kit#131
```

---

### 2. Camel Security Page Update

**Repository:** https://github.com/apache/camel-website  
**File:** `content/security/_index.md`  
**Action:** Add Camel Kit link to the security card's `read_first.body` field

#### Content to Add

In the `read_first.body` field of `content/security/_index.md`, append the following sentence after the existing
paragraph that lists sub-projects with their own security models:

```
Camel Kit also publishes its own [security model](https://github.com/apache/camel-kit/blob/main/docs/security-model.md).
```

**Note:** Preserve the existing wording that explains which projects inherit the umbrella model. Adding the link to the
regular Markdown body will not make it appear on the rendered security card.

#### PR Template for apache/camel-website

**Title:** `Add Apache Camel Kit to security page`

**Description:**
```markdown
Add Apache Camel Kit to the list of Camel sub-projects with their own security models.

Camel Kit provides AI-assisted workflows for the full Apache Camel integration lifecycle and
maintains its own security model covering development-time surfaces not covered by the umbrella
Apache Camel Security Model.

**Security Model:** https://github.com/apache/camel-kit/blob/main/docs/security-model.md  
**Tracking Issue:** https://github.com/apache/camel-kit/issues/131

This aligns the security page with other Camel sub-projects (Quarkus, Spring Boot, Karaf,
Kamelets, Kafka Connector, Camel K) that maintain their own models.

Refs apache/camel-kit#131
```

---

## Implementation Checklist

- [ ] **1. Create PR for apache/security-site**
  - [ ] Fork apache/security-site
  - [ ] Add the entry to `camel.projects` in `scripts/project-coordinates.json` and validate it against `scripts/project-coordinates.schema.json`.
  - [ ] Regenerate and include `content/projects/_index.md` and `content/projects/camel/_index.md`. Verify both contain the Camel Kit model link.
  - [ ] Submit PR with template description above
  - [ ] Record PR link: _________________

- [ ] **2. Create PR for apache/camel-website**
  - [ ] Fork apache/camel-website
  - [ ] Edit `content/security/_index.md`
  - [ ] Add Camel Kit link to the `read_first.body` field (not the regular Markdown body)
  - [ ] Submit PR with template description above
  - [ ] Record PR link: _________________

- [ ] **3. After both PRs are merged:**
  - [ ] Verify Camel Kit appears on https://security.apache.org/projects/
  - [ ] Verify Camel Kit appears on https://camel.apache.org/security/
  - [ ] Update Q10 in `docs/security-model.md` to mark as complete
  - [ ] Close issue #131

---

## Verification

Once registered, confirm:

1. **security.apache.org/projects/**
   - [ ] Camel Kit is listed
   - [ ] Link to security model works
   - [ ] Appears under Apache Camel section

2. **camel.apache.org/security/**
   - [ ] Camel Kit is mentioned
   - [ ] Link to security model works
   - [ ] Listed alongside other sub-projects

---

## Background

- **Issue #5:** Create the security model
- **PR #6:** Added `docs/security-model.md` and `docs/security-model.yaml`
- **Q10 (security-model.md):** Publication requirement - register on ASF security site and Camel security page
- **Issue #131:** This tracking issue for external registration

The security model was merged in PR #6. This registration work makes it discoverable to downstream users
and automated triage systems.

---

## Notes

- The security model covers both **Kit** (0.4.x releases) and **Knowledge** (0.0.x releases)
- Reports should go to `private-security@camel.apache.org` per Apache Camel process
- The model is additive to the umbrella Apache Camel Security Model
- Ship remains covered despite Technology Preview status
