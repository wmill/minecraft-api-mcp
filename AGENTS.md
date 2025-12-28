# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/com/example`: Core mod and Javalin API server (`APIServer`, `endpoints/*`), plus mixins.
- `src/client/java/com/example`: Optional client entrypoint hooks.
- `src/main/resources`: `fabric.mod.json`, mixin configs, assets/icons.
- `mods/`: External jars (Fabric API) loaded by the server; `build/libs` holds built mod + fat/remapped jars after builds.
- `mcp/`: Model Context Protocol server that exposes the REST API tools to LLM clients; Python 3.12+.
- `nbt-gen/`: Generated `.nbt` structures used by endpoints; keep large binaries out of git unless required.
- `test_api.py`: Lightweight Python integration tester for the running server at `localhost:7070`.
- `docker-compose.yml` and root scripts manage local server containerization.

## Build, Test, and Development Commands
- `./download-fabric-server.sh` (or `download-fabric-api-alternative.sh`): Fetch server launcher and Fabric API jars.
- `./gradlew build`: Compile, run Loom processing, and produce mod jars under `build/libs`.
- `./gradlew shadowJar` then `./gradlew remapJar`: Build the fat jar and remap it for distribution.
- `./gradlew runServer` (Loom): Start the dev server with hot reloading; `./gradlew runClient` for client-side checks.
- Docker path: `./gradlew dockerBuild`, `dockerRun`, `dockerLogs`, `dockerStop`; or `docker-compose up -d` to boot a server exposing 25565/7070.
- MCP server: `cd mcp && pip install -r requirements.txt` (or `uv sync`), then `python minecraft_mcp.py`; runs an MCP stdio server pointing at `http://localhost:7070`.

## Coding Style & Naming Conventions
- Java 21; 4-space indentation; keep files ASCII.
- Packages stay under `com.example...`; classes PascalCase, methods/fields camelCase, constants UPPER_SNAKE.
- Favor Fabric logging via `ExampleMod.LOGGER`; keep endpoint handlers small and side-effect limited.
- JSON payloads follow existing REST schema (see `endpoints/*`); prefer descriptive path segments (`/api/world/...`).

## Testing Guidelines
- Integration: start the server, then run `python3 test_api.py` to hit player/entity endpoints; update `API_BASE` if the port changes.
- Add focused unit tests under `src/test/java` (none exist yet) when adding logic; mirror package structure and name tests `*Test`.
- For manual checks, document the world seed and commands used (e.g., `curl http://localhost:7070/api/world/players`).

## Commit & Pull Request Guidelines
- Commits in history are short and imperative (e.g., `fix bug`, `change block set endpoint payload`); follow that style with clear scope and lowercase summary.
- PRs should include: brief description of behavior change, commands run (`./gradlew build`, tests), affected endpoints/blocks, and any screenshots or sample JSON responses.
- Link related issues; call out API-breaking changes or new ports; keep diffs focused per PR.

## Security & Configuration Tips
- Accept the EULA before running (`eula.txt` -> `eula=true`); copy from `eula.txt.example` if needed.
- Keep API port 7070 restricted on public hosts; avoid committing secrets or world data. Use `server.properties.example` as a baseline for server tweaks.
- MCP: confirm `mcp_config.json` base URL matches the running API, and keep credentials/environment variables out of the repo.
