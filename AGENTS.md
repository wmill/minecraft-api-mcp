# Repository Guidelines

- Run Python commands from the relevant Python project with `uv run` so the correct environment is used.
- This repository combines a Minecraft 1.21.7 Fabric mod, a Javalin REST API on port 7070, PostgreSQL-backed build task persistence, a Python MCP server, and optional schematic and Starlark build services.
- Python projects have independent `pyproject.toml`/`uv.lock` files. Run `uv sync` and `uv run ...` from the project being changed rather than from the repository root.
- MCP and schematic services require Python 3.12+; the Starlark service requires Python 3.13+. Java targets version 21.

## Project Structure & Module Organization
- `src/main/java/ca/waltermiller/mcpapi`: Core Fabric mod, `APIServer`, endpoint classes, database layer, and build-task domain.
- `src/main/java/ca/waltermiller/mcpapi/endpoints`: REST API endpoints. Keep route registration thin and put reusable logic into core/helper classes where practical.
- `src/main/java/ca/waltermiller/mcpapi/buildtask`: Build orchestration models, repositories, and services, including rail planning and location queries.
- `src/main/java/ca/waltermiller/mcpapi/database`: HikariCP/PostgreSQL configuration, schema creation, and connection management.
- `src/main/java/ca/waltermiller/mcpapi/preview`: Isometric preview grids, palettes, terrain adapters, and rendering logic.
- `src/main/java/ca/waltermiller/mcpapi/arealock`: Area reservations and write authorization; `survey/`: site suitability and occupancy queries; `snapshot/`: NBT snapshots and undo/redo restoration.
- `src/client/java/ca/waltermiller/mcpapi`: Optional client entrypoint hooks and client mixins.
- `src/main/resources`: `fabric.mod.json`, mod assets, and resource metadata.
- `src/test/java/ca/waltermiller/mcpapi`: JUnit 5 tests for endpoint cores, build-task logic, database config/manager, and utilities.
- `mcp/`: Python MCP server package (`minecraft_mcp`) that wraps the REST API and optional services as MCP tools; supports stdio, SSE, and streamable HTTP transports.
- `schematic-service/`: Optional FastAPI schematic catalog/search service on port 7080. Its local catalog, converted NBT, and image metadata live in the git-ignored `schematic-service-data/` directory.
- `starlark-service/`: Optional FastAPI build/cache, saved script library, and artifact preview service on port 7090. Compiler `starlark-to-nbt/` and renderer `nbt-image-gen/` are git submodules installed as editable dependencies. Follow the compiler's nested `AGENTS.md` for compiler or component-library changes. Cached artifacts live in git-ignored `starlark-service-data/`.
- `starlark-library/`: Git-tracked saved scripts, with `<name>/meta.json` and immutable `v<N>.star` versions. Never edit or delete published versions; save a new version because imports and cached artifacts depend on their contents.
- `docs/`: OpenAPI specifications and guides for area locks, site surveys, and undo/redo. Keep affected API documentation synchronized with behavior changes.
- `scripts/`: PostgreSQL snapshot helpers and the disposable-server undo/redo regression harness.
- `skills/minecraft-http-gateway`: Local Codex skill and helper scripts for using the Minecraft HTTP API.
- `nbt-gen/`: Generated `.nbt` structures and generation helpers; avoid committing large generated binaries unless required.
- `bruno/`: Saved HTTP requests for manually exercising the Minecraft, schematic, and Starlark APIs.
- Root Docker files and `docker-compose.yml`: Minecraft server image, MCP image, PostgreSQL, nginx gateway, and certbot configuration.

## Build, Test, and Development Commands
- `./download-fabric-server.sh` or `./download-fabric-api-alternative.sh`: Fetch the Fabric server launcher and Fabric API jars when needed.
- `./gradlew build`: Compile, run tests, Loom processing, and produce mod jars under `build/libs`.
- `./gradlew compileJava`: Fast Java compile check.
- `./gradlew test`: Run the JUnit 5 test suite. Use `./gradlew test --rerun-tasks` when validating from a clean test run.
- `./gradlew remapJar`: Build the fat jar and remap it for distribution; this task already depends on `shadowJar`.
- `./gradlew runServer`: Start the dev Minecraft server with the mod loaded. `./gradlew runClient` starts a client.
- `docker compose up -d postgres`: Start the local PostgreSQL dependency used by build-task persistence.
- `docker compose up -d`: Start the composed services. The compose stack includes PostgreSQL, Minecraft, MCP, nginx, and certbot.
- `docker compose --profile schematics up -d elasticsearch schematic-service`: Start schematic search and its Elasticsearch dependency.
- `docker compose --profile starlark up -d starlark-service`: Start the Starlark build service.
- Gradle Docker tasks: `./gradlew dockerBuild`, `dockerRun`, `dockerLogs`, `dockerStop`, and `dockerCompose`.
- MCP server: from `mcp/`, run `uv sync`, then `uv run minecraft-mcp` for stdio. For the container's transport locally, use `uv run minecraft-mcp --transport streamable-http --stateless --host 127.0.0.1 --port 3737` (endpoint `/mcp`). SSE remains available with `--transport sse --port 3000`.
- Schematic service: from `schematic-service/`, run `uv sync`, then `uv run schematic-service` or `uv run uvicorn schematic_service.app:app --host 0.0.0.0 --port 7080`.
- Starlark service: initialize both submodules from the repository root with `git submodule update --init --recursive`, then from `starlark-service/` run `uv sync`, `uv run starlark-service`, and `uv run pytest`.
- Starlark compiler/library: from `starlark-service/starlark-to-nbt/`, run `uv sync --dev` and `uv run pytest`; use `./scripts/rebuild_examples.sh` only when example artifacts need regeneration.
- Root integration smoke test: with the API running at `localhost:7070`, run `uv run ../test_api.py` from `mcp/` to use its dependencies. This script can spawn entities near online players; use a test world.
- Python MCP tests live in `mcp/`; run `uv run pytest` from that directory or target a focused file such as `uv run pytest test_starlark_tools.py`.
- Schematic tests: from `schematic-service/`, run `uv sync` and `uv run --with pytest pytest`; pytest is not declared in that project's dependencies.
- PostgreSQL backups: run `./scripts/pg_dump_snapshot.sh` from the root. `scripts/pg_restore_snapshot.sh` restores a dump; inspect its options before replacing database state. Dumps default to git-ignored `snapshots/postgres/`.

## Coding Style & Naming Conventions
- Java 21; 4-space indentation; keep files ASCII unless the file already requires otherwise.
- Java packages stay under `ca.waltermiller.mcpapi...`; classes use PascalCase, methods/fields camelCase, constants UPPER_SNAKE.
- Favor Fabric logging through `McpApiMod.LOGGER` or the logger passed into the local service.
- All Minecraft world reads/writes that must run on the server thread should use `server.execute()` or the established local endpoint pattern.
- Keep endpoint handlers small. Prefer service/core classes for validation, coordinate math, build orchestration, and other testable behavior.
- JSON payloads should follow snake_case in both the HTTP API and the MCP server.
- Use Java records/DTOs for structured request and response payloads when that matches existing endpoint style.
- Database access should stay behind repository/service classes and use `DatabaseManager` rather than ad hoc connections.
- Keep optional-service HTTP access behind the client modules in `mcp/minecraft_mcp/client`; keep MCP tool schemas, registry entries, and handlers synchronized.
- Preserve area-lock authorization in world mutation paths. Follow `GuardedPlacement` and existing endpoint patterns; `force` on undo/redo must not bypass reservations. See `docs/area-locks.md`.
- NBT dry runs must remain read-only: no chunk loading/generation or lock renewal. Snapshot capture, restoration, and build-status persistence must retain their server-thread and locking guarantees. Preserve undo/redo snapshots and pending recovery markers; see `docs/undo-and-dry-run.md`.
- In Starlark geometry, coordinates use `+X` east, `+Y` up, and `+Z` south, and region maxima are exclusive. Components should draw within `min_size`; preserve the compiler's structure/carve/fixture phase ordering.

## Testing Guidelines
- Add focused JUnit tests under `src/test/java/ca/waltermiller/mcpapi`, mirroring the production package, and name tests `*Test`.
- Prefer testing pure logic: coordinate calculations, validation, payload transformations, repository/service behavior, and endpoint core classes.
- Avoid mocking heavy Minecraft runtime classes such as `MinecraftServer` and `ServerWorld` unless an existing test pattern already handles it.
- Use AssertJ/JUnit 5 patterns already present in the test suite.
- Database-related tests may require local PostgreSQL; `docker compose up -d postgres` starts the expected service.
- For manual API checks, document the server state, world seed or relevant coordinates, and sample requests/responses.
- When touching the MCP server, validate affected tools with `uv run` from `mcp/` and keep handler schemas in sync with Java endpoint payloads.
- When touching schematic search/catalog behavior, run the focused `schematic-service/test_*.py` tests and avoid tests that depend on the large local data directory when pure fixtures are sufficient.
- When touching the Starlark service, run its service tests. When changing the compiler submodule or `lib/*.star`, also follow its nested `AGENTS.md` and run the relevant compiler tests, including rotation/library coverage for reusable components.
- Isometric preview changes should add focused tests under `src/test/java/ca/waltermiller/mcpapi/preview`; prefer small deterministic grids over live-world tests.
- Cover reservation, survey, and snapshot logic in their matching Java test packages and affected MCP tests (`test_area_locks.py`, `test_survey_site.py`, `test_undo_dry_run.py`).
- `scripts/verify_undo_redo.py` is a live regression harness for a separately configured disposable server on port 7071. Run it from `starlark-service/` with `uv run ../scripts/verify_undo_redo.py --snapshot-dir ../build/redo-smoke/build-snapshots`; its server-properties guard must pass. Restart that disposable server and rerun with `--resume` for persistence coverage.

## Configuration Notes
- The REST API defaults to `http://localhost:7070`; the JVM property `-Dapi.port=<port>` overrides the port. Compose does not publish 7070 to the host; local smoke tests require a reachable API or a deliberate port mapping.
- Database environment variables: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, and `DB_PASSWORD`.
- The MCP server reads `BASE_URL` from `mcp/.env`; it defaults to `http://localhost:7070`.
- Optional MCP service URLs are `SCHEMATIC_SERVICE_URL` (default `http://localhost:7080`) and `STARLARK_SERVICE_URL` (default `http://localhost:7090`).
- The schematic service uses `SCHEMATIC_DATA_DIR` and `ELASTICSEARCH_URL`; the Starlark service uses `STARLARK_TOOL_DIR`, `STARLARK_CACHE_DIR`, and `STARLARK_LIBRARY_DIR`, with resource-limit settings prefixed `STARLARK_`.
- `AREA_LOCK_IDLE_SECONDS` defaults to 900. `BUILD_SNAPSHOT_DIR` defaults to `build-snapshots` under the server run directory; `BUILD_SNAPSHOT_MAX_VOLUME` defaults to 2,000,000 voxels.
- Compose mounts `starlark-library/` read/write separately from the artifact cache. Its schematic profile mounts the self-contained `schematic-service-data/` directory, including copied images and metadata in `schematic-images/` for IDs with matching NBT files.
- Accept the Minecraft EULA before running a server (`cp eula.txt.example eula.txt`, then set `eula=true`).
- Use `server.properties.example` as a baseline for local server configuration.

## Commit & Pull Request Guidelines
- Commits in history are short and imperative, often lowercase (for example `fix bug` or `change block set endpoint payload`); keep summaries clear and scoped.
- PRs should include behavior changes, commands run, affected endpoints/tools, database impacts, and sample JSON or screenshots when useful.
- Call out API-breaking changes, schema changes, new ports, or Docker/deployment changes.
- Keep diffs focused. Do not mix large generated `.nbt` or world data changes into unrelated code PRs.
- Treat both Starlark service submodules as separate repositories: commit changes inside the affected submodule first, then update the parent repository's gitlink intentionally.

## Security & Deployment Tips
- Keep API port 7070 and PostgreSQL restricted on public hosts. Compose binds PostgreSQL to localhost by default.
- Do not commit secrets, `.env` credentials, world data, logs, or private server state.
- Do not commit schematic source data or Starlark cache artifacts from `schematic-service-data/` or `starlark-service-data/`.
- Keep PostgreSQL dumps and world undo/redo snapshots out of commits. Preserve both snapshot directions and pending markers when backing up or recovering builds.
- The Docker stack persists Minecraft and PostgreSQL data in named volumes; `docker compose down -v` deletes that data.
- nginx supports basic auth through `HTPASSWD`; keep that value out of the repository.
