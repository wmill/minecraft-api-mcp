# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Fabric mod for Minecraft 1.21.7 that provides a web API server for interacting with the Minecraft server. The mod exposes REST endpoints to query player information, spawn entities, and interact with the game world through HTTP requests.

## Build and Development Commands

### Building the mod
```bash
./gradlew build          # Build the complete mod jar
./gradlew clean build    # Clean build from scratch
./gradlew jar           # Build just the jar without tests
```

### Running the mod
```bash
./gradlew runClient     # Launch Minecraft client with mod loaded
./gradlew runServer     # Launch Minecraft server with mod loaded
```

### Development tasks
```bash
./gradlew classes       # Compile main classes only
./gradlew clientClasses # Compile client-side classes
./gradlew genSources    # Decompile Minecraft source code for reference
./gradlew migrateMappings # Update to newer mappings when needed
```

### IDE Integration
```bash
./gradlew genEclipseRuns # Generate Eclipse run configurations
./gradlew vscode        # Generate VSCode launch configurations
```

## Architecture

### Core Components

- **ExampleMod** (`src/main/java/com/example/ExampleMod.java`): Main mod entrypoint that initializes the API server when the Minecraft server starts
- **APIServer** (`src/main/java/com/example/APIServer.java`): Javalin-based web server running on port 7070, contains core endpoints and player management
- **Endpoint System** (`src/main/java/com/example/endpoints/`): Modular endpoint architecture where each endpoint class extends APIEndpoint

### Web API Architecture

The mod uses Javalin as the web framework to create REST endpoints. Key patterns:

1. **APIEndpoint Base Class**: All endpoints extend this class and receive Javalin app instance and MinecraftServer reference
2. **Server Thread Safety**: All Minecraft operations are wrapped in `server.execute()` to ensure thread safety
3. **JSON Serialization**: Uses Jackson for automatic JSON conversion of records and objects

### Key Endpoints

- `GET /players` - Returns array of PlayerInfo objects with name, UUID, and position
- `GET /api/world/entities` - Lists all registered entity types
- `POST /api/world/entities/spawn` - Spawns entities in the world with EntitySpawnRequest payload

### Fabric Integration

- Uses Fabric API and Loader for mod lifecycle management
- Integrates with `ServerLifecycleEvents.SERVER_STARTED` to initialize the web server
- Accesses Minecraft registries (`Registries.ENTITY_TYPE`) for game data
- Uses server world management for entity spawning across dimensions

### Dependencies

- **Javalin 6.1.3**: Web framework for HTTP server
- **Jackson 2.15.0**: JSON serialization
- **Fabric API**: Minecraft mod framework
- **Minecraft 1.21.7** with Yarn mappings

## Development Notes

- The mod ID is "modid" (defined in ExampleMod.MOD_ID)
- Web server starts automatically when Minecraft server initializes
- All API operations that modify game state must run on the server thread
- Uses records for clean JSON data transfer objects (PlayerInfo, EntityInfo, EntitySpawnRequest)