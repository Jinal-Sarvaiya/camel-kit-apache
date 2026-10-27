# Security Policy

## Supported Versions

Apache Camel Kit is developed and released under the Apache Camel umbrella. The repository contains two
independently released products, **Kit** (`kit/`) and **Knowledge** (`knowledge/`), plus the published Knowledge
**index** releases (`knowledge-index-v1-*`). Security fixes land on `main` and ship in the next applicable product
release; only the latest release line of each product is supported. See the
[Kit release channels](kit/README.md#release-channels), the
[Knowledge README](knowledge/README.md#running-the-mcp-server) and the
[GitHub releases](https://github.com/apache/camel-kit/releases) for the current versions.

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
[Security Model and Threat Model](docs/security-model.md). It covers both products and documents:

- **who is trusted** — the developer and their OS account, and the AI coding harness for its own permission model;
- **where the trust boundaries sit** — the source trees Kit reads without running them, the output of the AI model
  (treated as untrusted input, including the arguments it passes to MCP tools), the artifacts and index releases
  downloaded at pinned coordinates, the Ship controller's workers, validation and publication, and the Knowledge
  server's transport, index acquisition and search tools;
- **which findings count as a Camel Kit vulnerability** — a breach of a property the model claims (§4.8): code
  execution, network reach, an outside-root read or non-termination while reading a source tree, XML external
  entity processing, writes outside the operator-selected target, an unpinned or unverified supply-chain step, a Ship
  worker steering state or publication, a denied credential path entering a snapshot or prompt, an unredacted secret
  in evidence, a validation JVM reaching the live project or the network, a Knowledge index accepted on a checksum
  mismatch or unpacked outside its cache directory, a Knowledge tool argument reaching the filesystem, the network or
  a code path, a Knowledge listener opened without the operator's opt-in, or a generated harness asset granting more
  than documented;
- **which categories are out of scope** — a malicious process already running as the developer, prompt injection
  that steers the model without a documented tool, permission, parser or controller boundary failing, insecure designs
  the developer approved, the runtime behaviour of the generated integration (governed by the
  [Apache Camel Security Model](https://camel.apache.org/manual/security-model.html)), external MCP servers' own code,
  behaviour reachable only under a widened or non-default configuration, and shipped-but-unsupported code.

The Camel Kit security model is the additive sub-project expansion of the umbrella Apache Camel Security Model for
the development-time surfaces that model does not cover; it inherits the umbrella's trust assumptions and does not
contradict it. A [`docs/security-model.yaml`](docs/security-model.yaml) sidecar mirrors the triage-relevant facts for
automated tooling. Reports outside the documented scope will be closed with a reference to that document.

## Related Projects

- Suspected vulnerabilities in the **Camel JBang MCP server** (`camel-jbang-mcp`) or in **Apache Camel** itself are
  reported to Apache Camel through the same process above.
- The **Knowledge MCP server, its index and its indexer** live in [`knowledge/`](knowledge/) and are covered by
  §4.4a of the security model. Report suspected vulnerabilities through the same private process above. The
  `0.0.2` release published under the former `io.github.luigidemasi` coordinates is the currently supported Knowledge
  release line.
- The **Citrus MCP server**, **JBang**, **Maven**, **Pi**, **Node** and the **AI coding harnesses** Camel Kit targets
  belong to their respective projects.
