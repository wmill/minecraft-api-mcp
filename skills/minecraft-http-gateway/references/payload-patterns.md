# Payload Patterns

Use scripts first. Use `call_api.py` only when a helper does not cover the endpoint or when debugging raw payloads.

## Raw API Call

```bash
uv run python skills/minecraft-http-gateway/scripts/call_api.py \
  post \
  /api/world/blocks/fill \
  --data '{"x1":0,"y1":64,"z1":0,"x2":4,"y2":64,"z2":4,"block_type":"minecraft:stone"}'
```

Writes inside a reserved area need the token: add `--lock-id <token>` (sent as `X-Area-Lock-Id`) or set `MINECRAFT_AREA_LOCK_ID`. `--base-url` also works for the Starlark (7090) and schematic (7080) services, whose paths have no `/api` prefix.

## Area Lock Payload

```json
{
  "world": "minecraft:overworld",
  "label": "house beside the river",
  "bounds": {"min_x": 100, "min_y": 50, "min_z": 200, "max_x": 131, "max_y": 95, "max_z": 231}
}
```

`POST /api/area-locks` returns `lock_id`. `PATCH /api/area-locks/{lock_id}` with `{}` renews and with `{"bounds": {...}}` resizes. `DELETE` releases it.

## Site Survey Payload

`POST /api/world/blocks/survey`, at most 10,000 columns, corners inclusive:

```json
{"x1": 100, "z1": 200, "x2": 139, "z2": 239, "world": "minecraft:overworld"}
```

## NBT Placement Form

`POST /api/world/structure/place` is `multipart/form-data`: file field `nbt_file`, plus `x`, `y`, `z`, `rotation` (`NONE`, `CLOCKWISE_90`, `CLOCKWISE_180`, `COUNTERCLOCKWISE_90`), `include_entities`, `world`, and `dry_run=true` for a report without writing. `call_api.py` cannot send multipart; use `world_ops.py place-nbt`.

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

Rail planning queues `RAIL_SURFACE_SEGMENT`, `RAIL_BRIDGE_SEGMENT` and `RAIL_TUNNEL_SEGMENT` tasks; create those with `build_flow.py plan-rail` rather than by hand.

## Undo / Redo Payload

`POST /api/builds/{id}/undo` or `/redo` with an optional body `{"force": true}` to restore over later overlapping builds.

## Starlark Build Payload

`POST /build` on the Starlark service (port 7090):

```json
{"source": "def build():\n    ...", "entry": "build", "props": {"height": 14}, "root_size": [9, 20, 9]}
```
