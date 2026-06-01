---
name: minecraft-http-gateway
description: Use this skill when an LLM client needs to inspect or modify this Minecraft server through the repo's HTTP API and bundled helper scripts, without relying on MCP tools.
---

# Minecraft HTTP Gateway

Use this skill when the client can read files and run commands and should work through the Fabric mod HTTP API directly.

The server interface is `http://localhost:7070` by default. Prefer the bundled scripts in `scripts/` over ad hoc HTTP calls when they cover the task.

## Workflow

1. Inspect the current world or build state first.
2. Prefer queued build tasks for mutations, especially multi-block or destructive changes.
3. Preview or audit queued builds when available.
4. Execute the change.
5. Verify with a read call after writes.

Direct world writes are acceptable for small, simple actions after inspection.

## Commands

Run helpers from the repository root:

```bash
uv run python skills/minecraft-http-gateway/scripts/<script>.py ...
```

Use `--base-url` or `MINECRAFT_API_BASE_URL` when the API is not on `http://localhost:7070`.

## Scripts

- `scripts/world_query.py`
  Use for read-only inspection: API health, players, entities, blocks, heightmaps, heightmap PNG previews, and block chunks.
- `scripts/build_flow.py`
  Use for build-system workflows: create/status/tasks, add common task types, reorder/update/delete, preview, audit, execute, replay, query-location, and rail planning.
- `scripts/world_ops.py`
  Use for small direct operations: block fill/set, prefab placement, entity spawn, messages, teleport, rain-fire, and NBT structure placement.
- `scripts/call_api.py`
  Use as the raw fallback for unsupported endpoints.
- `scripts/rail_debug_e2e.py`
  Use for the manual end-to-end rail planning debug loop.

## Safety Defaults

- Read before writing when coordinates or world state matter.
- Use build queues for large fills, `minecraft:air` clears, rail planning, and multi-step builds.
- Run `build_flow.py audit` before executing non-trivial queued builds.
- Use preview output (`build_flow.py preview` or `world_query.py heightmap-preview`) when spatial layout matters.
- Verify direct writes with `world_query.py chunk` or another relevant read call.

## References

- For endpoint conventions and defaults, read `references/core.md`.
- For inspection and direct write flows, read `references/world-and-blocks.md`.
- For build queue and rail planning flows, read `references/builds.md`.
- For raw fallback payload examples, read `references/payload-patterns.md`.

## Notes

- Payloads are snake_case.
- World defaults to `minecraft:overworld` when omitted by the API.
- MCP tool schemas may be useful implementation reference, but this skill should operate through HTTP scripts and raw REST calls only.
