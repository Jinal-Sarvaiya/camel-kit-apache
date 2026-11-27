For the legacy flow inventory, apply `shared/graph-availability.md` and use the installed `{COMMAND_PREFIX}`.
Run `graph generate --require-parser legacy-flow --project-dir SOURCE --output GRAPH` as separate argv elements.
Use the canonical source directory selected for validation as SOURCE and the graph destination authorized for this
validation as GRAPH. If either is missing, ask for it and leave the inventory incomplete.
Run the command even if a previous graph exists. On failure, report the diagnostic and leave the inventory incomplete.
Run `graph analyze legacy --graph-file GRAPH` with the same installed command prefix and authorized GRAPH.
If analysis fails, report the diagnostic and leave the inventory incomplete. On success report the adapter summary,
or zero legacy flows when the adapter returns `matched: false`.
