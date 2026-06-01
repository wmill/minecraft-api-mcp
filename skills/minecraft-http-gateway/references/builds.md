# Build System Flows

Use `build_flow.py` for queued, auditable, replayable mutations.

## Lifecycle

```bash
uv run python skills/minecraft-http-gateway/scripts/build_flow.py create --name "test build"
uv run python skills/minecraft-http-gateway/scripts/build_flow.py add-single-block --build-id <uuid> --x 0 --y 64 --z 0 --block-name minecraft:stone
uv run python skills/minecraft-http-gateway/scripts/build_flow.py status --build-id <uuid>
uv run python skills/minecraft-http-gateway/scripts/build_flow.py audit --build-id <uuid>
uv run python skills/minecraft-http-gateway/scripts/build_flow.py execute --build-id <uuid>
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
