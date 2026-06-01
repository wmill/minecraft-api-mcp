# Core Conventions

This skill is the HTTP-only path for Minecraft automation in this repo.

## Base URL

- Default: `http://localhost:7070`
- Override with `MINECRAFT_API_BASE_URL` or any helper's `--base-url`.

## Script Execution

Run helpers from the repository root:

```bash
uv run python skills/minecraft-http-gateway/scripts/<script>.py ...
```

## Operating Loop

1. Read current state with `world_query.py` or `build_flow.py status`.
2. Queue non-trivial mutations with `build_flow.py`.
3. Preview or audit queued builds when the layout matters.
4. Execute the build or direct operation.
5. Verify with a read call.

## Output

- Helpers print formatted JSON to stdout on success.
- PNG preview helpers write the image file and print JSON metadata.
- Helpers print errors to stderr and exit non-zero on HTTP/API failures.

## API Conventions

- HTTP paths begin with `/api/...`.
- JSON request fields are snake_case.
- World defaults to `minecraft:overworld` when omitted.
- Coordinates follow Minecraft axes: X east/west, Y elevation, Z south/north.
