# Core Conventions

## Purpose

This skill is the non-MCP path for the repo's Minecraft automation interface.

## Base URL

- Default: `http://localhost:7070`
- Override with:
  - `MINECRAFT_API_BASE_URL`
  - `--base-url` on the helper scripts

## Script Execution

Run Python helpers with:

```bash
uv run python skills/minecraft-http-gateway/scripts/<script>.py ...
```

## Output Expectations

- Scripts print JSON to stdout on success.
- Scripts print an error message to stderr and exit non-zero on failure.
- Read-before-write and read-after-write is the default operating pattern.

## Important API Conventions

- Request fields use snake_case.
- World defaults to `minecraft:overworld` when omitted by the API.
- Endpoint paths begin with `/api/...`.
