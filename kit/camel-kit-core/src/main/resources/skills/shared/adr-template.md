# Architecture Decision Record

Use the project's existing convention; otherwise create `docs/adr/NNNN-short-title.md` with this shape.
Keep it short and focused. Prepare the context and credible options before choosing the decision.

```markdown
# NNNN — Decision title

Status: proposed | accepted | superseded | deprecated
Date: YYYY-MM-DD
Decision approval: [specific user/design approval, or pending]
Supersedes: [relative ADR link, if any]
Superseded by: [relative ADR link, if any]

## Context
Neutral facts, constraints, competing needs, assumptions and uncertainty.
What problem must be resolved? Historical rationale not evidenced: Unknown.

## Options considered
Credible alternatives, each with benefits, costs and constraints. No token straw options.
Distinguish options actually considered from suggestions made during later reconstruction.

## Decision
Chosen option and reasoning against the context. Pending until approved.
Acceptance does not assert implementation; link current system docs for implementation status/evidence.

## Consequences and responses
Positive, negative and neutral effects. For each material cost/risk: mitigation or action,
owner (Unknown if not agreed), resulting obligations and evidence needed.
Confidence, assumptions to test and specific conditions that trigger reconsideration.
```

Preserve accepted reasoning when superseding; change only status/reference links in the predecessor.
Do not backfill plausible historical motives. Distinguish verified facts from retrospective proposals.

References: [Gregor Hohpe](https://www.linkedin.com/posts/ghohpe_architecturedecisionrecords-activity-7502716054968844288-KVLU),
[Michael Nygard](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions),
[Martin Fowler](https://martinfowler.com/bliki/ArchitectureDecisionRecord.html).
