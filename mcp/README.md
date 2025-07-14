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

## Setup

1. Install dependencies:
```bash
pip install -r requirements.txt
```

2. Make sure your Minecraft server is running with the Fabric mod on port 7070

3. Run the MCP server:
```bash
python minecraft_mcp.py
```

## Usage

The MCP server communicates via stdio and can be integrated with Claude Desktop or other MCP clients.

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

## Integration with Claude Desktop

To use this with Claude Desktop, add the following to your MCP configuration:

```json
{
  "mcpServers": {
    "minecraft-api": {
      "command": "python",
      "args": ["/path/to/minecraft_mcp.py"],
      "cwd": "/path/to/mcp/directory"
    }
  }
}
```

## Configuration

The MCP server defaults to connecting to the Minecraft API at `http://localhost:7070`. You can modify the `api_base` parameter in the `MinecraftMCPServer` constructor to use a different endpoint.

## Error Handling

The MCP server includes comprehensive error handling for:
- Network connectivity issues
- Invalid API responses
- Malformed tool arguments
- Minecraft server errors

All errors are logged and returned as text content to the MCP client.