# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Minecraft 1.21.7 Fabric mod combined with a Python Model Context Protocol (MCP) server that enables AI models to interact with Minecraft servers. The mod exposes REST endpoints on port 7070, and the MCP server wraps these as tools for Claude Desktop and other LLM clients.

**Key Components:**
- Java Fabric mod with Javalin REST API server
- Python MCP server with stdio and SSE transport support
- PostgreSQL database for persistent build task management
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

## Architecture

### Core Java Components

#### Mod Initialization
- **ExampleMod** (`src/main/java/com/example/ExampleMod.java`): Main mod entrypoint that initializes database and API server when Minecraft server starts
- **APIServer** (`src/main/java/com/example/APIServer.java`): Javalin-based web server running on port 7070, orchestrates endpoint registration and initializes build system

#### Endpoint System
**Location**: `src/main/java/com/example/endpoints/`

All endpoints extend `APIEndpoint` base class and receive:
- Javalin app instance for route registration
- MinecraftServer reference for world access
- Logger for consistent logging

**Key Endpoints:**
- `PlayersEndpoint` - Query online players with positions/rotations
- `BlocksEndpoint` - Read/write block data, handle chunks
- `EntitiesEndpoint` - List entity types, spawn entities
- `MessageEndpoint` - Send messages to players (broadcast or targeted)
- `PlayerTeleportEndpoint` - Teleport players to coordinates
- `PrefabEndpoint` - Place prefab structures from NBT files
- `NBTStructureEndpoint` - Work with NBT data structures
- `BuildTaskEndpoint` - Complex build task management with database persistence

#### Build Task System
**Location**: `src/main/java/com/example/buildtask/`

A comprehensive system for queuing, persisting, and executing complex build operations:

**Architecture Pattern**: Repository-Service-Executor
- **Repositories** (`repository/`): PostgreSQL data access with implementations for Build and Task entities
- **Services** (`service/`): Business logic for task validation, location queries, and build orchestration
- **Models** (`model/`): Data classes (Build, BuildTask, BoundingBox, TaskType, TaskStatus)
- **Executor**: `TaskExecutor` - Executes queued tasks on Minecraft server thread

**Task Types:**
- `BLOCK_SET` - Place a 3D array of blocks
- `BLOCK_FILL` - Fill a rectangular region with a single block type
- `PREFAB_DOOR` - Place doors with proper orientation and hinge configuration
- `PREFAB_STAIRS` - Build staircases with optional support filling
- `PREFAB_WINDOW` - Place window panes in walls
- `PREFAB_TORCH` - Place torches (wall or floor) with proper facing
- `PREFAB_SIGN` - Place signs with text content

**Database Schema:**
- `builds` table: Build metadata (id, name, description, world, status, timestamps)
- `build_tasks` table: Individual tasks (id, build_id, task_order, task_type, status, task_data JSONB, description, executed_at)

#### Database Layer
**Location**: `src/main/java/com/example/database/`

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

#### Tool System
- **`handlers/`**: Tool handler functions organized by domain:
  - `world.py` - Player/entity tools
  - `blocks.py` - Block manipulation tools
  - `messages.py` - Messaging tools
  - `prefabs.py` - Prefab placement tools
  - `builds.py` - Build task management tools (create builds, add tasks, execute, query by location, get status)
  - `system.py` - System tools

- **`tools/`**: Tool definitions and registry:
  - `registry.py` - Maps tool names to handler functions
  - `schemas.py` - Defines tool schemas for MCP discovery

### Key Patterns

1. **Endpoint Pattern**: Extend `APIEndpoint`, register routes in constructor, use `server.execute()` for Minecraft operations
2. **Thread Safety**: Always wrap Minecraft world modifications with `server.execute()` to run on server thread
3. **JSON Serialization**: Use Java Records for automatic Jackson serialization of DTOs
4. **Database Access**: Use repository pattern with connection pooling via HikariCP
5. **Async Operations**: Use `CompletableFuture` for non-blocking build task execution
6. **Error Handling**: Return proper HTTP status codes with JSON error objects
7. **MCP Tool Design**: Each tool handler returns `CallToolResult` with formatted text content

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

### Resource Structure

**Mod Metadata**: `src/main/resources/fabric.mod.json`
- Mod ID: "modid"
- Version: 0.0.1
- Entrypoints: Main (`ExampleMod`), Client
- Mixins: `modid.mixins.json`
- Requires: FabricLoader >=0.16.14, Minecraft ~1.21.7, Java >=21

## Testing

**Framework**: JUnit 5 + Mockito + AssertJ
**Location**: `src/test/java/com/example/`

**Test Categories:**
- Database layer tests: `DatabaseManagerTest`, `DatabaseConfigTest`, `DatabaseIntegrationTest`
- Endpoint core logic tests: `BlocksEndpointCoreTest`, `PrefabEndpointCoreTest`
- Build system tests: `TaskExecutorTest`, `BuildTaskEndpointIntegrationTest`, `TaskDataValidatorTest`
- Utility tests: `CoordinateUtilsTest`

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

- Mod ID is "modid" (defined in `ExampleMod.MOD_ID`)
- Web API server runs on port 7070, starts automatically with Minecraft server
- Database schema auto-creates on first server start
- All Minecraft operations modifying game state must run on server thread via `server.execute()`
- Build tasks are executed asynchronously and can be queried by location for spatial awareness
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

**Database access:**
```bash
docker-compose exec postgres psql -U minecraft -d minecraft_builds
```
