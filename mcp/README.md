# Minecraft API MCP Server

This is a Model Context Protocol (MCP) server that provides tools to interact with the Minecraft Fabric mod API.

## Features

The MCP server provides the following tools:

### Player Management
- `get_players` - Get list of all online players with positions and rotations

### Entity Management  
- `get_entities` - Get list of all available entity types
- `spawn_entity` - Spawn entities at specified coordinates

### Block Management
- `get_blocks` - Get list of all available block types
- `set_blocks` - Set blocks in the world using 3D arrays
- `get_blocks_chunk` - Get a chunk of blocks from the world
- `fill_box` - Fill a cuboid/box with a specific block type between two coordinates
- `get_heightmap` - Get topographical heightmap for terrain analysis and building placement

### Messaging
- `broadcast_message` - Send messages to all players on the server
- `send_message_to_player` - Send messages to a specific player by name or UUID

## Coordinate System

World coordinates are based on a grid where three lines or axes intersect at the origin point:

- **X-axis**: Distance east (positive) or west (negative) of the origin point (longitude)
- **Z-axis**: Distance south (positive) or north (negative) of the origin point (latitude)  
- **Y-axis**: Elevation from -64 to 320 blocks, with sea level at 63 (height)

Each unit equals one block (1 cubic meter in real-world terms).

## Detailed Features

### Block Fill Operations
The `fill_box` tool allows you to quickly create large structures by filling rectangular areas with any block type. Simply specify two corner coordinates and the desired block type. The tool automatically handles coordinate ordering and includes safety limits to prevent excessive server load.

### Terrain Analysis
The `get_heightmap` tool provides comprehensive topographical analysis including:
- **Height mapping** with multiple detection types:
  - `WORLD_SURFACE` - Surface level including trees, buildings
  - `MOTION_BLOCKING` - Solid blocks that block movement  
  - `MOTION_BLOCKING_NO_LEAVES` - Solid ground ignoring leaves
  - `OCEAN_FLOOR` - Solid ground ignoring water
- **Terrain statistics** - Percentage of flat, steep, and moderate terrain
- **Building recommendations** - Automated assessment of construction difficulty
- **Visual height grid** - For areas 20x20 blocks or smaller

### Player Communication
Advanced messaging system with:
- **Broadcast messaging** - Send announcements to all online players
- **Targeted messaging** - Send messages to specific players by name or UUID
- **Action bar support** - Display messages above the hotbar or in chat
- **Player count tracking** - See how many players received broadcast messages

## Setup

1. Install dependencies:
```bash
cd mcp
uv sync
```

2. Make sure your Minecraft server is running with the Fabric mod on port 7070

3. (Optional) Configure the Minecraft API base URL in a `.env` file:
```bash
BASE_URL=http://localhost:7070
```

## Transport Modes

The MCP server supports two transport protocols:

### stdio Transport (Default - for Claude Desktop)

This is the default mode, compatible with Claude Desktop and other stdio-based MCP clients.

**Run the server:**
```bash
uv run minecraft_mcp.py
```

**Or explicitly specify stdio:**
```bash
uv run minecraft_mcp.py --transport stdio
```

### HTTP/SSE Transport (for web clients and Claude API)

This mode runs an HTTP server with Server-Sent Events, compatible with web-based MCP clients and the Claude API's MCP connector.

**Run HTTP/SSE server:**
```bash
uv run minecraft_mcp.py --transport sse --host 0.0.0.0 --port 3000
```

**Command-line options:**
- `--transport`: Transport protocol (`stdio` or `sse`). Default: `stdio`
- `--host`: Host to bind server to. Default: `0.0.0.0`
- `--port`: Port for HTTP server. Default: `3000`

**Endpoints when using SSE transport:**
- SSE connection: `http://localhost:3000/sse`
- Messages: `http://localhost:3000/messages` (POST)

**Test the HTTP/SSE server:**
```bash
# In one terminal, start the server
uv run minecraft_mcp.py --transport sse --port 3000

# In another terminal, test the connection
curl http://localhost:3000/sse
```

## Usage

The MCP server can be integrated with various MCP clients depending on the transport mode.

### Example Tool Calls

**Get Players:**
```json
{
  "name": "get_players",
  "arguments": {}
}
```

**Spawn Entity:**
```json
{
  "name": "spawn_entity",
  "arguments": {
    "entity_type": "minecraft:zombie",
    "x": 100,
    "y": 64,
    "z": 200
  }
}
```

**Set Blocks:**
```json
{
  "name": "set_blocks",
  "arguments": {
    "start_x": 100,
    "start_y": 64,
    "start_z": 200,
    "blocks": [
      [
        ["minecraft:stone", "minecraft:dirt"],
        ["minecraft:grass_block", null]
      ]
    ]
  }
}
```

**Get Block Chunk:**
```json
{
  "name": "get_blocks_chunk",
  "arguments": {
    "start_x": 100,
    "start_y": 64,
    "start_z": 200,
    "size_x": 10,
    "size_y": 5,
    "size_z": 10
  }
}
```

**Fill Box with Blocks:**
```json
{
  "name": "fill_box",
  "arguments": {
    "x1": 100,
    "y1": 64,
    "z1": 200,
    "x2": 110,
    "y2": 70,
    "z2": 210,
    "block_type": "minecraft:stone"
  }
}
```

**Get Heightmap for Terrain Analysis:**
```json
{
  "name": "get_heightmap",
  "arguments": {
    "x1": 0,
    "z1": 0,
    "x2": 50,
    "z2": 50,
    "heightmap_type": "WORLD_SURFACE"
  }
}
```

**Broadcast Message to All Players:**
```json
{
  "name": "broadcast_message",
  "arguments": {
    "message": "Server will restart in 5 minutes!",
    "action_bar": false
  }
}
```

**Send Message to Specific Player:**
```json
{
  "name": "send_message_to_player",
  "arguments": {
    "message": "Welcome to the server!",
    "player_name": "Steve",
    "action_bar": true
  }
}
```

## Integration with Claude Desktop

Claude Desktop uses the **stdio transport** (default mode). Add the following to your MCP configuration file:

**macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`
**Windows:** `%APPDATA%\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "minecraft-api": {
      "command": "/path/to/uv",
      "args": [
        "run",
        "--project",
        "/path/to/mcp",
        "/path/to/mcp/minecraft_mcp.py"
      ],
      "cwd": "/path/to/mcp"
    }
  }
}
```

**Note:** Update the paths to match your local installation. The server will use stdio transport by default when launched this way.

## Configuration

The MCP server defaults to connecting to the Minecraft API at `http://localhost:7070`. You can modify the `api_base` parameter in the `MinecraftMCPServer` constructor to use a different endpoint.

## Safety and Limitations

The MCP server includes built-in safety measures:

### Block Operations
- **Fill box limit**: Maximum 100,000 blocks per operation
- **Chunk size limit**: Maximum 64x64x64 blocks per chunk request
- **Heightmap limit**: Maximum 10,000 height points (100x100 area)

### Threading Safety
All Minecraft world operations are executed on the server thread to prevent world corruption and ensure thread safety.

### Performance Considerations
- Large operations may take time to complete (30-second timeout)
- Operations are logged for monitoring and debugging
- Failed block placements are tracked and reported

## Error Handling

The MCP server includes comprehensive error handling for:
- Network connectivity issues
- Invalid API responses
- Malformed tool arguments
- Minecraft server errors
- Invalid block types and coordinates
- Player not found errors

All errors are logged and returned as text content to the MCP client.



While debugging try
`DEBUG=1 npx @modelcontextprotocol/inspector uv run minecraft_mcp.py`

The mcp_config.json I use is copied in the repo, you'll need to update the paths before installing.