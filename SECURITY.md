# Security Policy

## Supported Versions

Apache Camel Kit is developed and released under the Apache Camel umbrella. Security fixes are made on `main` and
shipped in the next release; only the latest release line is supported. See the
[Kit release channels](kit/README.md#release-channels) in the product README and the
[GitHub releases](https://github.com/apache/camel-kit/releases) for the current version.

Camel Ship is a **Technology Preview**. Preview status does not exclude it from this policy: a report that breaks a
property the security model claims for Ship is handled like any other.

## Reporting a Vulnerability

Apache Camel Kit follows the Apache Software Foundation security process. For information on how to report a new
security problem please see the [Apache Camel security page](https://camel.apache.org/security/).

- Email `private-security@camel.apache.org` with a description, the affected versions, and a proof of concept that
  demonstrates the trust-boundary breach.
- **Do not** open a public GitHub issue, pull request or discussion, post on a mailing list, social media or any
  other public channel for an unpublished vulnerability. Avoid disclosing anything about the potential issue until a
  coordinated fix is released. If in doubt, contact the
  [Apache Software Foundation Security Team](https://apache.org/security/) (`security@apache.org`) and follow their
  instructions.

Reports that match the in-scope properties of the security model are triaged on the private list, fixed in a
coordinated release and published as a CVE advisory. Reports that fall outside the documented scope are closed with
a reference to the security model.

## Security Model

Before submitting a report, please read the project's
[Security Model and Threat Model](docs/security-model.md). It documents:

- **who is trusted** — the developer and their OS account, and the AI coding harness for its own permission model;
- **where the trust boundaries sit** — the source trees Camel-Kit reads without running them, the output of the AI
  model (treated as untrusted input), the artifacts downloaded at pinned coordinates, and the Ship controller's
  workers, validation and publication;
- **which findings count as a Camel-Kit vulnerability** — a breach of a property the model claims (§4.8): code
  execution or network reach while reading a source tree, XML external entity processing, writes outside the target
  workspace, an unpinned or unverified supply-chain step, a Ship worker steering state or publication, a denied
  credential path entering a snapshot or prompt, an unredacted secret in evidence, a validation JVM reaching the live
  project or the network, or a generated harness asset granting more than documented;
- **which categories are out of scope** — a malicious process already running as the developer, prompt injection
  that steers the model without a shipped hard control failing, insecure designs the developer approved, the runtime
  behaviour of the generated integration (governed by the
  [Apache Camel Security Model](https://camel.apache.org/manual/security-model.html)), external MCP servers' own code,
  behaviour reachable only under a widened or non-default configuration, and shipped-but-unsupported code.

The Camel-Kit security model is the additive sub-project expansion of the umbrella Apache Camel Security Model for
the development-time surfaces that model does not cover; it inherits the umbrella's trust assumptions and does not
contradict it. A [`docs/security-model.yaml`](docs/security-model.yaml) sidecar mirrors the triage-relevant facts for
automated tooling. Reports outside the documented scope will be closed with a reference to that document.

## Related Projects

- Suspected vulnerabilities in the **Camel JBang MCP server** (`camel-jbang-mcp`) or in **Apache Camel** itself are
  reported to Apache Camel through the same process above.
- The **Camel-Kit Knowledge MCP server** now lives in [`knowledge/`](knowledge/). Report suspected vulnerabilities
  through the same private Apache Camel process above. Its model coverage remains an open question (Q11);
  missing coverage must not be used to reject a report as belonging to an external project.
- The **Citrus MCP server**, **JBang**, **Maven**, **Pi**, **Node** and the **AI coding harnesses** Camel-Kit targets
  belong to their respective projects.
