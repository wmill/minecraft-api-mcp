---
name: minecraft-http-gateway
description: Use this skill when an LLM client needs to inspect or modify this Minecraft server through the repo's HTTP API and bundled helper scripts, without relying on MCP tools. Covers world reads, site surveys, area locks, queued builds with undo/redo, NBT placement with dry runs, Starlark build scripts, and the schematic catalog.
---

# Minecraft HTTP Gateway

Use this skill when the client can read files and run commands and should work through the Fabric mod HTTP API directly.

The server interface is `http://localhost:7070` by default. Two optional services sit beside it: the Starlark build service on `http://localhost:7090` and the schematic catalog on `http://localhost:7080`. Prefer the bundled scripts in `scripts/` over ad hoc HTTP calls when they cover the task.

## Workflow

1. Inspect the current world or build state first (`world_query.py survey`, `build_flow.py query-location`).
2. Reserve the area with `area_lock.py acquire` when building somewhere non-trivial, and pass the token to every write.
3. Prefer queued build tasks or NBT structures for mutations, especially multi-block or destructive changes.
4. Preview, audit, or dry-run before executing.
5. Execute the change.
6. Verify with a read call after writes, then release the lock.

Direct world writes are acceptable for small, simple actions after inspection.

## Commands

Run helpers from the repository root:

```bash
uv run python skills/minecraft-http-gateway/scripts/<script>.py ...
```

Use `--base-url` or `MINECRAFT_API_BASE_URL` when the API is not on `http://localhost:7070`. The service scripts read `STARLARK_SERVICE_URL` and `SCHEMATIC_SERVICE_URL`. Write commands read an area lock token from `--lock-id` or `MINECRAFT_AREA_LOCK_ID`.

## Scripts

- `scripts/world_query.py`
  Use for read-only inspection: API health, players, entities, blocks, heightmaps, heightmap PNG previews, block chunks, and `survey` (ground level, slope, water, cut/fill and existing builds/locks for a footprint).
- `scripts/area_lock.py`
  Use to reserve a cuboid before building: `acquire`, `list`, `renew` (optionally resize), `release`.
- `scripts/build_flow.py`
  Use for build-system workflows: create/status/tasks, add common task types, reorder/update/delete, preview, audit, execute, replay, clone, translate, undo/redo, query-location, and rail planning.
- `scripts/world_ops.py`
  Use for small direct operations: block fill/set, prefab placement, entity spawn, messages, teleport, rain-fire, and NBT structure placement (with `--dry-run`).
- `scripts/starlark.py`
  Use to write builds as Starlark scripts: docs, examples, build (compile to NBT), preview images, the shared script library (search/get/source/save), and place.
- `scripts/schematics.py`
  Use to find and place pre-made schematics: search, tags, get, preview images, NBT download, and place.
- `scripts/call_api.py`
  Use as the raw fallback for unsupported endpoints.
- `scripts/rail_debug_e2e.py`
  Use for the manual end-to-end rail planning debug loop.

## Safety Defaults

- Read before writing when coordinates or world state matter. Run `build_flow.py query-location` or `world_query.py survey` before grading or clearing land; another agent's build may be there.
- Acquire an area lock for multi-step builds. On a lock conflict (HTTP 409) do not relocate or retry without the token; report it.
- Use build queues for large fills, `minecraft:air` clears, rail planning, and multi-step builds.
- Run `build_flow.py audit` before executing non-trivial queued builds.
- Dry-run NBT placements (`--dry-run` on `place-nbt`, `starlark.py place`, `schematics.py place`) and check `overwrite.air_carved` / `replaced`: NBT placement writes air cells too.
- NBT placements are recorded as builds and can be reverted with `build_flow.py undo` (and re-applied with `redo`).
- Use preview output (`build_flow.py preview`, `world_query.py heightmap-preview`, `starlark.py image`, `schematics.py image`) when spatial layout matters.
- Verify direct writes with `world_query.py chunk` or another relevant read call.

## References

- For endpoint conventions, defaults, locks and error codes, read `references/core.md`.
- For inspection, survey and direct write flows, read `references/world-and-blocks.md`.
- For build queue, undo/redo and rail planning flows, read `references/builds.md`.
- For Starlark scripts and schematics, read `references/structures.md`.
- For raw fallback payload examples, read `references/payload-patterns.md`.
- Full contracts: `docs/openapi-minecraft.yaml`, `docs/openapi-starlark.yaml`, `docs/openapi-schematics.yaml`; feature notes in `docs/area-locks.md`, `docs/site-survey.md`, `docs/undo-and-dry-run.md`.

## Notes

- Payloads are snake_case.
- World defaults to `minecraft:overworld` when omitted by the API.
- MCP tool schemas may be useful implementation reference, but this skill should operate through HTTP scripts and raw REST calls only.
