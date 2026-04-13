# Payload Patterns

## Raw API Call

```bash
uv run python skills/minecraft-http-gateway/scripts/call_api.py \
  post \
  /api/world/blocks/fill \
  --data '{"x1":0,"y1":64,"z1":0,"x2":4,"y2":64,"z2":4,"block_type":"minecraft:stone"}'
```

## Add A Single Block Build Task

```bash
uv run python skills/minecraft-http-gateway/scripts/build_flow.py add-single-block \
  --build-id <uuid> \
  --x 10 --y 64 --z 10 \
  --block-name minecraft:stone
```

## Add A Fill Build Task

```bash
uv run python skills/minecraft-http-gateway/scripts/build_flow.py add-fill \
  --build-id <uuid> \
  --x1 0 --y1 64 --z1 0 \
  --x2 10 --y2 64 --z2 10 \
  --block-type minecraft:stone
```

## Read A Heightmap

```bash
uv run python skills/minecraft-http-gateway/scripts/world_query.py heightmap \
  --x1 -32 --z1 -32 --x2 32 --z2 32
```
