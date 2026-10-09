# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Minecraft 1.21.7 Fabric mod combined with a Python Model Context Protocol (MCP) server that enables AI models to interact with Minecraft servers. The mod exposes REST endpoints on port 7070, and the MCP server wraps these as tools for Claude Desktop and other LLM clients.

**Key Components:**
- Java Fabric mod with Javalin REST API server
- Python MCP server with stdio and SSE transport support
- PostgreSQL database for persistent build task management
- Optional Python schematic catalog service on port 7080
- Optional Elasticsearch-backed schematic search index
- Optional Python Starlark build service on port 7090 (starlark-to-nbt and nbt-image-gen vendored as git submodules at `starlark-service/starlark-to-nbt` and `starlark-service/nbt-image-gen`; run `git submodule update --init` after cloning)
- Docker containerization for deployment

## Build and Development Commands

### Building the mod
```bash
./gradlew build          # Build the complete mod jar with tests
./gradlew clean build    # Clean build from scratch
./gradlew jar            # Build just the jar without tests
./gradlew shadowJar      # Build fat jar with dependencies
./gradlew remapJar       # Remap fat jar for distribution
```

### Running the mod
```bash
./gradlew runClient     # Launch Minecraft client with mod loaded
./gradlew runServer     # Launch Minecraft server with mod loaded
```

### Testing
```bash
./gradlew test                    # Run all tests
./gradlew test --rerun-tasks      # Force rerun all tests
open build/reports/tests/test/index.html  # View test report
```

### Development tasks
```bash
./gradlew classes       # Compile main classes only
./gradlew genSources    # Decompile Minecraft source code for reference
./gradlew migrateMappings # Update to newer mappings when needed
```

### IDE Integration
```bash
./gradlew genEclipseRuns # Generate Eclipse run configurations
./gradlew vscode         # Generate VSCode launch configurations
```

### Docker Operations
```bash
./gradlew dockerBuild     # Build Docker image
./gradlew dockerRun       # Build and run container
./gradlew dockerStop      # Stop running container
./gradlew dockerLogs      # View container logs
./gradlew dockerCompose   # Start with docker-compose (includes PostgreSQL)

# Direct docker-compose commands
docker-compose up -d      # Start all services
docker-compose logs -f minecraft  # Follow logs
docker-compose down       # Stop all services

# Optional schematic catalog/search stack
docker compose --profile schematics up -d elasticsearch schematic-service
curl -X POST http://localhost:7080/index/rebuild

# Optional Starlark build service
docker compose --profile starlark up -d starlark-service
curl http://localhost:7090/health
```

### Python MCP Server
```bash
cd mcp
uv sync                   # Install dependencies

# Run with stdio transport (Claude Desktop default)
uv run minecraft_mcp.py

# Run with SSE transport (web clients, Claude API)
uv run minecraft_mcp.py --transport sse --host 0.0.0.0 --port 3000
```

### Python Schematic Service
```bash
cd schematic-service

# Run the optional schematic catalog API locally
uv run schematic-service                                      # uses main.py entrypoint (port 7080)
uv run uvicorn schematic_service.app:app --host 0.0.0.0 --port 7080  # equivalent direct form

# Smoke-check local catalog loading
uv run python -c "from schematic_service.config import load_config; from schematic_service.catalog import load_catalog; c=load_config(); docs=load_catalog(c.catalog_path,c.nbt_dir,c.images_dir); print(len(docs), sum(1 for d in docs if d['placeable']))"
```

### Python Starlark Build Service
```bash
cd starlark-service
git submodule update --init   # once, after cloning: vendors starlark-to-nbt and nbt-image-gen
uv sync

uv run starlark-service                                                # main.py entrypoint (port 7090)
uv run uvicorn starlark_service.app:app --host 0.0.0.0 --port 7090     # equivalent direct form
uv run pytest                                                          # service tests
```

## Architecture

### Core Java Components

#### Mod Initialization
- **McpApiMod** (`src/main/java/ca/waltermiller/mcpapi/McpApiMod.java`): Main mod entrypoint that starts the API server when the Minecraft server starts (database initialization is owned by the build task system inside `APIServer`)
- **APIServer** (`src/main/java/ca/waltermiller/mcpapi/APIServer.java`): Javalin-based web server on port 7070 (override with `-Dapi.port`), orchestrates endpoint registration and initializes build system

#### Endpoint System
**Location**: `src/main/java/ca/waltermiller/mcpapi/endpoints/`

All endpoints extend the `APIEndpoint` base class, which provides:
- Javalin app instance, MinecraftServer reference, and logger via constructor
- `respond(ctx, future, timeout, ...)` — shared async responder that waits on a `CompletableFuture`, maps validation failures to 400 (via the `isClientError` message-prefix heuristic) and everything else to 500
- Route registration happens in each endpoint's private `init()` method

**Shared helpers:**
- `WorldResolver.resolveWorldKey(String)` - single place that maps world identifier strings to registry keys (null/blank → overworld, malformed → null; callers treat null as "unknown world" and return 400)
- `OperationResult` - common `success()`/`error()` interface implemented by all core result records, used by `TaskExecutor`'s generic dispatch helpers

**Key Endpoints:**
- `PlayersEndpoint` - Query online players with positions/rotations
- `BlocksEndpoint` - Read/write block data, handle chunks, heightmap queries (core logic in `BlocksEndpointCore`)
- `EntitiesEndpoint` - List entity types, spawn entities
- `MessageEndpoint` - Send messages to players (broadcast or targeted)
- `PlayerTeleportEndpoint` - Teleport players to coordinates
- `PrefabEndpoint` - Place prefab structures: doors, stairs, windows, torches, signs, ladders (core logic in `PrefabEndpointCore`)
- `NBTStructureEndpoint` - Place NBT data structures; snapshots the target cuboid before writing (undo) and supports `dry_run` overwrite reports
- `RainFireEndpoint` - Rain fire effect over a radius
- `BuildTaskEndpoint` - Build task management REST routes (one `registerX()` method per route) with database persistence; includes audit, replay, undo, clone, translate, preview, rail planning, and location query routes
- `TaskExecutor` - Executes queued build tasks on the Minecraft server thread; generic `executeAsync`/`dispatch` helpers parse task data and route it to the endpoint cores
- `RailRenderInspectionService` - Dry-run rail placement inspection used by the audit route

#### Preview Rendering
**Location**: `src/main/java/ca/waltermiller/mcpapi/preview/`

Isometric PNG preview of builds and terrain: `BlockSink` abstraction (`WorldBlockSink` writes to the world, `RecordingBlockSink` records for dry runs), `BlockGrid` sparse voxel container, `IsoRenderer` (scale bounds `MIN_SCALE`/`MAX_SCALE`), `Palette`, `TerrainHeightmapGridAdapter`.

#### Placement Snapshots (Undo / Dry Run)
**Location**: `src/main/java/ca/waltermiller/mcpapi/snapshot/`

- `SnapshotStore` - gzipped structure NBT per build id in `BUILD_SNAPSHOT_DIR` (default `<run dir>/build-snapshots`), capped by `BUILD_SNAPSHOT_MAX_VOLUME` voxels (default 2,000,000)
- `RegionSnapshot` - server-thread capture/restore and the dry-run template-vs-world scan
- `OverwriteTally` - pure counting/categorization for dry-run reports

`POST /api/builds/{id}/undo` restores the snapshot once (build becomes `REVERTED`); later overlapping builds return 409 `undo_conflict` unless `force`. See `docs/undo-and-dry-run.md`.

#### Build Task System
**Location**: `src/main/java/ca/waltermiller/mcpapi/buildtask/`

A comprehensive system for queuing, persisting, and executing complex build operations:

**Architecture Pattern**: Repository-Service-Executor
- **Repositories** (`repository/`): PostgreSQL data access with implementations for Build, Task, and RailPlanningJob entities
- **Services** (`service/`): Business logic for task validation, location queries, build orchestration, and rail planning
- **Models** (`model/`): Data classes (Build, BuildTask, BoundingBox, TaskType, TaskStatus, BuildStatus incl. `REVERTED`, RailPlanningJob, RailPlanningStatus)

**Task Types:**
- `BLOCK_SET` - Place a 3D array of blocks
- `BLOCK_FILL` - Fill a rectangular region with a single block type
- `PREFAB_DOOR` - Place doors with proper orientation and hinge configuration
- `PREFAB_STAIRS` - Build staircases with optional support filling
- `PREFAB_WINDOW` - Place window panes in walls
- `PREFAB_TORCH` - Place torches (wall or floor) with proper facing
- `PREFAB_SIGN` - Place signs with text content
- `PREFAB_LADDER` - Place vertical ladders with auto-facing
- `RAIL_SURFACE_SEGMENT` - Rail corridor segment on the surface
- `RAIL_BRIDGE_SEGMENT` - Rail corridor segment over a gap (bridge)
- `RAIL_TUNNEL_SEGMENT` - Rail corridor segment underground (tunnel)

**Database Schema:**
- `builds` table: Build metadata (id, name, description, world, status, timestamps)
- `build_tasks` table: Individual tasks (id, build_id, task_order, task_type, status, task_data JSONB, description, executed_at)
- `rail_planning_jobs` table: Async rail planning jobs (id, build_id, status, phases, timestamps)

#### Rail Planning System
**Location**: `src/main/java/ca/waltermiller/mcpapi/buildtask/service/RailPlanningService.java`

Asynchronously plans terrain-following rail corridors between two points. Triggered via `POST /api/builds/{id}/plan-rail`; status polled via `GET /api/rail-plans/{jobId}`. The planner samples heightmap data, classifies each segment as surface/bridge/tunnel, then queues the appropriate RAIL_* build tasks.

#### Database Layer
**Location**: `src/main/java/ca/waltermiller/mcpapi/database/`

- **DatabaseManager**: Singleton managing HikariCP connection pool
- **DatabaseConfig**: Environment-based configuration (DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD)
- **DatabaseSchema**: Auto-creates tables and indexes on startup

**Configuration via environment variables:**
```bash
DB_HOST=localhost
DB_PORT=5432
DB_NAME=minecraft_builds
DB_USER=minecraft
DB_PASSWORD=your_password
```

### Python MCP Server Architecture

**Location**: `/mcp/minecraft_mcp/`

#### Server Core
- **`server.py`**: `MinecraftMCPServer` class orchestrates tool registration and routing
- **`__main__.py`**: Entry point supporting stdio and SSE transports
- **`config.py`**: Loads configuration from `.env` file (BASE_URL defaults to `http://localhost:7070`)

#### API Client
- **`client/minecraft_api.py`**: `MinecraftAPIClient` - Async HTTP client using httpx for all Minecraft REST endpoints
- **`client/schematic_service.py`**: `SchematicServiceClient` - Async HTTP client for the optional schematic catalog service

#### Tool System
- **`handlers/`**: Tool handler functions organized by domain:
  - `world.py` - Player/entity tools
  - `blocks.py` - Block manipulation tools
  - `messages.py` - Messaging tools
  - `prefabs.py` - Prefab placement tools
  - `builds.py` - Build task management tools (create builds, add tasks, execute, audit, undo, query by location, get status, rail planning)
  - `system.py` - System tools
  - `schematics.py` - Optional schematic search, metadata lookup, and NBT placement orchestration

- **`tools/`**: Tool definitions and registry:
  - `registry.py` - Maps tool names to handler functions
  - `schemas.py` - Defines tool schemas for MCP discovery

- **`utils/`**: Shared utilities:
  - `formatting.py` - Response formatting helpers
  - `helpers.py` - General helper utilities

### Optional Schematic Service Architecture

**Location**: `/schematic-service/`

This service is intentionally separate from the Minecraft mod. The Minecraft server must start and run normally when Elasticsearch and the schematic service are unavailable.

**Data inputs** live under `schematic-service-data/`, which is ignored by git:
- `schematic_catalog_gemma3.json` - primary AI-generated searchable catalog
- `Schematics-nbt/{schematic_id}.nbt` - converted vanilla NBT files that can be placed
- `schematic-images/{schematic_id}/meta.json` - conversion/image metadata used for enrichment
- `schematic-images/{schematic_id}/{iso,top,north,south,east,west}.png` - pre-rendered previews. This folder is raw render-tool output: about half of it (including renders up to 500+ MP) is for schematics that failed NBT conversion and are not in the catalog, so images are only ever served for catalogued, placeable ids. Orientation (verified against NBT): `top` has north up/east right; side views are elevations seen from that side (`south` = +Z face at rotation NONE); `iso` is viewed from the south-east.

**Service modules:**
- `app.py` - FastAPI routes for health, search, metadata, NBT download, preview images, and index rebuild
- `images.py` - Catalog-gated preview images: downscaled single views and a labelled 3x2 contact sheet (Pillow, LRU-cached)
- `catalog.py` - Catalog normalization and metadata enrichment; restricts which meta.json fields are exposed publicly via `PUBLIC_META_KEYS`
- `search.py` - Elasticsearch indexing/search plus local fallback search
- `config.py` - Environment-driven paths and Elasticsearch URL
- `main.py` - CLI entrypoint invoked by the `schematic-service` script

**Configuration via environment variables (or `.env` in `schematic-service/`):**
```bash
SCHEMATIC_DATA_DIR=../schematic-service-data   # root data directory
SCHEMATIC_CATALOG_PATH=...                     # path to schematic_catalog_gemma3.json
SCHEMATIC_NBT_DIR=...                          # path to Schematics-nbt/
SCHEMATIC_IMAGES_DIR=...                       # path to schematic-images/
ELASTICSEARCH_URL=http://localhost:9200
SCHEMATIC_INDEX=minecraft_schematics
```

**HTTP API:**
- `GET /health`
- `POST /index/rebuild`
- `GET /schematics/search?q=...&limit=...&structure_type=...&style=...&size_category=...&has_interior=...&placeable=true&fallback=true`
- `GET /schematics/{schematic_id}`
- `GET /schematics/{schematic_id}/nbt`
- `GET /schematics/{schematic_id}/images/{view}?max_px=...` - `view` is `sheet` or one of `iso/top/north/south/east/west`; PNG downscaled to `max_px` (64-1024; default 768, or 256 per sheet tile); 404 for ids not in the catalog

**MCP tools using this service:**
- `search_schematics`
- `get_schematic`
- `get_schematic_image` - preview sheet or single view as MCP image content (`search_schematics` can also attach small iso thumbnails via `include_thumbnails`)
- `place_schematic`

`place_schematic` fetches NBT bytes from the schematic service, then calls the Minecraft NBT placement endpoint via multipart upload. Keep this orchestration in MCP unless there is a strong reason to couple the Minecraft mod directly to the schematic service.

### Optional Starlark Build Service Architecture

**Location**: `/starlark-service/`

Compiles Starlark build scripts submitted by MCP users into placeable structure NBT using [starlark-to-nbt](https://github.com/wmill/starlark-to-nbt), which is vendored as a **git submodule** at `starlark-service/starlark-to-nbt` (run `git submodule update --init` after cloning). Like the schematic service, it is intentionally separate from the Minecraft mod and must fail gracefully when unavailable.

**Design:**
- **Stateless with a content-addressed cache**: every `POST /build` carries the full script source; successful builds are cached on disk keyed by `artifact_id = "slk_" + sha256(source, entry, props, root_size, lib_fingerprint)[:16]`, where `lib_fingerprint` hashes the submodule's `lib/*.star` and compiler source (`src/starlark_to_nbt/**/*.py`), so a submodule bump invalidates cached artifacts. Output is deterministic, so identical sources always yield the same artifact id — an evicted artifact is recovered by rebuilding the same source.
- **Sandboxed builds**: the tool has no evaluation budget of its own, so each build runs in a killable subprocess (`starlark_service/runner.py`) under a wall-clock timeout (SIGKILL), an `RLIMIT_DATA` memory cap, and root-volume/NBT-byte caps. Do not lower `STARLARK_BUILD_MEMORY_MB` below ~1024: the Rust starlark runtime reserves ~1GB of data mappings up front and SIGABRTs under smaller caps.
- **Confined `load()`**: submitted scripts can only load from the vendored `lib/` component library and saved `library/` versions (upstream `loader_root` confinement; uniform "module not found" errors prevent filesystem probing). Scripts use `load("../lib/structural.star", ...)`, the same convention as the upstream `examples/`.
- **Script library** (`starlark-library/`, repo root, git-tracked): agents save working scripts via `POST /library` so later builds can fork them or `load()` their components. Layout is `<name>/meta.json` + append-only `v<N>.star`; versions are **immutable** (never edit/delete a published `v<N>.star` — scripts load them by pinned path and the artifact cache keys on source text only, so the lib fingerprint doesn't need to cover the library). Saves are rebuilt first and rejected (200 `{ok:false, diagnostics}`) if they fail; identical re-saves are no-ops. Scripts load saved versions as `load("../library/<name>/v<N>.star", ...)` through the submodule loader's `mounts` option (`runner.py` mounts `library` → `STARLARK_LIBRARY_DIR`); library modules use the same load paths as top-level scripts. `meta.json` records title/description/tags/author, and per version: lineage (`parent` as `name@N`, notes), entry params and UpperCamel component exports with signatures (parsed with Python `ast`, regex fallback), loads, and build stats (size, block_count, palette_top, artifact_id).
- **Artifact previews**: `GET /artifacts/{id}/images/{view}` renders the artifact with [nbt-image-gen](https://github.com/wmill/nbt-image-gen) (submodule at `starlark-service/nbt-image-gen`) on first request, in a subprocess under `STARLARK_PREVIEW_TIMEOUT_S`, into `<artifact_id>.previews/` beside the NBT (evicted with it, counted in cache bytes). Views match the schematic previews (`iso` from the south-east, `top` north up, side elevations, labelled 3x2 `sheet`), but are flattened onto slate rather than white so white blocks stay visible.
- **Build failures are HTTP 200** with `{ok: false, error_kind, diagnostics, hint}` — a build that ran and produced diagnostics is a successful request, keeping the MCP edit→build-error→edit loop exception-free. `error_kind` is one of `starlark_error`, `build_error`, `timeout`, `resource_limit`, `crash`.

**Service modules:**
- `app.py` - FastAPI routes (factory `create_app(config)`)
- `config.py` - env-driven `STARLARK_*` settings
- `sandbox.py` - subprocess orchestration, concurrency cap (429 when full), timeout kill
- `runner.py` - in-subprocess build worker (`build_source` → NBT + result JSON on stdout)
- `cache.py` - artifact hashing, sharded disk layout, LRU eviction, lib fingerprint
- `library.py` - saved-script library: versioned storage, source analysis (params/exports/loads), local search
- `docs.py` - focused views of the component catalog plus the `library` usage topic
- `previews.py` - lazy per-artifact preview rendering (bounded concurrency, per-artifact lock), downscaled views and contact sheet
- `preview_worker.py` - in-subprocess nbt-image-gen render of all six views plus `meta.json`

**Configuration via environment variables (or `.env` in `starlark-service/`):**
```bash
STARLARK_TOOL_DIR=starlark-to-nbt        # submodule root (lib/, docs/, examples/)
STARLARK_CACHE_DIR=../starlark-service-data
STARLARK_BUILD_TIMEOUT_S=15
STARLARK_BUILD_MEMORY_MB=2048            # RLIMIT_DATA in the build subprocess; keep >= 1024
STARLARK_MAX_SOURCE_BYTES=262144
STARLARK_MAX_ROOT_VOLUME=2000000         # voxels
STARLARK_MAX_NBT_BYTES=16777216
STARLARK_MAX_CONCURRENT_BUILDS=2
STARLARK_CACHE_MAX_BYTES=1073741824
STARLARK_LIBRARY_DIR=../starlark-library # saved-script library (git-tracked; /library in Docker)
STARLARK_PREVIEW_TIMEOUT_S=30            # wall-clock cap for rendering one artifact's previews
```

**HTTP API:**
- `GET /health`
- `POST /build` — `{source, entry?, props?, root_size?}`
- `GET /artifacts/{artifact_id}` and `GET /artifacts/{artifact_id}/nbt`
- `GET /artifacts/{artifact_id}/images/{view}?max_px=...` - `view` is `sheet` or one of `iso/top/north/south/east/west`; same sizing as the schematic image route; 404 for unknown artifacts, 503 if rendering fails or times out
- `GET /docs/catalog` — the script API reference (component catalog markdown)
- `GET /examples` and `GET /examples/{name}`
- `POST /library` — `{name, artifact_id | source, title, description, tags?, author?, notes?, parent?, entry?, props?, root_size?}`; title/description required for a new name
- `GET /library/search?q=&tag=&author=&max_x=&max_y=&max_z=&limit=`
- `GET /library/{name}` (meta plus computed `used_by` reverse dependencies) and `GET /library/{name}/source?version=` (version in `X-Library-Version` header)

**MCP tools using this service:**
- `build_starlark_structure` — compile source; failure text lists numbered diagnostics for the edit loop; `include_preview` attaches a small iso render (best-effort)
- `get_starlark_preview` — preview sheet or single view of a compiled artifact as MCP image content
- `place_starlark_structure` — fetch artifact NBT, apply its `y_offset`, place via the mod's NBT placement endpoint
- `get_starlark_docs`
- `list_starlark_examples` / `get_starlark_example`
- `save_starlark_script` / `search_starlark_library` / `get_starlark_script` — shared script library

As with schematics, placement orchestration stays in MCP: `place_starlark_structure` fetches NBT bytes from the starlark service and posts them to the Minecraft `/api/world/structure/place` endpoint.

### Key Patterns

1. **Endpoint Pattern**: Extend `APIEndpoint`, register routes in a private `init()` called from the constructor, use `server.execute()` for Minecraft operations
2. **Thread Safety**: Always wrap Minecraft world modifications with `server.execute()` to run on server thread
3. **JSON Serialization**: Use Java Records for automatic Jackson serialization of DTOs; the build task system shares one `ObjectMapper` via `buildtask.Json.MAPPER`
4. **Database Access**: Use repository pattern with connection pooling via HikariCP; shared JDBC helpers live in `repository/JdbcSupport`; multi-statement writes use `*WithConnection` variants so they run in one transaction
5. **Async Operations**: Use `CompletableFuture` for non-blocking build task execution; endpoints wait on futures via `APIEndpoint.respond()` with an explicit timeout
6. **Error Handling**: Validation/bad-input errors return 400, server-side failures 500, as JSON `{"error": ...}` objects; `BuildTaskEndpoint.handle()` maps `IllegalArgumentException`→400, `IllegalStateException`→409, `SQLException`→500
7. **World Resolution**: Never inline `RegistryKey.of(RegistryKeys.WORLD, ...)` — use `WorldResolver.resolveWorldKey()`
8. **MCP Tool Design**: Each tool handler returns `CallToolResult` with formatted text content
9. **Optional Services**: Schematic service, Elasticsearch, and the Starlark build service must fail gracefully from MCP and must not affect Minecraft startup
10. **Starlark Build Loop**: `build_starlark_structure` failures return HTTP 200 with structured diagnostics (not exceptions) so the MCP user can edit and resubmit; untrusted script execution stays in the service's sandboxed subprocess

### Dependencies

**Java (build.gradle):**
- Javalin 6.7.0 - Web framework for HTTP server
- Jackson 2.18.2 - JSON serialization
- PostgreSQL JDBC 42.7.4 - Database driver
- HikariCP 5.1.0 - Connection pooling
- Fabric API 0.128.1+1.21.7 - Minecraft mod framework
- Minecraft 1.21.7 with Yarn mappings
- JUnit 5, Mockito, AssertJ - Testing libraries

**Python (pyproject.toml):**
- mcp >= 1.11.0 - Model Context Protocol SDK
- httpx >= 0.25.0 - Async HTTP client
- python-dotenv >= 0.9.9 - Environment configuration
- starlette >= 0.27.0 - ASGI framework for SSE
- uvicorn >= 0.23.0 - ASGI server
- debugpy >= 1.8.19 - Debugging support
- requests >= 2.32.5 - Synchronous HTTP (used in some utilities)

**Schematic Service (`schematic-service/pyproject.toml`):**
- fastapi >= 0.115.0 - HTTP API
- httpx >= 0.27.0 - Elasticsearch HTTP client
- pillow >= 10.1 - Preview image downscaling and contact sheets
- python-dotenv >= 1.0.0 - Environment configuration
- uvicorn >= 0.30.0 - ASGI server

**Starlark Service (`starlark-service/pyproject.toml`):** requires Python >= 3.13 (starlark-pyo3 wheels)
- fastapi >= 0.115.0 - HTTP API
- python-dotenv >= 1.0.0 - Environment configuration
- uvicorn >= 0.30.0 - ASGI server
- starlark-to-nbt - editable path dependency on the vendored submodule (pulls nbtlib and starlark-pyo3), so submodule bumps apply without reinstalling
- nbt-image-gen - editable path dependency on the second submodule (pulls numpy and pillow) for artifact previews

### Resource Structure

**Mod Metadata**: `src/main/resources/fabric.mod.json`
- Mod ID: "mcpapi"
- Version: 0.0.1
- Entrypoint: Main (`ca.waltermiller.mcpapi.McpApiMod`); no client entrypoint or mixins
- Requires: FabricLoader >=0.16.14, Minecraft ~1.21.7, Java >=21

## Testing

**Framework**: JUnit 5 + Mockito + AssertJ
**Location**: `src/test/java/ca/waltermiller/mcpapi/`

**Test Categories:**
- Database layer tests: `DatabaseManagerTest`, `DatabaseConfigTest`, `DatabaseIntegrationTest` (integration gated behind `DB_TEST` env var)
- Endpoint tests: `BlocksEndpointTest`, `BlocksEndpointCoreTest`, `PrefabEndpointCoreTest`, `RailRenderInspectionServiceTest`, `EndpointRefactoringTest`
- Build system tests: `TaskExecutorTest`, `BuildTaskEndpointTest`, `BuildTaskEndpointIntegrationTest`, `BuildServiceTest`, `TaskDataValidatorTest`, `LocationQueryServiceTest`, `RailPlanningServiceTest`
- Preview tests: `BlockGridTest`, `IsoRendererTest`, `PaletteTest`, `TerrainHeightmapGridAdapterTest`
- Snapshot tests: `SnapshotStoreTest`, `OverwriteTallyTest`, `BuildUndoEndpointTest`

**Python Tests** (`mcp/`):
- `test_backward_compatibility.py` - Backward compatibility tests
- `test_stdio_transport.py` - Stdio transport tests
- `test_debug_mode.py` - Debug mode tests
- `test_final_verification.py` - Final verification tests
- `test_schematic_tools.py` - Schematic MCP tool registration/config tests
- `test_schematic_images.py` - `get_schematic_image` and search thumbnails
- `test_starlark_tools.py` - Starlark MCP tool registration/config tests, `get_starlark_preview` and build thumbnails
- `test_undo_dry_run.py` - `undo_build` and `dry_run` placement tools

**Schematic Service Tests** (`schematic-service/`):
- `test_catalog.py` - Catalog normalization and metadata sanitization
- `test_search.py` - Local fallback search behavior
- `test_images.py` - Preview downscaling, contact sheet, catalog gating, and path validation (run with `uv run --with pytest python -m pytest`)

**Starlark Service Tests** (`starlark-service/`, run with `uv run pytest`):
- `test_app.py` - Build success/failure/diagnostics, cache hits, artifact routes, examples/catalog
- `test_cache.py` - Artifact id hashing, LRU eviction, lib fingerprint
- `test_sandbox.py` - Timeout kill, resource-limit rejections, queue-full 429
- `test_library.py` - Save/version immutability, failed-build rejection, save by artifact id, loading saved versions, search filters
- `test_previews.py` - Preview views/sheet, orientation, render failure/timeout 503, eviction of preview dirs

**Testing Best Practices** (see TESTING.md):
- Extract pure logic from endpoints into testable helper methods
- Test coordinate calculations and data transformations
- Avoid mocking heavy Minecraft classes (ServerWorld, MinecraftServer)
- Use `@ParameterizedTest` for testing multiple scenarios
- Focus on testing business logic, not framework code

## Coordinate System

Minecraft uses a right-handed 3D coordinate system:
- **X-axis**: East (positive) / West (negative) - Longitude
- **Z-axis**: South (positive) / North (negative) - Latitude
- **Y-axis**: Vertical elevation from -64 to 320 (sea level at 63)
- Units: 1 = 1 block (1 cubic meter)

## Development Notes

- Mod ID is "mcpapi" (defined in `McpApiMod.MOD_ID`)
- Web API server runs on port 7070, starts automatically with Minecraft server
- Optional schematic service runs on port 7080 when started
- Optional Starlark build service runs on port 7090 when started; its artifact cache in `starlark-service-data/` is git-ignored and safe to delete (artifacts regenerate deterministically); its script library in `starlark-library/` is durable and meant to be committed
- Database schema auto-creates on first server start
- All Minecraft operations modifying game state must run on server thread via `server.execute()`
- Build tasks are executed asynchronously and can be queried by location for spatial awareness
- Run Python commands from the relevant Python project with `uv run`
- Keep `schematic-service-data/` local-only; do not commit converted NBT files, source schematics, rendered images, or generated catalog data
- MCP server uses stdio transport by default (for Claude Desktop) but supports SSE for web clients
- Fat JAR includes all dependencies; remapped JAR is for distribution to other servers
- Container deployment requires setting `eula=true` in eula.txt and downloading Fabric server JAR

## Docker Deployment

The project includes full Docker containerization with PostgreSQL:

1. **Accept EULA**: `cp eula.txt.example eula.txt` and edit to set `eula=true`
2. **Download Fabric**: `./download-fabric-server.sh`
3. **Build mod**: `./gradlew build`
4. **Deploy**: `docker-compose up -d`

**Services:**
- `postgres` - PostgreSQL 16 database on port 5432
- `minecraft` - Fabric server with mod on ports 25565 (game) and 7070 (API)
- `nginx` - Optional reverse proxy with basic auth support
- `elasticsearch` - Optional schematic search index, enabled by the `schematics` profile
- `schematic-service` - Optional schematic catalog/NBT service on port 7080, enabled by the `schematics` profile
- `starlark-service` - Optional Starlark build service on port 7090, enabled by the `starlark` profile (Docker build requires the submodules: `git submodule update --init`)

**Database access:**
```bash
docker-compose exec postgres psql -U minecraft -d minecraft_builds
```
