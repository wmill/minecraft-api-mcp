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
