---
name: minecraft-http-gateway
description: Use this skill when an LLM client cannot use MCP tools but still needs to inspect the Minecraft world, manipulate blocks, use prefabs, or drive the build system through the repo's HTTP API and bundled helper scripts.
---

# Minecraft HTTP Gateway

Use this skill when the client can read files and run commands, but cannot connect to the MCP server.

The server interface is the Fabric mod HTTP API on `http://localhost:7070` by default. Prefer the bundled scripts in `scripts/` over ad hoc HTTP calls when they cover the task.

## Workflow

1. Inspect the current world or build state first.
2. Choose the narrowest helper script that fits the task.
3. Run scripts with `uv run python ...`.
4. Verify the result with a read call after writes.

## Scripts

- `scripts/call_api.py`
  Use for raw GET/POST/PATCH/DELETE access to any HTTP endpoint.
- `scripts/world_query.py`
  Use for common read operations such as players, entities, heightmaps, block lists, and block chunks.
- `scripts/build_flow.py`
  Use for build-system operations such as create build, add common tasks, execute builds, get build status, and start or poll rail planning.

## Base URL

- Default base URL is `http://localhost:7070`.
- Override with `MINECRAFT_API_BASE_URL=...` or `--base-url`.

## References

- For endpoint conventions and defaults, read `references/core.md`.
- For inspection and block/prefab flows, read `references/world-and-blocks.md`.
- For build queue and rail planning flows, read `references/builds.md`.
- For payload shapes and command examples, read `references/payload-patterns.md`.

## Notes

- Payloads are snake_case.
- Prefer helper scripts for repeated workflows because they normalize arguments and output.
- For unsupported operations, fall back to `scripts/call_api.py` with the documented endpoint path.
