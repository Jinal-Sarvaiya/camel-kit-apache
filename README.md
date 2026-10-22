# Apache Camel Kit

Design, implement and verify Apache Camel integrations with AI coding assistants.

<p align="center">
  <img src="camel-kit.gif" alt="Camel Kit" width="600"/>
</p>

This repository contains two independently released products:

| Product | Purpose | Documentation |
|---|---|---|
| **Kit** | The CLI, agent skills, project graph and Camel JBang plugin for the integration lifecycle | [Kit README](kit/README.md) |
| **Knowledge** | A standalone MCP server for searching Camel documentation, release notes, CVE advisories and component metadata | [Knowledge README](knowledge/README.md) |

Kit installs workflows such as `/camel-start`, `/camel-brainstorm`, `/camel-plan`,
`/camel-execute`, `/camel-validate` and `/camel-knowledge` into supported coding agents.
Knowledge also works with MCP clients that do not use Kit. Ship is a Technology Preview.

## Getting started

For released installation commands and a first integration, see the
[Kit installation guide](kit/README.md#installation). For standalone documentation
search, see [running Knowledge](knowledge/README.md#running-the-mcp-server).

The [shared Antora documentation](docs/modules/ROOT/pages/index.adoc) covers both
products. Its component descriptor is [docs/antora.yml](docs/antora.yml).

## Repository layout

```text
pom.xml       Shared parent and aggregator, with its own version
kit/          CLI, skills, graph, Ship resolver and JBang plugin
knowledge/    Schema, embedding, indexer, reviewed index and MCP server
docs/         Shared documentation and Antora website source
tools/        Distribution and release tooling
```

The product POMs own their versions and dependencies. The root parent provides
common build conventions. `distribution.properties` is the source of truth for
Kit's tested distribution pins. Java packages use `org.apache.camel.kit`; Maven
modules use `org.apache.camel`.

## Building from source

Use Java 17 or later and the checked-in Maven wrapper. Run these commands from
the repository root:

```bash
git clone https://github.com/apache/camel-kit.git
cd camel-kit
./mvnw -B clean install
```

This builds and tests both products. Knowledge downloads its embedding models
when they are not cached; ordinary builds use the reviewed index already in the
repository and do not regenerate it.

For an individual product or the formatting gate:

```bash
# Install the shared parent first if the full root build has not already done so
./mvnw -B -N install
./mvnw -B -f kit/pom.xml clean install
./mvnw -B -f knowledge/pom.xml clean install
./mvnw -B -Psourcecheck validate
```

After building, run Kit from its source launcher or run the standalone Knowledge server:

```bash
jbang kit/camel-kit-main/src/main/jbang/main/CamelKit.java --help
java -Dknowledge.index.path=knowledge/index/src/main/resources/knowledge-index \
  -jar knowledge/mcp/target/camel-kit-knowledge-mcp-0.0.3-SNAPSHOT-runner.jar
```

## Versions and index updates

The shared parent, Kit and Knowledge have independent release versions. A Kit
release pins an exact tested Knowledge server version; releasing Knowledge does
not automatically change that pin. The current Kit default still uses the
released Knowledge 0.0.2 artifact under `io.github.luigidemasi`; development
Knowledge artifacts use `org.apache.camel`.

The search index has its own versions. Maintainers publish validated index
releases explicitly. The server automatically selects a compatible published
index, supports an exact index-version pin, and retains a working cached index
when an update fails. See [Knowledge index distribution](knowledge/README.md#index-distribution).

## Security

To report a suspected vulnerability, follow [SECURITY.md](SECURITY.md) — never a public issue or pull request. The [Security Model](docs/security-model.md) documents trust boundaries and security-report triage.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development checks and
[the release guide](docs/releasing.md) for independent release preparation.
Report issues and propose changes at [apache/camel-kit](https://github.com/apache/camel-kit/issues).

Licensed under the [Apache License, Version 2.0](LICENSE).
