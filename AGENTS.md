# Repository Guidelines
- Run Python commands from the relevant Python project with `uv run` so the correct environment is used.
- This repository combines a Minecraft 1.21.7 Fabric mod, a Javalin REST API on port 7070, PostgreSQL-backed build task persistence, and a Python MCP server.

## Project Structure & Module Organization
- `src/main/java/ca/waltermiller/mcpapi`: Core Fabric mod, `APIServer`, endpoint classes, database layer, and build-task domain.
- `src/main/java/ca/waltermiller/mcpapi/endpoints`: REST API endpoints. Keep route registration thin and put reusable logic into core/helper classes where practical.
- `src/main/java/ca/waltermiller/mcpapi/buildtask`: Build orchestration models, repositories, and services, including rail planning and location queries.
- `src/main/java/ca/waltermiller/mcpapi/database`: HikariCP/PostgreSQL configuration, schema creation, and connection management.
- `src/client/java/ca/waltermiller/mcpapi`: Optional client entrypoint hooks and client mixins.
- `src/main/resources`: `fabric.mod.json`, mod assets, and resource metadata.
- `src/test/java/ca/waltermiller/mcpapi`: JUnit 5 tests for endpoint cores, build-task logic, database config/manager, and utilities.
- `mcp/`: Python MCP server package (`minecraft_mcp`) that wraps the REST API as MCP tools; supports stdio and SSE transports.
- `skills/minecraft-http-gateway`: Local Codex skill and helper scripts for using the Minecraft HTTP API.
- `nbt-gen/`: Generated `.nbt` structures and generation helpers; avoid committing large generated binaries unless required.
- Root Docker files and `docker-compose.yml`: Minecraft server image, MCP image, PostgreSQL, nginx gateway, and certbot configuration.

## Build, Test, and Development Commands
- `./download-fabric-server.sh` or `./download-fabric-api-alternative.sh`: Fetch the Fabric server launcher and Fabric API jars when needed.
- `./gradlew build`: Compile, run tests, Loom processing, and produce mod jars under `build/libs`.
- `./gradlew compileJava`: Fast Java compile check.
- `./gradlew test`: Run the JUnit 5 test suite. Use `./gradlew test --rerun-tasks` when validating from a clean test run.
- `./gradlew shadowJar` then `./gradlew remapJar`: Build the fat jar and remap it for distribution.
- `./gradlew runServer`: Start the dev Minecraft server with the mod loaded. `./gradlew runClient` starts a client.
- `docker compose up -d postgres`: Start the local PostgreSQL dependency used by build-task persistence.
- `docker compose up -d`: Start the composed services. The compose stack includes PostgreSQL, Minecraft, MCP, nginx, and certbot.
- Gradle Docker tasks: `./gradlew dockerBuild`, `dockerRun`, `dockerLogs`, `dockerStop`, and `dockerCompose`.
- MCP server: from `mcp/`, run `uv sync`, then `uv run minecraft_mcp.py` for stdio or `uv run minecraft_mcp.py --transport sse --host 0.0.0.0 --port 3000` for SSE.
- Root integration smoke test: with the API running at `localhost:7070`, run `uv run test_api.py`.
- Python MCP tests live in `mcp/`; run them with `uv run` from that directory, for example `uv run python test_stdio_transport.py`.

## Coding Style & Naming Conventions
- Java 21; 4-space indentation; keep files ASCII unless the file already requires otherwise.
- Java packages stay under `ca.waltermiller.mcpapi...`; classes use PascalCase, methods/fields camelCase, constants UPPER_SNAKE.
- Favor Fabric logging through `ExampleMod.LOGGER`.
- All Minecraft world reads/writes that must run on the server thread should use `server.execute()` or the established local endpoint pattern.
- Keep endpoint handlers small. Prefer service/core classes for validation, coordinate math, build orchestration, and other testable behavior.
- JSON payloads should follow snake_case in both the HTTP API and the MCP server.
- Use Java records/DTOs for structured request and response payloads when that matches existing endpoint style.
- Database access should stay behind repository/service classes and use `DatabaseManager` rather than ad hoc connections.

## Testing Guidelines
- Add focused JUnit tests under `src/test/java/ca/waltermiller/mcpapi`, mirroring the production package, and name tests `*Test`.
- Prefer testing pure logic: coordinate calculations, validation, payload transformations, repository/service behavior, and endpoint core classes.
- Avoid mocking heavy Minecraft runtime classes such as `MinecraftServer` and `ServerWorld` unless an existing test pattern already handles it.
- Use AssertJ/JUnit 5 patterns already present in the test suite.
- Database-related tests may require local PostgreSQL; `docker compose up -d postgres` starts the expected service.
- For manual API checks, document the server state, world seed or relevant coordinates, and sample requests/responses.
- When touching the MCP server, validate affected tools with `uv run` from `mcp/` and keep handler schemas in sync with Java endpoint payloads.

## Configuration Notes
- The REST API defaults to `http://localhost:7070`.
- Database environment variables: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, and `DB_PASSWORD`.
- The MCP server reads `BASE_URL` from `mcp/.env`; it defaults to `http://localhost:7070`.
- Accept the Minecraft EULA before running a server (`cp eula.txt.example eula.txt`, then set `eula=true`).
- Use `server.properties.example` as a baseline for local server configuration.

## Commit & Pull Request Guidelines
- Commits in history are short and imperative, often lowercase (for example `fix bug` or `change block set endpoint payload`); keep summaries clear and scoped.
- PRs should include behavior changes, commands run, affected endpoints/tools, database impacts, and sample JSON or screenshots when useful.
- Call out API-breaking changes, schema changes, new ports, or Docker/deployment changes.
- Keep diffs focused. Do not mix large generated `.nbt` or world data changes into unrelated code PRs.

## Security & Deployment Tips
- Keep API port 7070 and PostgreSQL restricted on public hosts. Compose binds PostgreSQL to localhost by default.
- Do not commit secrets, `.env` credentials, world data, logs, or private server state.
- The Docker stack persists Minecraft and PostgreSQL data in named volumes; `docker compose down -v` deletes that data.
- nginx supports basic auth through `HTPASSWD`; keep that value out of the repository.
