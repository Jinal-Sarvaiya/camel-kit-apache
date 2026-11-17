---
name: camel-ship
description: Use when the user explicitly asks to ship or publish a Camel project via the Technology Preview Ship workflow.
user_invocable: false
---

# Camel Ship — CLI Delegate

**Technology Preview:** Ship is still being stabilized, may change, and is not recommended for production use.

Invoke `{COMMAND_PREFIX} ship` once using the invocation's Ship options. Add no defaults. If no options were supplied, invoke `{COMMAND_PREFIX} ship` without adding any.

Return the command output and whether it succeeded; after successful publication apply the documentation step below. Do not reinterpret positional input, invoke pipeline skills, maintain workflow state, or reproduce Ship orchestration in the agent; the registered CLI command owns validation, state, oversight, evidence, publication, and recovery.

## After Successful Publication

For a new run, submission or explicit resume that actually completes publication, the parent owns the separate
project documentation step in `shared/documentation-lifecycle.md`. Load
`shared/documentation-reconciliation.md` and run its documentation-only procedure against the published project,
using the controller reports/Stamp as evidence and checking actual code. This is after the controller finishes; never edit candidates, run state, native proposals,
imported artifacts or the Stamp to satisfy documentation requirements. Surface unresolved decisions instead of
accepting them from worker prose. Preserve ADR history and existing documentation conventions.

A status query or abort authorizes no documentation writes. A paused, failed or interrupted run remains pending;
report that limit. For a non-JSON fallback, require the controller's successful COMPLETED publication output before
finalizing. If completion cannot be established, report documentation pending and name
`/camel-start reconcile documentation` as the follow-up. Ship's COMPLETED state covers its controller workflow;
overall documentation completion additionally requires this recorded review. Do not repeat the review on status.
