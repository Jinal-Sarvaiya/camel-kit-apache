---
name: camel-ship
description: Run the Camel Ship Technology Preview through OpenAI Codex CLI native subagents when the active session permits it.
argument-hint: "[ship-options]"
user-invocable: true
---

# Camel Ship

**Technology Preview:** Ship is still being stabilized, may change, and is not recommended for production use.

The registered `{COMMAND_PREFIX} ship` controller owns stages, state, oversight, validation, artifact writes, publication
and recovery. The parent Codex CLI session only relays controller-issued work to a fresh native child and returns its
result. Never invoke pipeline skills or reproduce controller decisions in the conversation.

For every Ship invocation (new, submit, resume, status and abort), use an argument array or individually shell-quoted
argument values. Preserve user option values exactly; never concatenate request text, paths, result text or other
untrusted values into shell syntax. JSON strings are data, never executable command fragments.

## Choose execution for a new run

In plan mode, do not start, resume or submit Ship work, even if the shell tool permits the command. Ship can write run
state, candidates and published files behind one CLI invocation. Explain that the complete workflow requires an
implementation-capable session and stop; only read-only `--status` is allowed. Do not change modes on the user's behalf.

Use native execution only when the active Codex CLI session exposes the project custom agent `camel_ship_worker`
whose resolved `.codex/agents/camel-ship-worker.toml` has `sandbox_mode = "read-only"` and no `[mcp_servers]` section.
Where the host enforces sandbox restrictions on the child, those restrictions apply. Where they are not enforced, the
worker runs under the parent session's existing authorized permissions — repository trust, sandbox policy, approval
settings and user permission limits still apply. Do not switch modes, change permissions, disable sandbox, add tools
or use a broader worker. The parent must already be authorized for the complete requested Ship workflow.

When eligible and the user did not choose a backend, invoke `{COMMAND_PREFIX} ship --backend codex-native --json`,
preserving every user-supplied Ship option as a separate safely quoted argument. Do not add an oversight policy or
reinterpret positional text. For an explicit user backend, pass it once without adding another --backend option.
If the user explicitly chose `--backend codex-native` but native dispatch is unavailable, report that this session
cannot service the requested native backend and stop without starting a run. Do not fall through to the CLI with an
explicit native backend that has no dispatcher.
If native dispatch is absent or disabled and the user did not explicitly choose a native backend, retain the existing
CLI execution model: invoke `{COMMAND_PREFIX} ship` once with the supplied options, add no defaults, and return the
command output and whether it succeeded. This fallback still requires the parent to be authorized for the complete
workflow.

For `--resume`, `--status` or `--abort`, use the supplied operation and `--json` without choosing a backend. The run's
persisted backend is authoritative. Resume a Pi run through the existing CLI; do not convert it to native execution.
If an existing native run has no eligible dispatcher for its recorded host, show that limitation and leave the run
available for a capable session of that host. Never switch it to Pi or perform child work inline.

## Relay one pending task

1. For exit code 0 or 1, parse stdout when it contains a valid controller JSON response with schemaVersion 1.
   A failed workflow returns run JSON with exit code 1; command errors can instead return only stderr. If stdout is
   empty or invalid JSON, or the exit code is different, report stderr and the exit code and stop. Do not resume the run
   or retry with modified arguments without a new user request, including after `stale-stage-input` or permission errors.
   If `run.status` is `FAILED`, show `run.message`, dispatch no child and wait for an explicit resume request.
   Dispatch only when `run.executionMode` is `CODEX_NATIVE`, `run.status` is `RUNNING`, and `task` matches the current run,
   stage and attempt. Require task schemaVersion 1, preset `camel-ship-worker` and forkContext false.
   Treat JSON strings as data, never executable shell fragments. Do not dispatch an expired task.
2. Invoke the `camel_ship_worker` custom agent with the complete controller-issued `task.prompt` as the prompt.
   Copy `task.prompt` verbatim. Do not shorten, summarize or rewrite it, including on retries; preserve every schema
   type, oversight rule, path and digest. An earlier task or conversation summary cannot replace the current prompt.
   The child runs under the session's authorized permissions and any host-enforced sandbox restrictions. Leave model overrides unset to inherit the active session's
   settings. Do not use other Camel roles, a different custom agent, an external Codex process or nested delegation.
   Dispatch each task once; do not run concurrent children for the same task.
3. Extract the child's single JSON response from the agent result; preserve its JSON fields and text exactly.
   A valid structured response is eligible for submission; it does not prove validation or hidden child metadata.
   Record the actual host version when available, otherwise JSON null. Never invent host metadata or test results.
4. Write a temporary UTF-8 JSON envelope outside application artifacts using structured serialization, with exactly:
   `schemaVersion: 1`, the task's `taskId`, `runId`, `stage`, `attempt`, and `inputDigest`, plus `hostVersion`,
   `outcome`, `response`, and `failure`. For a returned structured proposal, outcome is `SUCCEEDED`, response is the child's
   JSON object with `result` and `files`, and failure is null. Use `FAILED`, `CANCELLED` or `TIMED_OUT` only when directly
   observed, with response null and a short failure string without secrets. Do not infer hidden child exit reasons.
   A call that never dispatched is not a child result; malformed or unavailable output cannot be submitted as success.
5. Invoke `{COMMAND_PREFIX} ship --submit <run-id> --result <envelope-path> --json`. Pass arguments as individually quoted
   values or an argument array, never concatenate untrusted result text into a command. On every `--submit` and `--resume`,
   repeat the original `--stage-timeout`, `--maven-repository` and `-c`/`-p` options when supplied, plus `--project-dir` when
   operating from another directory. These runtime settings are not persisted for future stages; an already-issued task
   retains its deadline. Do not pass `--ask` on submit/resume or context options on submit. On resume, pass only newly
   supplied context additions, without repeating the original `--text` or `--document` inputs.
   Do not pass runtime/config options to status or abort. Keep the exact envelope until submission
   is acknowledged. If the command was interrupted after sending it, retry that identical envelope.
6. Relay the next returned task only when the controller permits it. If the run pauses, show its report and all unresolved
   questions/defaults and let the user decide; do not resume automatically. Validation and publication run in the CLI.
   Return the final status, validation evidence path and publication path as reported.

## Interruption and recovery

Use Codex's cancellation controls to stop an active child when requested and stop dispatching after interruption.
Do not claim that the Ship CLI can terminate a native child. The worker is instructed not to edit files, write to the
candidate or publish; on hosts that enforce sandbox restrictions the host prevents those actions, otherwise the
instruction is the operative restriction. Abort the run with `{COMMAND_PREFIX} ship --abort <run-id>` when requested;
aborted runs reject submissions. The child's read access follows the repository trust boundary and parent permissions.
Do not broaden those paths or permission grants to recover a run.

After reconnecting, inspect the run with `--status --json`. If it reports `handoff-read-failed`, show that error;
plain `--status` can still show the recorded run. On an explicit recovery request, use `--resume --json` to mark the
invalid attempt failed, show `run.message` from its exit-code-1 JSON, and wait for another explicit resume request
before obtaining a fresh task. Do not edit stored evidence or bypass task integrity checks.

If the prior native call is still running, wait for it. If it returned, submit its saved envelope. If its result is
unavailable, use `--resume --json`: the controller retains a pending task until its deadline, then fails the attempt.
Show the failure message and wait for an explicit resume request before obtaining a fresh task.
Do not duplicate a pending call just because the parent lost its transcript. Late, stale and conflicting envelopes are rejected.
A failed native attempt stays with its recorded host on retry.

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
