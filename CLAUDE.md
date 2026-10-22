# Camel-Kit

@AGENTS.md

AI-agent workflows for the full Apache Camel integration lifecycle. The product is
the **skills** — Markdown instruction files that guide AI coding agents — plus a Java
CLI that installs them into user workspaces. Repo: `camel-kit`.
The standalone Knowledge MCP server lives in `knowledge/` with an independent version line.
The root POM is a shared parent and aggregator; Antora lives in root `docs/`.
Java packages use `org.apache.camel.kit`; Maven group IDs use `org.apache.camel`.

## Module map

| Module | What lives here |
|--------|----------------|
| `kit/camel-kit-main` | JBang entry point (`src/main/jbang/main/CamelKit.java`) |
| `kit/camel-kit-core` | CLI commands, config, init/trait machinery; all skills + templates resources |
| `kit/camel-kit-graph` | Project graph analysis — 9 parsers (Pom, Java, Groovy, XML, Mule, DataWeave, BizTalk, YAML, Config) |
| `kit/camel-kit-ship-resolver` | Classloader-isolated Maven Resolver for Ship evidence |
| `kit/camel-jbang-plugin-kit` | Camel JBang plugin |

| `knowledge/` | Knowledge schema, embedding, indexer, index data and standalone MCP server |

`camel-kit-dispatch/` exists on disk but is **not in the Maven reactor** — do not
build or reference it. `camel-kit-worktrees/` and `target/` are scratch.

Records are used in this codebase (`WorkflowManifest`, `InitContext`, …) and are fine.

## Skills authoring

Location: `kit/camel-kit-core/src/main/resources/skills/{skill-name}/`

Pipeline (user-invocable):
`/camel-start` → `/camel-brainstorm` or `/camel-migrate` → `/camel-plan` →
`/camel-execute` → `/camel-validate`

Utilities: `/camel-ship`, `/camel-knowledge`, `/camel-debug`

Internal (not user-invocable): `camel-design`, `camel-implement`, `camel-verify`,
`camel-test`

Rules:
- Each skill: `SKILL.md` manifest + `guides/` loaded on demand.
- **Never hardcode versions** — reference `distribution.properties` (repo root;
  single source of truth for all version pins, copied into the JAR).
- `skills/shared/` guides feed multiple skills — check every caller before editing.
- A new **user-invocable** skill must be registered in ALL 8 agent templates:
  `templates/{antigravity,bob2,claude,codex,copilot,opencode,pi,qwen}/` and added to
  `docs/commands.md`.
- Agent traits: `templates/traits/{agent}/{skill}.append.md` — no code registration
  needed. `ShippedAssetStructureTest` enforces the contract; run it after any
  skill/template/trait change.

Governance:
- `docs/constitution.md` — 8 non-negotiable rules for every generated route.
- `skills/shared/iron-laws.md` — 6 iron laws. Skill changes must not contradict them.
- Record every architectural decision, including rejected alternatives. Never reference
  internal architecture decision documents in public PRs/issues.

## Security model

- `SECURITY.md` — how to report a vulnerability (private ASF process). An agent that discovers or is handed a
  suspected vulnerability MUST NOT open a public issue, PR, discussion or mailing-list post about it — point the
  user to `SECURITY.md` and stop.
- `docs/security-model.md` — who is trusted, where the trust boundaries sit, what counts as a Camel-Kit
  vulnerability, and the closed set of triage dispositions. Use it when triaging a report or reviewing a
  security-sensitive PR. `docs/security-model.yaml` is the derived machine-readable index; regenerate it whenever
  the prose changes.
- A change that moves a boundary the model describes — a parser or input format in `camel-kit-graph`, Ship tree,
  expression, secret-scan or resolver policy, worker launch flags, generated allow-lists, hooks or guard policies,
  MCP coordinates or repositories in `distribution.properties`, or a trust statement in `skills/shared/` — must
  update `docs/security-model.md` in the same PR (its §4.12 lists the triggers).

## Graph module

- New parsers must be registered in the parser registry.
- Parser test data: `src/test/resources/{platform-name}/`.

## Definition of done

Work is finished only when all of these hold:

1. `./mvnw test -B` passes in every touched module; `./mvnw clean install -B` from
   root when changes span modules or touch skills/templates/`distribution.properties`.
2. `./mvnw -Psourcecheck validate -B` passes (CI format gate).
3. Skill/template/trait change: `ShippedAssetStructureTest` passes.
4. New user-invocable skill: registered in all 8 agent templates + `docs/commands.md`.
5. No hardcoded versions where `distribution.properties` should be referenced.
6. `docs/` updated as needed; `CHANGELOG.md` for significant changes.
7. Architectural decision documentation is updated as required.
8. Independent releases follow `docs/releasing.md`; never deploy the whole reactor for a product release.
9. Nothing was skipped, disabled, or weakened to get green — if a check fails, report
   the output rather than claiming done.
9. Security-relevant change (see §4.12 of `docs/security-model.md`): the security model and its
   `docs/security-model.yaml` sidecar are updated.
