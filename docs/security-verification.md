# Security model verification

Evidence for issues #153–#158. These are automated regression results and source references,
not a claim of human maintainer approval. The security model remains the policy authority.
Tests characterize the stated configuration; neither source inspection nor a passing probe
establishes an OS sandbox. Re-run the named tests when their implementation or dependencies change.

## V1: source reading

`kit/camel-kit-graph/.../ParserSourceReadingContractTest` invokes all nine parsers with
nonempty graph assertions. Fixtures include Java/Groovy code and AST transform canaries,
YAML object tags, external XML entities, runtime-looking placeholders and a secret sentinel.
JFR observes process starts and project-class loads; a loopback HTTP server observes external
entity attempts. The positive control deliberately performs each observed side effect.

The 14 cases passed directly on Java 17, 21 and 25. Isolated mutations moving Groovy compilation
to semantic analysis, substituting a runtime property, loading a project class, starting a
process and enabling XML entity expansion all caused failures. The XML mutation uses element
text: an external entity in an attribute would be rejected even by an otherwise unsafe parser.
These probes protect the covered execution paths; they do not prove safety of arbitrary future
parser/dependency changes. Website impact: not required; this pins existing behavior.


## V2: graph inputs

`GraphInputBoundaryTest` exercises actual parsing and `GraphBuilder`: outside file links,
directory links, an operator-selected root alias, direct supplied outside/oversized lists,
16 MiB and 16 MiB+1 sources, aggregate overflow, large unrelated artifacts and a deliberately
slow POM parser. Production bounds are 16 MiB per input, 64 MiB aggregate recognized source
and 100,000 visited entries. Sources are checked before parser sniffing and bounded again
when read. The POM pass uses the same timeout policy as the remaining parsers.

The baseline reproduced file-link escape, missing POM timeout and oversized-input acceptance.
A timed-out parser's fragment is discarded; daemon threads do not hold the CLI open.
Cancellation remains cooperative, and parser/library expansion can exceed the input byte size:
there is no hard heap quota. Filesystem checks cover a pre-existing hostile tree, not concurrent
same-account directory substitution. Website impact: required; graph reference updated.


## V3: Claude permissions

`ClaudeGeneratorTest` tests fresh and repeated generation; `ShippedAssetStructureTest` compares
the shipped settings to the manifest. Both require exactly the 46 current MCP grants, with no
server-wide wildcard or duplicate. Existing Read and Maven command grants are unchanged.

Actual Claude Code 2.1.316 was run with generated settings, isolated configuration, a benign
stdio MCP fixture and a loopback model API fixture. For each of camel, camel-knowledge and citrus:

| Rule/tool | Observed behavior |
| --- | --- |
| Listed tool, exact grant | Dispatched without a permission request |
| Unlisted tool, exact grants | Host permission request before dispatch; denied; zero MCP calls |
| Same unlisted tool, former wildcard restored | Dispatched without a permission request |
| Listed tool, its exact grant removed | Host permission request; denied; zero MCP calls |

All 12 cases passed. The fixture drives the actual CLI permission protocol without a remote
model account; it does not certify future Claude releases or unrelated user-level grants.
The reproducible harness is `tools/verify_claude_mcp_permissions.py`; use `--help` for paths.
Website impact: required; agent reference updated. Existing workspaces need regeneration.


## V5: generated and extracted paths

`InitWriteConfinementTest` covers shared directory ancestors, generated file leaves,
all eight agents' nested skill outputs and the positive selected-root-alias case.
`ForageCatalogConfinementTest` covers linked cache ancestors, relative/absolute version escapes
and the exact two-entry archive allowlist. `CitrusSchemaDownloaderTest` covers traversal,
cache/schema links and both generated quick-reference leaves, with an in-memory HTTP archive.
`IndexArchiveConfinementTest` covers traversal, absolute entries and Unix ZIP symlink metadata
through actual hash-verified acquisition. Existing downloader tests cover unsafe activation,
staging, version names and fallback.

Baseline probes demonstrated outside writes for init/Citrus/Forage, including deletion of an
outside directory selected by a malicious Forage version. All outside paths were test fixtures.
The fix rejects unsafe versions and pre-existing descendant links before writes/deletions.
Generated file leaves are replaced through AtomicFileWriter rather than truncated in place;
six hard-link regressions prove outside sentinels remain unchanged for init and Citrus outputs.
An explicitly chosen root alias is allowed. Extraction creates ordinary files and never
materializes ZIP symlink metadata. These checks do not provide descriptor-relative isolation
against concurrent same-account directory substitution; existing same-account exclusions apply.
Website impact: required; initialization behavior documented in the agent reference.


## V6: Knowledge

`KnowledgeInputBoundaryTest` supplies synthetic Lucene documents to actual service/tool methods.
It checks escaped free text, structured identifiers, upper/default/lower result bounds across all
call sites and catalog-only HTTP/XSLT/exec/file endpoint validation. A conventional HIGH severity
query exposed duplicate elements in `Set.of`; distinct case variants now accept HIGH/high/High.
`LuceneFallbackBoundaryTest` checks pinned failures, readable unpinned temporary fallback,
POSIX temporary-directory privacy where supported, cleanup, persistent-index preservation and
failure when no bundled index exists. Existing downloader tests cover cache permissions, hash,
transport/redirect, pin, staging and activation boundaries. Removing query escaping, increasing
the cap or disabling fallback cleanup each made the respective regression fail.

`OfflineModelsTest` forks fresh JVMs with isolated caches and real bundled embedding/reranker
resources, in both initialization orders. Both return valid inference results with zero observed
HTTP opens, JFR process starts or socket I/O. Four conflicting-environment cases fail before
native/model extraction. All six cases passed directly on Java 17, 21 and 25. Normal cases start
with deliberately conflicting system properties to check enforcement. Removing either provider's
offline call produced four blocked EC2 metadata requests. No real metadata request was sent.
An earlier probe caught JNA launching `ldconfig`; the shared initializer now suppresses implicit
platform-library discovery while preserving an explicitly configured search path.

The offline policy is process-wide DJL configuration for the bundled CPU runtime, not an OS
sandbox. DJL cache and ONNX/native temporary writes remain expected. Conflicting environment
values are rejected rather than silently enabling telemetry or GPU-native downloads.
Packaged transport verification uses `tools/verify_knowledge_runtime.py` with a local synthetic
index: stdio initialize/list-tools/first query, and explicit loopback SSE initialize/list-tools/
query without authorization. It inspects the child process's sockets and syscall trace where
available; JVM local-interface discovery is reported separately from application network.
Website impact: required; Knowledge runtime/reference updated. Published Knowledge0.0.2 is not
retroactively changed by these source fixes.


### Packaged V6 observation (2026-11-09)

The checked-in probe passed on Temurin 21.0.8+9 with runner SHA-256
`d5e8e7b1a379e75bea6f432d0538d1bc54a75a1143bbb040730f7edb6575e6a6`, with no
baseline/offline/CUDA/JNA command overrides. Both fresh JVMs initialized MCP, listed seven
tools and returned the local fixture from the first query with reranking. Default stdio had
no IP sockets, binds or listeners; explicit SSE had one 127.0.0.1 listener and accepted the
unauthenticated initialization/query. Lifetime syscall traces contained only the initial JVM
exec, no child executable and no application service connection/send. JVM netlink and UDP
port-zero interface discovery was recorded separately. The packaged runner contained all five
model/tokenizer resources and no `knowledge-index/` payload.
