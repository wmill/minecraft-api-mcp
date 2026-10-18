# Core Conventions

This skill is the HTTP-only path for Minecraft automation in this repo.

## Base URLs

| Service | Default | Override |
|---|---|---|
| Minecraft mod API | `http://localhost:7070` | `MINECRAFT_API_BASE_URL` or `--base-url` |
| Starlark build service (optional) | `http://localhost:7090` | `STARLARK_SERVICE_URL` or `starlark.py --base-url` |
| Schematic catalog (optional) | `http://localhost:7080` | `SCHEMATIC_SERVICE_URL` or `schematics.py --base-url` |

The optional services may be down; the Minecraft API works without them. Check with `starlark.py health` / `schematics.py health`.

## Script Execution

Run helpers from the repository root using the MCP project environment. Run `uv sync` from `mcp/` first if needed:

```bash
uv run --project mcp python skills/minecraft-http-gateway/scripts/<script>.py ...
```

Global options such as `--base-url` go before the subcommand. Helpers read process environment variables; they do not load `mcp/.env` or its `BASE_URL` setting automatically.

Compose does not publish API port 7070 to the host. Use a reachable gateway URL or a deliberate port mapping for host-side commands.

## Operating Loop

1. Read current state with `world_query.py`, `build_flow.py status`, or `build_flow.py query-location`.
2. Reserve the area with `area_lock.py acquire` for anything non-trivial.
3. Queue non-trivial mutations with `build_flow.py`, or compile a structure with `starlark.py build`.
4. Preview, audit, or dry-run when the layout matters.
5. Execute the build or direct operation, passing the lock token.
6. Verify with a read call, then `area_lock.py release`.

## Area Locks

Locks are optional reservations of an inclusive cuboid, held in Minecraft server memory.

```bash
uv run --project mcp python skills/minecraft-http-gateway/scripts/area_lock.py acquire \
  --min-x 100 --min-y 50 --min-z 200 --max-x 131 --max-y 95 --max-z 231 --label "riverside house"
export MINECRAFT_AREA_LOCK_ID=<lock_id from the response>
uv run --project mcp python skills/minecraft-http-gateway/scripts/area_lock.py renew --lock-id $MINECRAFT_AREA_LOCK_ID
uv run --project mcp python skills/minecraft-http-gateway/scripts/area_lock.py release --lock-id $MINECRAFT_AREA_LOCK_ID
```

- Writes overlapping someone else's reservation are rejected; a write carrying a token must fit entirely inside its reservation.
- Block/prefab/NBT writes and build execution/restoration take `--lock-id` (default `MINECRAFT_AREA_LOCK_ID`) and sends it as the `X-Area-Lock-Id` header: `world_ops.py` writes, `build_flow.py execute/replay/undo/redo`, and `starlark.py place` / `schematics.py place`.
- Reservations expire after 900 s idle by default. Successful writes and `renew` restart the timer; reads, previews, dry runs and queue edits do not.
- `renew` with all six bounds resizes the reservation; a failed resize keeps the old one.
- Stairs include four blocks of clearance and rotated NBT can extend to negative offsets, so reserve generously.

## Output

- Helpers print formatted JSON to stdout on success.
- PNG and NBT download helpers write the file and print JSON metadata.
- `starlark.py build` and `library-save` print the result JSON and exit 2 when the script failed to build (diagnostics included).
- Helpers print errors to stderr and exit non-zero on HTTP/API failures.

## Error Codes

- 400: validation errors, and `unloaded_chunks` (with `missing_chunks`) for surveys and dry runs. Chunks only load near players or spawn.
- 404: unknown build, artifact, or schematic.
- 409: lock conflicts, with `code` one of `area_locked` (another reservation overlaps), `outside_area_lock` (operation leaves your cuboid or world), `invalid_area_lock` (unknown or expired token). Also `undo_conflict` / `redo_conflict` and invalid build state transitions.
- 503: an optional service, or a preview render, is unavailable.

## API Conventions

- HTTP paths begin with `/api/...` on the Minecraft API.
- JSON request fields are snake_case.
- World defaults to `minecraft:overworld` when omitted.
- Coordinates follow Minecraft axes: X east (+) / west (-), Y elevation, Z south (+) / north (-).
