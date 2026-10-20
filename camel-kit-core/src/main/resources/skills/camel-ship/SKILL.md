---
name: camel-ship
description: Use when the user explicitly asks to ship or publish a Camel project via the Technology Preview Ship workflow.
user_invocable: false
---

# Camel Ship — CLI Delegate

**Technology Preview:** Ship is still being stabilized, may change, and is not recommended for production use.

Invoke `{COMMAND_PREFIX} ship` once using the invocation's Ship options. Add no defaults. If no options were supplied, invoke `{COMMAND_PREFIX} ship` without adding any.

Return the command output and whether it succeeded. Do not reinterpret positional input, invoke pipeline skills, maintain workflow state, or reproduce Ship orchestration in the agent; the registered CLI command owns validation, state, oversight, evidence, publication, and recovery.
