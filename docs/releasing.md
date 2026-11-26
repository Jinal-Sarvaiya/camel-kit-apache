# Independent Camel Kit releases

The repository contains three Maven release targets and a separate index-data release.
Run all commands below from the repository root with `./mvnw`.

| Target | POM | Tag | Scope |
|---|---|---|---|
| Shared parent | `pom.xml` | `camel-kit-parent-X.Y.Z` | Parent POM only (`-N`) |
| Kit | `kit/pom.xml` | `camel-kit-X.Y.Z` | Kit and its five modules |
| Knowledge | `knowledge/pom.xml` | `camel-kit-knowledge-X.Y.Z` | Knowledge code; excludes index data |
| Index | `knowledge/index/src/main/resources/` | `knowledge-index-v1-YYYY.MM.DD-HHMMSS` | Validated ZIP and manifest |

Versions and release schedules are independent. A product release must reference an
already published, released `org.apache.camel:camel-kit-parent` version. A parent
release does not change either product version. A Knowledge release does not update
Kit's server pin automatically.

## Prepare a Maven release

Use JDK 21 or later for every release target; the release and snapshot workflows use JDK 21.
Kit release validation rejects snapshot, timestamped snapshot, and prerelease Camel CLI host pins.
Set `camel.cli.version` in `distribution.properties` to a tested released Aesh-based Camel host
(4.24.0 or later), synchronize the Maven mirror, and run the hosted compatibility suite before staging.
Development builds may use snapshot versions of the host and the Camel MCP runner. Before staging Kit, set
`camel.mcp.version` to the tested release in Maven Central and update `camel-mcp-version` in `docs/antora.yml`.
Verify the shipped runtime profiles with that runner. Generated integration pins are independent.

1. Start from reviewed source on a release branch. Change only the selected product
   POM version and its child parent versions (or only the root version for a parent
   release). Set its SCM tag. Keep the other product's version unchanged.
2. For a product release, set its shared parent to the intended released version.
   Confirm that this parent resolves from the public repository with an empty local
   Maven repository. Do not rely on a locally installed snapshot parent.
3. For Kit, choose the tested Knowledge runner explicitly using
   `knowledge.mcp.group-id` and `knowledge.mcp.version` in `distribution.properties`.
   Version 0.0.2 remains under `io.github.luigidemasi`; migrated Knowledge builds use
   `org.apache.camel`. Verify the exact runner before changing these pins.
   Synchronize distribution mirrors with `python3 tools/sync_distribution_mirrors.py`
   and launcher fallback versions with
   `./mvnw -B -f kit/pom.xml -N antrun:run@sync-jbang-launcher-versions`.
4. Update the product README/changelog and shared Antora docs. Run
   `./mvnw -B -Psourcecheck validate`, then
   `./mvnw -B -Plinux-ship-certification -Deval.requireVectors=true clean install`
   on Linux. The Knowledge quality gate must have a working vector leg.
5. Validate the selected target, for example
   `python3 tools/release_target.py kit --tag camel-kit-X.Y.Z`.
   Review and sign the source commit and tag; record its full SHA.
6. Dispatch **Stage Maven Release** with the target, exact tag, and expected SHA.
   It tests and signs only the selected reactor and uploads a validated Central
   staging deployment. `autoPublish` is disabled. No product release command should
   deploy the entire root reactor.
7. Prepare the source release candidate, signatures, checksums, verification and PMC
   vote following the [Camel release guide](https://camel.apache.org/manual/release-guide.html)
   and [ASF release policy](https://www.apache.org/legal/release-policy.html).
   The staging workflow is an artifact-building aid, not a complete ASF release
   process. Promote artifacts and publish release announcements only after approval.
8. Advance only the released target to its next snapshot and restore its SCM tag to
   `HEAD`. For Kit, synchronize launcher fallback versions again. Verify clean-cache
   installation of the released CLI/plugin or standalone Knowledge runner.

The nightly snapshot workflow builds the root reactor. Manual snapshot dispatch can
select just the parent, Kit, or Knowledge. Independent product builds require their
chosen shared parent to be available locally or in the configured Maven repository.

## Publish index data

Index production is maintainer-triggered and independent of server releases.

1. On a branch, run
   `./mvnw -B -pl knowledge/index -am -Prebuild-index install`.
   This updates the committed index and manifest skeleton; a normal build never
   regenerates the corpus.
2. Inspect the generated changes, run
   `./mvnw -B -pl knowledge/mcp -am -Deval.requireVectors=true test`, and submit the
   signed index update for review.
3. After merge, dispatch **Index Release** from `main` with the reviewed full SHA.
   It packages the unchanged, tested index with a SHA-256 manifest under a unique
   `knowledge-index-v1-...` tag. It does not mark data as the repository's latest
   product release and does not overwrite previous assets.

The v1 contract is Lucene 9, `granite-embedding-small-english-r2-q8`, 384 dimensions,
and manifest `formatVersion: 1`. A breaking format/model change needs a new
compatibility channel and a compatible server release. See the
[Knowledge README](../knowledge/README.md#index-distribution) for discovery, pinning,
cache fallback and legacy manifest overrides.

## Migration and Git history

Knowledge was imported as a subtree merge without rewriting its original commits.
The subsequent layout and namespace changes are separate from that import. Preserve
the merge ancestry when landing this migration: squash merging or rebasing the
import would discard the original commit identities. Historical Knowledge tags
retain their original names and targets; transfer them only after checking for
collisions. Existing release assets remain at their original repository URLs.

The migration changes Java packages to `org.apache.camel.kit` and Knowledge Maven
coordinates to `org.apache.camel`. Java consumers must update imports and Maven
dependencies; MCP clients continue to use the same protocol and tool names. Keep
legacy artifacts and download locations available for existing installations. New
index discovery becomes usable once the first compatible index release is published
in `apache/camel-kit`; until then, use an explicit legacy manifest URL or local index.
