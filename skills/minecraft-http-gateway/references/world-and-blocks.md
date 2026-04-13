# World And Block Flows

## Inspection

Use `world_query.py` for:

- online players
- available entities
- available blocks
- heightmaps
- block chunks

Examples:

```bash
uv run python skills/minecraft-http-gateway/scripts/world_query.py players
uv run python skills/minecraft-http-gateway/scripts/world_query.py heightmap --x1 0 --z1 0 --x2 32 --z2 32
uv run python skills/minecraft-http-gateway/scripts/world_query.py chunk --start-x 0 --start-y 64 --start-z 0 --size-x 8 --size-y 8 --size-z 8
```

## Raw Block Writes

For endpoints not wrapped by `world_query.py`, use `call_api.py`.

Common write endpoints:

- `POST /api/world/blocks/set`
- `POST /api/world/blocks/fill`
- `POST /api/world/entities/spawn`
- `POST /api/world/prefabs/...`

## Prefabs

Prefab endpoints are available through the HTTP API even though this skill does not wrap all of them with a dedicated script. Use `call_api.py` for prefabs when needed.
