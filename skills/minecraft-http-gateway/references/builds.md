# Build System Flows

Use `build_flow.py` for queued, auditable, replayable mutations.

## Lifecycle

```bash
uv run python skills/minecraft-http-gateway/scripts/build_flow.py create --name "test build"
uv run python skills/minecraft-http-gateway/scripts/build_flow.py add-single-block --build-id <uuid> --x 0 --y 64 --z 0 --block-name minecraft:stone
uv run python skills/minecraft-http-gateway/scripts/build_flow.py status --build-id <uuid>
uv run python skills/minecraft-http-gateway/scripts/build_flow.py audit --build-id <uuid>
uv run python skills/minecraft-http-gateway/scripts/build_flow.py execute --build-id <uuid> --lock-id <token>
```

`execute` and `replay` carry the lock token into each task; the check happens when a task writes. On a lock failure the build becomes `FAILED` with completed tasks left in place. After resolving the reservation, `execute` retries failed and pending tasks, skipping completed ones; `replay` resets and re-runs every replayable task.

Before building, check what is already recorded in the area:

```bash
uv run python skills/minecraft-http-gateway/scripts/build_flow.py query-location \
  --min-x 100 --min-y 0 --min-z 200 --max-x 140 --max-y 320 --max-z 240
```

## Common Task Commands

- `add-single-block`
- `add-block-set`
- `add-fill`
- `add-door`
- `add-stairs`
- `add-window`
- `add-torch`
- `add-sign`
- `add-ladder`

Use `--task-order` on add commands to insert at a specific queue position.

## Copy And Move

```bash
uv run python skills/minecraft-http-gateway/scripts/build_flow.py clone --build-id <uuid>
uv run python skills/minecraft-http-gateway/scripts/build_flow.py translate --build-id <uuid> --dx 40 --dz -10
```

`clone` creates a new queued build with copies of the source's tasks (NBT placements are skipped) and prints `new_build_id`. Translate the clone, then execute it to stamp a second copy. `translate` shifts the coordinates of a build that has not run yet; completed builds or builds with completed tasks are rejected with 409, so clone first.

## Undo And Redo

NBT placements (`world_ops.py place-nbt`, `starlark.py place`, `schematics.py place`) snapshot their cuboid first. If the placement response says `undo_available: true`:

```bash
uv run python skills/minecraft-http-gateway/scripts/build_flow.py undo --build-id <uuid> --lock-id <token>
uv run python skills/minecraft-http-gateway/scripts/build_flow.py redo --build-id <uuid> --lock-id <token>
```

- Undo restores the region and marks the build `REVERTED`; redo restores the placed state and marks it `COMPLETED`. Both are repeatable in alternation; a duplicate returns 409.
- If later recorded builds overlap, undo/redo returns 409 `undo_conflict` / `redo_conflict` listing them. `--force` restores anyway and erases them. Force does not bypass area locks.
- Entities are not removed or respawned. Queued task builds cannot be undone; `replay` is not redo.
- A timeout (504) is an unknown outcome: inspect the build status and the world before retrying. See `docs/undo-and-dry-run.md`.

## Queue Maintenance

```bash
uv run python skills/minecraft-http-gateway/scripts/build_flow.py tasks --build-id <uuid>
uv run python skills/minecraft-http-gateway/scripts/build_flow.py update-task --build-id <uuid> --task-id <task> --description "new text"
uv run python skills/minecraft-http-gateway/scripts/build_flow.py delete-task --build-id <uuid> --task-id <task>
uv run python skills/minecraft-http-gateway/scripts/build_flow.py reorder --build-id <uuid> --task-id <first> --task-id <second>
```

## Preview

```bash
uv run python skills/minecraft-http-gateway/scripts/build_flow.py preview \
  --build-id <uuid> \
  --terrain-margin 3 \
  --output /tmp/build-preview.png
```

## Rail Planning

The rail planner is asynchronous:

```bash
uv run python skills/minecraft-http-gateway/scripts/build_flow.py plan-rail \
  --build-id <uuid> \
  --start-x 0 --start-y 64 --start-z 0 \
  --end-x 200 --end-y 64 --end-z 200

uv run python skills/minecraft-http-gateway/scripts/build_flow.py rail-status --job-id <uuid>
uv run python skills/minecraft-http-gateway/scripts/build_flow.py audit --build-id <uuid>
```

For a full local rail debug loop, use `scripts/rail_debug_e2e.py`.
