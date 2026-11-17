# Camel Kit Knowledge

Knowledge layer for [Camel Kit](../README.md) -- semantic search over Apache Camel documentation, release notes, CVE advisories, and component metadata.

Release **0.0.2** bundles the Camel **4.22.1** catalog for endpoint validation.
Development continues at **0.0.3-SNAPSHOT**. Unless explicitly marked `0.0.2`, this
README describes the current monorepo sources. Their downloadable knowledge index
uses independent `knowledge-index-v1-*` releases; the published `0.0.2` server keeps
its original download URL and does not support the new release-discovery or pinning options.

## Modules

| Module | Description |
|--------|-------------|
| **schema** | Shared Lucene field constants and `KnowledgeDocument` builder |
| **embedding** | ONNX-based embedding provider (Granite Embedding Small English R2, 384-dim, Q8) |
| **indexer** | Document ingestion pipeline -- Git repos, AsciiDoc conversion, Camel Catalog JARs |
| **index** | Pre-built Lucene index (published as a GitHub Release asset, see Index Distribution) |
| **mcp** | Quarkus MCP server exposing search tools to AI agents |

## Prerequisites

- Java 21+
- Use the Maven wrapper at the repository root. All commands below run from that root.

No Docker. No Docling. No external tokens.

## Data Sources

| Source | Content | Format |
|--------|---------|--------|
| `apache/camel` | Component docs, EIP patterns, user manual | AsciiDoc |
| `apache/camel-website` | Release notes, CVE advisories | Markdown (YAML frontmatter) |
| `apache/camel-quarkus` | Quarkus extension docs | AsciiDoc |
| `apache/camel-spring-boot` | Spring Boot starter docs | AsciiDoc |
| Camel Catalog JAR | Component/EIP option metadata (properties, types, defaults) | JSON |

## Dynamic Version Resolution

An explicit index rebuild discovers the documentation versions to ingest. Normal
builds use the committed index without refreshing its content. This selection does
not change the server's separately pinned endpoint-validation catalog.

1. **Active versions** -- the indexer refreshes `apache/camel-website` and parses release frontmatter (`content/releases/release-*.md`). It selects every supported LTS line (`kind: lts`, `eol > today`), plus the latest release line only when it is non-LTS. A latest LTS release therefore leaves no non-LTS line selected. Draft and future-dated releases are ignored; the highest published patch represents each selected line.
2. **Release tags** -- latest release tags are resolved via `git ls-remote` (JGit) for `camel`, `camel-spring-boot`, and `camel-quarkus`
3. **Quarkus-to-Camel mapping** -- resolved by fetching each Quarkus release tag's `pom.xml` from GitHub and reading the `camel.major.minor` property
4. **Tag-aware cloning** -- repos are cloned at immutable release tags (not moving branches). `.fetched-tag` marker files enable cache reuse between builds

The selected lines control component documentation, runtime documentation, and Camel Catalog metadata. Historical release notes from the oldest selected line onward remain searchable, so older non-LTS versions can still appear in `camel_docs_index_info` through their release history. CVE advisories are indexed independently of this version selection.

## AsciiDoc Conversion

Component documentation is converted directly from AsciiDoc to Markdown via a custom AsciidoctorJ converter (`MarkdownConverter`). No HTML intermediate, no Docling container.

- Antora `partial$` includes resolved by inlining partial files with attribute substitution
- Antora `jsonpath$` / `jsonpathcount$` extensions reimplemented in Java (`JsonPathIncludeProcessor`, pre-processing pipeline)
- `jsonpathTable::` block macros render option tables from component JSON data
- Component docs render with real data (e.g. "The Kafka component supports 127 options")

## Camel Catalog Integration

Endpoint validation uses the bundled Camel **4.22.1** catalog. The downloadable index
is refreshed independently; updating it does not change the validator catalog.

`CamelCatalogIndexer` downloads the `camel-catalog` JAR from Maven Central for each active version, extracts component and EIP JSON metadata, and creates document chunks with structured option data (properties, types, defaults, descriptions). If the exact version JAR is not on Maven Central (unreleased), it falls back to previous patch versions.

## Build

### Normal build (uses pre-built index)

```bash
./mvnw -B -N install
./mvnw -B -f knowledge/pom.xml clean install
```

The MCP module downloads the ONNX embedding model from HuggingFace during `generate-resources` (skipped if already cached).

All Knowledge modules use Maven group `org.apache.camel` and Java packages under
`org.apache.camel.kit.knowledge`. Java consumers must update dependencies and imports
from the previous `io.github.luigidemasi` coordinates and `io.github.luigidemasi.camelkit`
package prefix. Knowledge and Kit inherit build conventions from the root parent,
but retain independent versions and release schedules. The MCP server also works
with clients that do not use Kit.

### Rebuild index from source

```bash
./mvnw install -pl knowledge/index -Prebuild-index -am -B
```

This will:
1. Clone/update Git repos at latest release tags
2. Render AsciiDoc to Markdown with resolved option tables
3. Download Camel Catalog JARs for structured option data
4. Parse release notes and CVE advisories
5. Enrich release notes with Apache JIRA issue details (public, no auth required)
6. Generate embeddings (384-dim vectors; cached in `knowledge/indexer/src/main/resources/apache-camel/embedding-cache/`, so
   incremental rebuilds only embed new or changed chunks)
7. Write the Lucene index to `knowledge/index/src/main/resources/knowledge-index/` plus an
   `index.json` manifest skeleton

### CVE enrichment and fallback

Apache Camel advisories remain the source for affected versions, fixes, severity, and descriptions.
For optional CVSS scores/vectors and CWE classifications, the indexer reuses valid cached NVD records,
then queries NVD. If NVD returns an HTTP error (including 429), times out, or returns an invalid or
missing record, it falls back to [CIRCL Vulnerability-Lookup](https://circl.lu/services/cve-search/),
using its FKIE mirror of NVD data. A mirror can lag behind NVD; only a record matching the requested
CVE ID is accepted.

No API key is required. Uncached requests are spaced at least 6.1 seconds apart across both services.
Each request has a 15-second deadline including its response body, with a 5-second connection timeout.
HTTP 429/503 responses put that service on cooldown according to `Retry-After` (30 seconds when absent
or invalid); the other service is still eligible. Each lookup attempts each available service once.

Successful responses are cached under `knowledge/indexer/src/main/resources/apache-camel/cve-cache/`, together
with their source, URL, and retrieval time. Existing NVD caches remain usable. If both services fail,
the Apache advisory is still indexed without the optional enrichment; failures are not cached, so a
later rebuild retries them. Rerun the rebuild command after updating the indexer to enrich previously
skipped CVEs; completed index files are not changed automatically.

## Index Distribution

The index is **data, not code** — it is published as a GitHub Release asset, not a Maven artifact.
Reindexing is performed on a branch; the generated index is committed with a signed commit and reviewed through a PR.
After that PR is merged, the `Index Release` workflow (manually dispatched from `main` with the reviewed commit SHA, independently of server releases)
builds the reviewed index without regenerating it, runs the retrieval-quality gate (`RetrievalQualityTest`, with working
vectors required), verifies that the build did not modify the index, and publishes the reviewed `knowledge-index.zip` +
`index.json` to a release tagged `knowledge-index-v1-YYYY.MM.DD-HHMMSS`.

On the first knowledge query, the MCP server resolves the index in this order:

1. **`knowledge.index.path`** — a local index directory, used in place (dev, tests, air-gapped)
2. **Release discovery** — query `https://api.github.com/repos/apache/camel-kit/releases`
   and select the newest published `knowledge-index-v1-YYYY.MM.DD-HHMMSS` release.
   Product releases, drafts, prereleases and other format channels are ignored.
   `knowledge.index.version=YYYY.MM.DD-HHMMSS` selects an exact index tag instead.
   An explicit `knowledge.index.url` bypasses discovery and supports legacy or mirrored manifests.
   The manifest must match the selected tag, v1 format, embedding model and 384 dimensions.
   The archive is SHA-256 verified, unzipped and activated atomically in
   `~/.camel-kit/knowledge-index/`. A failed update retains the previous working index;
   a pinned client may fall back only to that exact cached version. The index is opened
   directly from the cache, without per-startup extraction.
3. **Classpath** — legacy fallback for uber-jars bundling the index, only when no
   exact index version is pinned

Discovery runs on the first knowledge query in each server process. It follows at most
10 release-list pages (100 releases per page); configure an explicit manifest URL if
that bound is exceeded. Each page has the same five-second response deadline as a manifest.
The v1 manifest contract is `formatVersion: 1`, model
`granite-embedding-small-english-r2-q8`, and `embeddingDimensions: 384`. Legacy manifests
may omit format and dimension fields, but must identify that model. New publishers
include all fields. A breaking format or model change requires a new channel.

The first monorepo index release must be published before default discovery is usable.
Until then, set `knowledge.index.path` to the committed local index, or use the old
manifest explicitly: `https://github.com/luigidemasi/camel-kit-knowledge/releases/latest/download/index.json`.
Existing released servers continue to use their original artifact and download URLs.

The MCP handshake and tool listing do not initialize the index; call `camel_docs_index_info` to check readiness.
With no usable cache, a failed first installation requires a reachable manifest or `knowledge.index.path`.
The cache filesystem must support advisory file locks and atomic moves for both version directories and metadata.
If acquiring the lock or an atomic move fails, a readable active cache remains usable; without one, the download attempt
fails. A later attempt can activate an update once the failure clears; retrying alone cannot add missing advisory-lock
or atomic-move support. Atomic moves do not guarantee durability across power loss.

Cache updates are serialized across threads and processes, from reading the active version through pruning.
Cache sharing requires all clients to use this locking protocol; older clients without it must use a separate cache.
When another updater holds the lock, a caller uses a readable active cache immediately. Without a usable cache, lock
acquisition waits at most five seconds in total across threads and processes before the download attempt fails.
Each download uses its own staging directory. The next locked attempt cleans up recognized interrupted staging files,
including legacy `<version>.part` entries only when they are real directories and the version prefix follows the
safe-name rules below.
New metadata files honor the process umask; replacements preserve existing POSIX permissions.
HTTP connections have a three-second timeout. Manifest requests must finish within five seconds and archive requests
within five minutes, including the response body; a timeout falls back to the readable active cache when available.
The manifest ETag is saved after activation and bound to the manifest URL and version; saving it is best effort.
An HTTP 200 response without an ETag removes the old validator, and legacy unbound validators trigger a fresh check.

Custom manifest versions must be 1–128 ASCII letters, digits, dots, underscores or hyphens, beginning with a letter
or digit. Trailing dots, `.part` suffixes, `current`, `etag`, and Windows device names are reserved (case-insensitive).
Version directories must be real directories inside the cache, not symbolic links.

Two runtime guards protect the vector leg: the index carries an embedding-model stamp
(`__index_meta__`), and a startup self-check re-embeds a few stored chunks and verifies the
stored vectors match — on any mismatch, vector search is disabled and search degrades to
BM25 + reranker with a loud log line.

## Running the MCP Server

### Current source build

After the build above, run the server from the repository root with the committed index:

```bash
java -Dknowledge.index.path=knowledge/index/src/main/resources/knowledge-index \
  -jar knowledge/mcp/target/camel-kit-knowledge-mcp-0.0.3-SNAPSHOT-runner.jar
```

The installed runner's Maven coordinates are
`org.apache.camel:camel-kit-knowledge-mcp:0.0.3-SNAPSHOT:runner`.
Kit still pins the released `0.0.2` runner until explicitly configured otherwise;
building this source does not alter that pin. See
[using the local runner with Kit](../kit/README.md#use-the-locally-built-knowledge-server-with-kit).

The server uses stdio by default and does not open an HTTP port. To enable the
HTTP and SSE transports explicitly on localhost:

```bash
java -Dquarkus.http.host-enabled=true -Dquarkus.http.host=127.0.0.1 \
  -Dquarkus.http.port=9090 \
  -Dknowledge.index.path=knowledge/index/src/main/resources/knowledge-index \
  -jar knowledge/mcp/target/camel-kit-knowledge-mcp-0.0.3-SNAPSHOT-runner.jar
```

### Published release 0.0.2

The existing release remains available at its original coordinates:

```bash
jbang io.github.luigidemasi:camel-kit-knowledge-mcp:0.0.2:runner
```

It uses the standalone repository's original index download URL. The monorepo
release-discovery and exact-version pinning behavior below requires the current source build.

### Configure index selection in current source builds

Choose one of these configurations in an external `config/application.properties`
relative to the server's working directory, or pass each property as a `-Dkey=value`
argument before `-jar`. Remove the local-path argument from the examples above when
using discovery or a manifest: `knowledge.index.path` takes precedence over both.

To select an exact monorepo release, replace this illustrative version with an
existing published index version:

```properties
# Exact published tag suffix; example only
knowledge.index.version=2026.10.26-120000
```

To bypass release discovery with a mirrored manifest (which must reference an
accessible archive), use:

```properties
knowledge.index.url=file:///opt/mirrors/camel-kit/index.json
```

To use an unpacked local index without downloading it, use:

```properties
knowledge.index.path=/opt/camel-kit/knowledge-index
```

An explicit manifest URL may also be combined with a version pin; its manifest
version must match the pin. A local path is used directly and bypasses release-version
selection. Environment equivalents include `KNOWLEDGE_INDEX_PATH`,
`KNOWLEDGE_INDEX_URL` and `KNOWLEDGE_INDEX_VERSION` in the MCP server process.

## MCP Tools

7 tools with `camel_docs_*` prefix:

| Tool | Description |
|------|-------------|
| `camel_docs_search` | General hybrid search across all documentation |
| `camel_docs_component_info` | Component lookup with usage, options, and related CVEs |
| `camel_docs_cve_search` | CVE search by ID, component, severity, or version |
| `camel_docs_release_info` | Release notes by version |
| `camel_docs_jira_lookup` | CAMEL-* JIRA issue details and fix version |
| `camel_docs_validate_endpoint` | Deterministic endpoint URI validation against the Camel catalog |
| `camel_docs_index_info` | Index metadata: covered versions, doc counts, embedding model, search mode |

## Search Architecture

**Hybrid search** combining:
- **BM25 text search** (20% weight) -- keyword matching via Lucene's standard analyzer
- **Vector search** (80% weight) -- semantic similarity via KNN float vectors (384-dim Granite embeddings)

Full-text results are reranked with a local cross-encoder when its model is available.
If embeddings are unavailable or fail the compatibility checks, retrieval falls
back to BM25, with reranking when available; `camel_docs_index_info` reports the active mode.

Component lookups (`camel_docs_component_info`) first query the exact structured
component field. If there is no exact hit, they use full-text search and label the
response `match: "fuzzy"`; exact hits use `match: "exact"`, and no results use `match: "none"`.

## Configuration Reference

### System properties

| Property | Purpose | Default |
|----------|---------|---------|
| `-Djira.parallelism` | JIRA fetch thread pool size | `4` |
| `-Ddownload.parallelism` | Git repo download thread pool size | `4` |

### MCP application properties

| Property | Purpose | Default |
|----------|---------|---------|
| `knowledge.index.path` | Local index directory, used in place (no download) | _(unset)_ |
| `knowledge.index.url` | Explicit manifest URL (https:// or file://); overrides discovery | unset |
| `knowledge.index.version` | Exact downloaded index version (tag suffix); no fallback to another version; local path takes precedence | unset, newest compatible |
| `knowledge.index.releases-url` | GitHub-compatible releases API endpoint | `https://api.github.com/repos/apache/camel-kit/releases` |
| `knowledge.index.cache-dir` | Local cache for downloaded index versions | `~/.camel-kit/knowledge-index` |

### Cache locations (gitignored)

| Path | Contents |
|------|----------|
| `knowledge/indexer/src/main/resources/apache-camel/repos/` | Cloned Git repositories |
| `knowledge/indexer/src/main/resources/apache-camel/catalog-cache/` | Downloaded Camel Catalog JARs |
| `knowledge/indexer/src/main/resources/apache-camel/embedding-cache/` | Chunk embeddings keyed by model+text (incremental rebuilds) |

Apache JIRA (CAMEL-* issues) is public and requires no authentication.

See the [shared release guide](../docs/releasing.md) for independent parent, product
and maintainer-triggered index publication.

## License

Apache License 2.0
