# Docker Setup for Minecraft Fabric Mod

This guide explains how to build and deploy your Minecraft Fabric mod using Docker.

## Prerequisites

1. Docker installed on your system
2. Java 21 for building the mod
3. Fabric server launcher jar file

## Setup Steps

### 1. Download Fabric Server Launcher

Download the Fabric server launcher for Minecraft 1.21:
```bash
# Use the provided download script
./download-fabric-server.sh

# Or download manually
wget https://meta.fabricmc.net/v2/versions/loader/1.21/0.16.9/1.0.1/server/jar -O fabric-server-launch.jar
```

### 2. Accept Minecraft EULA

```bash
cp eula.txt.example eula.txt
# Edit eula.txt and set eula=true
```

### 3. Configure Server Properties (Optional)

```bash
cp server.properties.example server.properties
# Edit server.properties as needed
```

## Build Commands

### Build with Gradle

```bash
# Build the mod jar
./gradlew build

# Build Docker image using Gradle tasks
./gradlew dockerBuild

# Build and run Docker container
./gradlew dockerRun

# Push to registry (if configured)
./gradlew dockerPush
```

### Build with Docker directly

```bash
# Build image
docker build -t minecraft-fabric-mod .

# Run container
docker run -d -p 25565:25565 -p 7070:7070 minecraft-fabric-mod
```

### Using Docker Compose (Recommended)

```bash
# Start the server with persistent volumes
docker-compose up -d

# View logs
docker-compose logs -f

# Stop the server
docker-compose down

# Stop and remove volumes (WARNING: This deletes world data)
docker-compose down -v
```

## Available Gradle Docker Tasks

- `./gradlew dockerBuild` - Build Docker image
- `./gradlew dockerRun` - Build and run container (stops existing first)
- `./gradlew dockerStop` - Stop and remove running container
- `./gradlew dockerLogs` - View container logs
- `./gradlew dockerPush` - Push image to registry
- `./gradlew dockerCompose` - Start services using docker-compose

## Ports Exposed

- **25565**: Minecraft server port
- **7070**: API server port for mod endpoints

## Volumes

- `/minecraft/world` - World data (persistent)
- `/minecraft/logs` - Server logs (persistent)

## API Endpoints

Once running, the mod API will be available at:
- `http://localhost:7070/api/world/players`
- `http://localhost:7070/api/world/entities`
- `http://localhost:7070/api/world/blocks/*`
- `http://localhost:7070/api/message/*`

## Troubleshooting

### Container won't start
1. Check EULA acceptance in `eula.txt`
2. Verify Fabric server jar is present
3. Check Docker logs: `docker-compose logs minecraft`

### API not responding
1. Check if port 7070 is exposed
2. Verify mod is loaded correctly
3. Check server logs for errors

### Memory issues
1. Adjust JAVA_OPTS in docker-compose.yml
2. Increase Docker memory limits
3. Monitor with `docker stats`

## Development

For development, you can mount your mod directly:
```bash
# Add to docker-compose.yml volumes:
- ./build/libs:/minecraft/mods
```

Then rebuild your mod and restart the container to test changes.