# Build System Flows

## Build Lifecycle

Use `build_flow.py` for the common build workflow:

1. `create`
2. `add-single-block` or `add-fill`
3. `status`
4. `execute`

Examples:

```bash
uv run python skills/minecraft-http-gateway/scripts/build_flow.py create --name "test build"
uv run python skills/minecraft-http-gateway/scripts/build_flow.py status --build-id <uuid>
uv run python skills/minecraft-http-gateway/scripts/build_flow.py execute --build-id <uuid>
```

## Rail Planning

The rail planner is asynchronous.

Start a plan:

```bash
uv run python skills/minecraft-http-gateway/scripts/build_flow.py plan-rail \
  --build-id <uuid> \
  --start-x 0 --start-y 64 --start-z 0 \
  --end-x 200 --end-y 64 --end-z 200
```

Poll the planning job:

```bash
uv run python skills/minecraft-http-gateway/scripts/build_flow.py rail-status --job-id <uuid>
```

Audit the resulting build:

```bash
uv run python skills/minecraft-http-gateway/scripts/build_flow.py audit --build-id <uuid>
```

Run the full local debug loop:

```bash
uv run python skills/minecraft-http-gateway/scripts/rail_debug_e2e.py \
  --start-x 0 --start-y 64 --start-z 0 \
  --end-x 200 --end-y 64 --end-z 200
```

## When To Use Raw API

If a task type is not wrapped by `build_flow.py`, call:

- `POST /api/builds/{id}/tasks`
- `PATCH /api/builds/{id}/tasks/{taskId}`
- `DELETE /api/builds/{id}/tasks/{taskId}`
- `POST /api/builds/{id}/audit`

through `call_api.py`.
