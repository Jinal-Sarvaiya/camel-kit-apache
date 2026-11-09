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
