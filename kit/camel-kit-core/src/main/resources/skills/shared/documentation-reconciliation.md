# Reconcile Current Documentation

Load this procedure only when the parent owns documentation writes: documentation-only requests, execute finalization,
direct migration/debug after verification, or Ship after successful publication. First apply
`shared/documentation-lifecycle.md` and its authority, path and decision rules. Validation is reporting-only and does
not run this procedure. Keep the validator's report as historical evidence; write the parent's final result separately.

## Reconcile and record

1. Confirm the documentation scope and dispositions for discrepancies using existing user decisions. If accepting a
   new architecture decision or changing behavior is necessary, return that decision to the appropriate design gate.
   A documentation-only request authorizes factual doc updates, not changing code or accepting unexplained drift.
2. After implementation and ADR status/link edits settle, capture the **pre-review** source inventory. Include source
   directories (so additions/deletions are detected), build/config files, tests, the ADR directory and any linked
   current-doc modules other than the document being recorded. For multiple apps include all their relevant roots.
   Keep the document being rewritten, review report and snapshot outside those roots; avoid volatile build output.
   If the project adds another source root later, enlarge the scope and review again. Do not claim coverage beyond it.

   Example for a project with `src/`, `pom.xml` and `docs/adr/` already present:

   ```bash
   {COMMAND_PREFIX} doc snapshot --project . --source src --source pom.xml --source docs/adr > .camel-kit/doc-review-input.json
   ```

   Check the command exit status before using the snapshot. If `.camel-kit/` is absent, create the validated directory.
   Use distinct snapshot/report names for concurrent work. The CLI accepts an aliased project root, but rejects
   descendant symlinks, missing inputs, Git internals and out-of-project paths. Bounds apply together: 10,000 entries,
   64 MiB per file, 512 MiB total, 1 MiB per snapshot/report/document and 2 MiB per receipt. Long paths can reach the
   snapshot byte limit before the entry limit.
   A rejected scope is unavailable evidence, never an invitation to omit a relevant source silently.
3. Rewrite the current description in place as a coherent account of the **whole inspected system**. Cover boundaries,
   applications/flows, contracts/data, error handling, security/data retention, configuration/operations, limitations
   and pending work as applicable. Preserve still-valid material and user edits. Readers must not need amendment chains
   or multiple pipeline specs to reconstruct current behavior. Link evidence and active ADRs; keep historical rationale
   in ADRs and past pipeline reports. For modular docs, update all affected modules and their index.
4. Compare the final prose to code and actual verification results, claim by claim. Write a review report (default
   `docs/camel-kit/documentation-review.md`) with: inspected source boundary, document paths, important assertions and
   code references, actual checks/results, ADR approval/supersession evidence, discrepancies/dispositions, unavailable
   evidence, reviewer and date. Do not infer a test result from file existence, a timestamp, Git HEAD or a stored flag.
   Tests requiring services/credentials or execution permission remain unavailable until authorized and run.
5. Choose `complete` only when the entire stated scope is inspected, relevant checks ran, the final description matches
   implemented behavior, and no unresolved intent/implementation discrepancy remains. Use `partial` for missing
   evidence/pending work and `failed` for demonstrated inconsistency. A source-only reconstruction can be useful while
   its runtime claims remain explicitly unverified. Record that limit as partial.
6. Finish all document/report edits and any justified `doc unstale` **before** recording, then run:

   ```bash
   {COMMAND_PREFIX} doc record docs/camel-kit/system-design.md --project . --snapshot .camel-kit/doc-review-input.json --evidence docs/camel-kit/documentation-review.md --result complete
   {COMMAND_PREFIX} doc check docs/camel-kit/system-design.md --project .
   ```

   Substitute actual adopted paths and the honest result. `record` refuses changed source inputs and writes only
   `<document>.reconciliation.json`, preserving the document byte-for-byte. Commit the document, report and receipt
   together. The snapshot is working data; the receipt includes its inventory. Do not edit a receipt manually.
   For linked modular docs, first settle all prose, then snapshot/review/record each; include the other modules as sources.
7. Interpret `reconciliation.status`: `unchanged` means the recorded completed review's document, report and declared
   source inventory still match; `stale` means changed inputs (listed in `reconciliation.changedPaths`); `unverified`
   means missing, invalid, partial/failed or unreadable evidence. A legacy stale flag also blocks currency. **Unchanged is not semantic proof or a signed
   attestation.** The reviewer remains responsible for coverage and conclusions. Any new finding overrides a previous
   positive conclusion and requires reconciliation. Neither `doc init` nor `doc unstale` manufactures review evidence.

## Documentation-only entry

For requests such as "reconcile documentation", "update the system design from these pipelines" or "document my
manual changes", perform discovery and the procedure above directly. Existing specs/reports are evidence to reconcile
with current source, not an ordered set of amendments to concatenate. Do not create or switch the active pipeline.
If only old pieces are available, produce a useful consolidated draft with explicit Unknown sections and a partial
review; never fabricate missing rationale, approval, implementation or test results.
