# Payload Patterns

Use scripts first. Use `call_api.py` only when a helper does not cover the endpoint or when debugging raw payloads.

## Raw API Call

```bash
uv run python skills/minecraft-http-gateway/scripts/call_api.py \
  post \
  /api/world/blocks/fill \
  --data '{"x1":0,"y1":64,"z1":0,"x2":4,"y2":64,"z2":4,"block_type":"minecraft:stone"}'
```

## Block Set Payload

The block array is ordered as X, then Y, then Z. Use `null` for no change.

```json
{
  "start_x": 10,
  "start_y": 64,
  "start_z": 10,
  "blocks": [
    [
      [
        {
          "block_name": "minecraft:oak_stairs",
          "block_states": {
            "facing": "south"
          }
        }
      ]
    ]
  ]
}
```

## Build Task Payload

```json
{
  "task_type": "BLOCK_FILL",
  "description": "stone pad",
  "task_data": {
    "x1": 0,
    "y1": 64,
    "z1": 0,
    "x2": 10,
    "y2": 64,
    "z2": 10,
    "block_type": "minecraft:stone",
    "notify_neighbors": false
  }
}
```

Common task types:

- `BLOCK_SET`
- `BLOCK_FILL`
- `PREFAB_DOOR`
- `PREFAB_STAIRS`
- `PREFAB_WINDOW`
- `PREFAB_TORCH`
- `PREFAB_SIGN`
- `PREFAB_LADDER`
