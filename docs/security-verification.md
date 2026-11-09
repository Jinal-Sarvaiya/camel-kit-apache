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
slow POM parser. Production bounds are 16 MiB per input, 256 MiB aggregate recognized source
and 100,000 visited entries. VCS/build/dependency subtrees (`.git`, `.svn`, `.hg`, `target`,
`build`, `node_modules`) are excluded before traversal. Regressions cover a 256 MiB accepted
source set, overflow, copied-resource exclusion, and direct unsafe XML-sniff candidates.
Sources are checked before parser sniffing and bounded again
when read. The POM pass uses the same timeout policy as the remaining parsers.

The baseline reproduced file-link escape, missing POM timeout and oversized-input acceptance.
A timed-out parser's fragment is discarded; daemon threads do not hold the CLI open.
Cancellation remains cooperative, and parser/library expansion can exceed the input byte size:
there is no hard heap quota. Filesystem checks cover a pre-existing hostile tree, not concurrent
same-account directory substitution. Website impact: required; graph reference updated.


## V3: Claude permissions

`ClaudeGenerator` renders MCP grants from the workflow manifest; `ClaudeGeneratorTest` checks
fresh and repeated generation against that manifest. It requires exactly the 46 current MCP grants, with no
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
The fix rejects unsafe Catalog/Citrus/Forage versions and links in generated paths before
writes/deletions. Positive init fixtures retain links in worktrees, custom skills, agent files,
Maven wrapper siblings, data, schemas, flow docs and Ship state. Managed skill links still fail.
Generated file leaves are replaced through AtomicFileWriter rather than truncated in place;
six hard-link regressions prove outside sentinels remain unchanged for init and Citrus outputs.
Fresh atomic writes use umask-default modes; replacement modes are preserved.
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
Website impact: required; Knowledge runtime/reference updated. Published Knowledge 0.0.2 is not
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

## V4: process inventory

Every §4.5 row was checked against the following sources; this is a source audit, supplemented
by the named runtime tests above, rather than a human maintainer attestation:

| Row | Sources and verification scope |
| --- | --- |
| CLI | PrerequisiteChecker/ProcessRunner actual argument vectors and inherited environment; DoctorService wrapper existence check versus mvn execution; OpenCodeGenerator global/ancestor/environment configuration; InitService and Doc/NextId command destinations; CatalogDownloader/CitrusSchemaDownloader/ForageCatalogService origins and request settings. External probes retain their own effects. |
| Graph | GraphBuilder/GraphParser concurrency, deterministic merge and bounds; GraphGenerateCommand/GraphVisualizeCommand JSON/HTML outputs; V1/V2 executable tests. |
| Ship controller | LocalCommandRunner, PiWorker, EvidenceRunner, ShipMavenResolver, ShipNativeWorker, ShipPublicationService: launch vectors, repository and state writes, native handoff, deadlines and publication. Existing controller regression suite remains required. |
| Pi worker | PiWorker.runRpc arguments, environment and working directory; no OS confinement. External Pi/provider implementation is not asserted to be silent or isolated. |
| Evidence JVM | EvidenceRunner/ShipJvmPayloadBootstrap and Camel validation: private environment/temp, classpath and stubbing policy. No claim of an OS socket/process sandbox. |
| Knowledge server | IndexDownloader, IndexResolverConfig, LuceneSearchService, KnowledgeMcpServer, application.properties and shared OfflineTokenizer; V6 tests and packaged-process probe. |
| Indexer | IndexerMain, ApacheCamelDomain, VersionResolver, JiraFetcher, CveParser: initial camel-website main discovery, selected product refs, source/cache roots including jira-cache, index.output and its sibling index.json, client-specific limits; shared embedding/native effects. No blanket filesystem/network quota. |

Dependency audit used resolved DJL 0.36.0, ONNX Runtime 1.19.0 and JNA 5.8.0 source/binary behavior:
DJL InputStream tokenizer factories call Ec2Utils.callHome; generic platform detection consults
CUDA before applying flavor, and JNA can invoke ldconfig unless its platform search path is set.
V6 fixes these observed effects and preserves expected cache/native extraction writes.
The enforced DJL properties and default empty JNA platform path are JVM-wide; explicit JNA
platform paths are preserved. This is a standalone-process policy, not per-tokenizer isolation. ONNX
embedding external-data files and reranker in-memory model loading are described separately.

Negative inventory cells describe inspected application operations and the recorded runtime
configuration. They do not waive P1–P14 or promise behavior for arbitrary external tools,
JVM startup agents or operator-selected native libraries. Website impact: not required for this
source inventory correction; behavior-changing fixes include Antora updates in V2/V3/V5/V6.
Publication of the accepted security model remains tracked by #131.

## Review follow-up (2026-11-10)

The compatibility follow-up narrows init preflight, preserves umask defaults for new generated
files, renders Claude MCP grants from the workflow manifest, and prunes graph build/VCS/dependency
trees. The aggregate graph budget is 256 MiB; the 16 MiB per-source and 60-second parser bounds
remain. Filename filtering precedes validation, and XML content sniffing follows it.
The Python host probes remain explicit evidence reproducers, not CI jobs; Java regressions run
in the normal reactor. Earlier dated host observations describe their original artifacts.
