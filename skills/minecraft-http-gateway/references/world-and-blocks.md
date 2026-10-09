# World And Block Flows

Use `world_query.py` for read-only inspection and `world_ops.py` for small direct writes.

## Inspection

```bash
uv run python skills/minecraft-http-gateway/scripts/world_query.py test
uv run python skills/minecraft-http-gateway/scripts/world_query.py players
uv run python skills/minecraft-http-gateway/scripts/world_query.py blocks
uv run python skills/minecraft-http-gateway/scripts/world_query.py entities
uv run python skills/minecraft-http-gateway/scripts/world_query.py heightmap --x1 -16 --z1 -16 --x2 16 --z2 16
uv run python skills/minecraft-http-gateway/scripts/world_query.py chunk --start-x 0 --start-y 64 --start-z 0 --size-x 5 --size-y 5 --size-z 5
```

Heightmap requests are capped at 10,000 columns; split larger areas.

Survey a footprint before choosing a build site (read-only; chunks must already be loaded):

```bash
uv run python skills/minecraft-http-gateway/scripts/world_query.py survey --x1 100 --z1 200 --x2 139 --z2 239
```

The response gives resolved ground heights, slope, water/lava/vegetation coverage, a `grading.suggested_walking_y` with `cut_blocks`/`fill_blocks` estimates, and up to five overlapping `reservations` and `builds`. It never declares an area free: unrecorded player builds are not covered. See `docs/site-survey.md`.

Render a terrain preview:

```bash
uv run python skills/minecraft-http-gateway/scripts/world_query.py heightmap-preview \
  --x1 -32 --z1 -32 --x2 32 --z2 32 \
  --output /tmp/heightmap.png
```

## Direct Writes

Use direct writes for small, clear actions after inspection:

```bash
uv run python skills/minecraft-http-gateway/scripts/world_ops.py set-block \
  --x 0 --y 64 --z 0 --block-name minecraft:stone

uv run python skills/minecraft-http-gateway/scripts/world_ops.py fill \
  --x1 0 --y1 64 --z1 0 --x2 4 --y2 64 --z2 4 \
  --block-type minecraft:stone
```

Prefer build queues for large fills, clears with `minecraft:air`, or multi-step structures.

Direct fills are capped at 100,000 blocks. Add `--lock-id <token>` (or set `MINECRAFT_AREA_LOCK_ID`) to every write inside a reserved area.

## Prefabs And Operations

`world_ops.py` wraps direct prefab and operational endpoints:

- `door`, `stairs`, `window`, `torch`, `sign`, `ladder`
- `spawn-entity`
- `broadcast`, `message-player`
- `teleport`
- `rain-fire`
- `place-nbt`

Example:

```bash
uv run python skills/minecraft-http-gateway/scripts/world_ops.py torch \
  --x 10 --y 65 --z 10 --block-type minecraft:torch
```

## NBT Structures

`place-nbt` uploads a structure file. Placement writes the template's air cells too, so it can carve terrain or a neighbouring build. Dry-run first:

```bash
uv run python skills/minecraft-http-gateway/scripts/world_ops.py place-nbt \
  --file house.nbt --x 100 --y 64 --z 200 --rotation CLOCKWISE_90 --dry-run
```

The dry run reports `bounds`, `overwrite` (`replaced`, `air_carved`, `placed_into_air`, `replaced_by_category`, `top_replaced`), `lock_check`, and overlapping `reservations`/`builds`, without writing or loading chunks. A real placement is recorded as a build: keep the returned build id so it can be reverted with `build_flow.py undo`.

Rotation pivots on the origin: `CLOCKWISE_90` maps local `(x, z)` to world `(x0 - z, z0 + x)`, so the structure extends west of `--x`. Check the dry-run `bounds`.

For Starlark builds and catalogued schematics, use `starlark.py place` and `schematics.py place` instead; they apply the ground offset for you (see `structures.md`).
