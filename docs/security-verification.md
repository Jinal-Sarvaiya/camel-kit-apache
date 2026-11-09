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
