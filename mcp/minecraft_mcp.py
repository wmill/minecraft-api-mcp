#!/usr/bin/env python3
"""
MCP Server for Minecraft Fabric Mod API
Provides tools to interact with the Minecraft server through HTTP endpoints.

Coordinate System:
World coordinates are based on a grid where three lines or axes intersect at the origin point.

- The x-axis indicates the player's distance east (positive) or west (negative) of the origin point—i.e., the longitude
- The z-axis indicates the player's distance south (positive) or north (negative) of the origin point—i.e., the latitude  
- The y-axis indicates how high or low (from 0 to 255 (pre 1.18) or -64 to 320 (from 1.18), with 63 being sea level) the player is—i.e., the elevation

The unit length of the three axes equals the side of one block. And, in terms of real-world measurement, one block equals 1 cubic meter.
"""

import asyncio
import json
import logging
from typing import Any, Dict, List, Optional, Sequence
from urllib.parse import urljoin
import os

import httpx
from mcp.server import Server
from mcp.server.models import InitializationOptions
from mcp.server.stdio import stdio_server
from mcp.types import (
    CallToolRequest,
    CallToolResult,
    ListToolsRequest,
    TextContent,
    Tool,
    ContentBlock,
)
import sys

from mcp.server.lowlevel import NotificationOptions

if os.getenv('DEBUG'):
    try:
        import debugpy
        debugpy.listen(("localhost", 5678))
        print("Debugger listening on port 5678", file=sys.stderr)
        print("Attach your debugger now or set breakpoints and continue", file=sys.stderr)
        # Uncomment the next line if you want to wait for debugger to attach
        # debugpy.wait_for_client()
    except ImportError:
        print("debugpy not installed. Install with: pip install debugpy", file=sys.stderr)

# Configure logging
logging.basicConfig(level=logging.DEBUG)
logger = logging.getLogger(__name__)


# Add debug output to stderr so it shows in Claude Desktop logs
print("Starting Minecraft MCP Server...", file=sys.stderr)

class MinecraftMCPServer:
    def __init__(self, api_base: str = "http://localhost:7070"):
        self.api_base = api_base
        self.server = Server("minecraft-api")
        print(f"Initialized server with API base: {api_base}", file=sys.stderr)
        self.setup_handlers()
    
    def setup_handlers(self):
        print("Setting up handlers...", file=sys.stderr)
        
        @self.server.list_tools()
        async def list_tools() -> List[Tool]:
            """List available tools for Minecraft API interaction."""
            print("list_tools called", file=sys.stderr)
            return [
                Tool(
                    name="get_players",
                    description="Get list of all players currently online with their positions and rotations",
                    inputSchema={
                        "type": "object",
                        "properties": {},
                        "required": []
                    }
                ),
                Tool(
                    name="get_entities",
                    description="Get list of all available entity types that can be spawned",
                    inputSchema={
                        "type": "object",
                        "properties": {},
                        "required": []
                    }
                ),
                Tool(
                    name="spawn_entity",
                    description="Spawn an entity at specified coordinates",
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "entity_type": {
                                "type": "string",
                                "description": "Entity type (e.g., 'minecraft:zombie', 'minecraft:cow')"
                            },
                            "x": {
                                "type": "number",
                                "description": "X coordinate (east positive, west negative)"
                            },
                            "y": {
                                "type": "number", 
                                "description": "Y coordinate (elevation: -64 to 320, sea level at 63)"
                            },
                            "z": {
                                "type": "number",
                                "description": "Z coordinate (south positive, north negative)"
                            },
                            "world": {
                                "type": "string",
                                "description": "World name (optional, defaults to minecraft:overworld)",
                                "default": "minecraft:overworld"
                            }
                        },
                        "required": ["entity_type", "x", "y", "z"]
                    }
                ),
                Tool(
                    name="get_blocks",
                    description="Get list of all available block types",
                    inputSchema={
                        "type": "object",
                        "properties": {},
                        "required": []
                    }
                ),
                Tool(
                    name="set_blocks",
                    description="Set blocks in the world using a 3D array",
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "start_x": {
                                "type": "integer",
                                "description": "Starting X coordinate (east positive, west negative)"
                            },
                            "start_y": {
                                "type": "integer",
                                "description": "Starting Y coordinate (elevation: -64 to 320, sea level at 63)"
                            },
                            "start_z": {
                                "type": "integer",
                                "description": "Starting Z coordinate (south positive, north negative)"
                            },
                            "blocks": {
                                "type": "array",
                                "description": "3D array of block IDs (use null for no change)",
                                "items": {
                                    "type": "array",
                                    "items": {
                                        "type": "array",
                                        "items": {
                                            "type": ["string", "null"]
                                        }
                                    }
                                }
                            },
                            "world": {
                                "type": "string",
                                "description": "World name (optional, defaults to minecraft:overworld)",
                                "default": "minecraft:overworld"
                            }
                        },
                        "required": ["start_x", "start_y", "start_z", "blocks"]
                    }
                ),
                Tool(
                    name="get_blocks_chunk",
                    description="Get a chunk of blocks from the world",
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "start_x": {
                                "type": "integer",
                                "description": "Starting X coordinate (east positive, west negative)"
                            },
                            "start_y": {
                                "type": "integer",
                                "description": "Starting Y coordinate (elevation: -64 to 320, sea level at 63)"
                            },
                            "start_z": {
                                "type": "integer",
                                "description": "Starting Z coordinate (south positive, north negative)"
                            },
                            "size_x": {
                                "type": "integer",
                                "description": "Size in X dimension (max 64)"
                            },
                            "size_y": {
                                "type": "integer",
                                "description": "Size in Y dimension (max 64)"
                            },
                            "size_z": {
                                "type": "integer",
                                "description": "Size in Z dimension (max 64)"
                            },
                            "world": {
                                "type": "string",
                                "description": "World name (optional, defaults to minecraft:overworld)",
                                "default": "minecraft:overworld"
                            }
                        },
                        "required": ["start_x", "start_y", "start_z", "size_x", "size_y", "size_z"]
                    }
                ),
                Tool(
                    name="fill_box",
                    description="Fill a cuboid/box with a specific block type between two coordinates",
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "x1": {
                                "type": "integer",
                                "description": "First corner X coordinate (east positive, west negative)"
                            },
                            "y1": {
                                "type": "integer",
                                "description": "First corner Y coordinate (elevation: -64 to 320, sea level at 63)"
                            },
                            "z1": {
                                "type": "integer",
                                "description": "First corner Z coordinate (south positive, north negative)"
                            },
                            "x2": {
                                "type": "integer",
                                "description": "Second corner X coordinate (east positive, west negative)"
                            },
                            "y2": {
                                "type": "integer",
                                "description": "Second corner Y coordinate (elevation: -64 to 320, sea level at 63)"
                            },
                            "z2": {
                                "type": "integer",
                                "description": "Second corner Z coordinate (south positive, north negative)"
                            },
                            "block_type": {
                                "type": "string",
                                "description": "Block type identifier (e.g., 'minecraft:stone', 'minecraft:oak_wood')"
                            },
                            "world": {
                                "type": "string",
                                "description": "World name (optional, defaults to minecraft:overworld)",
                                "default": "minecraft:overworld"
                            }
                        },
                        "required": ["x1", "y1", "z1", "x2", "y2", "z2", "block_type"]
                    }
                ),
                Tool(
                    name="broadcast_message",
                    description="Send a message to all players on the server",
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "message": {
                                "type": "string",
                                "description": "Message text to send to all players"
                            },
                            "action_bar": {
                                "type": "boolean",
                                "description": "If true, shows message in action bar above hotbar. If false, shows in chat",
                                "default": False
                            }
                        },
                        "required": ["message"]
                    }
                ),
                Tool(
                    name="send_message_to_player",
                    description="Send a message to a specific player",
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "message": {
                                "type": "string",
                                "description": "Message text to send to the player"
                            },
                            "player_uuid": {
                                "type": "string",
                                "description": "Player's UUID (takes priority over name if both provided)"
                            },
                            "player_name": {
                                "type": "string",
                                "description": "Player's name (used if UUID not provided)"
                            },
                            "action_bar": {
                                "type": "boolean",
                                "description": "If true, shows message in action bar above hotbar. If false, shows in chat",
                                "default": False
                            }
                        },
                        "required": ["message"]
                    }
                )
            ]
        
        @self.server.call_tool()
        async def call_tool(name: str, arguments: Dict[str, Any]) -> List[ContentBlock]:
            """Handle tool calls."""
            print(f"call_tool: {name} with args: {arguments}", file=sys.stderr)
            try:
                if name == "get_players":
                    result = await self.get_players()
                    return result.content
                elif name == "get_entities":
                    result = await self.get_entities()
                    return result.content
                elif name == "spawn_entity":
                    result = await self.spawn_entity(**arguments)
                    return result.content
                elif name == "get_blocks":
                    result = await self.get_blocks()
                    return result.content
                elif name == "set_blocks":
                    result = await self.set_blocks(**arguments)
                    return result.content
                elif name == "get_blocks_chunk":
                    result = await self.get_blocks_chunk(**arguments)
                    return result.content
                elif name == "fill_box":
                    result = await self.fill_box(**arguments)
                    return result.content
                elif name == "broadcast_message":
                    result = await self.broadcast_message(**arguments)
                    return result.content
                elif name == "send_message_to_player":
                    result = await self.send_message_to_player(**arguments)
                    return result.content
                else:
                    raise ValueError(f"Unknown tool: {name}")
            except Exception as e:
                logger.error(f"Error in tool {name}: {e}")
                print(f"Tool error: {e}", file=sys.stderr)
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error: {str(e)}")]
                )
    
    async def get_players(self) -> CallToolResult:
        """Get list of online players."""
        print("Getting players...", file=sys.stderr)
        try:
            async with httpx.AsyncClient() as client:
                response = await client.get(f"{self.api_base}/api/world/players")
                response.raise_for_status()
                players = response.json()
                
                result = "**Online Players:**\n"
                for player in players:
                    pos = player["position"]
                    rot = player["rotation"]
                    result += f"- **{player['name']}** (UUID: {player['uuid']})\n"
                    result += f"  Position: ({pos['x']:.1f}, {pos['y']:.1f}, {pos['z']:.1f})\n"
                    result += f"  Rotation: Yaw {rot['yaw']:.1f}°, Pitch {rot['pitch']:.1f}°\n"
                
                return CallToolResult(
                    content=[TextContent(type="text", text=result)]
                )
        except Exception as e:
            print(f"Error getting players: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def get_entities(self) -> CallToolResult:
        """Get list of available entity types."""
        try:
            async with httpx.AsyncClient() as client:
                response = await client.get(f"{self.api_base}/api/world/entities")
                response.raise_for_status()
                entities = response.json()
                
                result = f"**Available Entity Types ({len(entities)} total):**\n"
                for entity in entities[:20]:  # Show first 20
                    result += f"- {entity['id']} ({entity['display_name']})\n"
                
                if len(entities) > 20:
                    result += f"... and {len(entities) - 20} more entities\n"
                
                return CallToolResult(
                    content=[TextContent(type="text", text=result)]
                )
        except Exception as e:
            print(f"Error getting entities: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def spawn_entity(self, entity_type: str, x: float, y: float, z: float, world: str = None) -> CallToolResult:
        """Spawn an entity at specified coordinates."""
        try:
            payload = {
                "type": entity_type,
                "x": x,
                "y": y,
                "z": z
            }
            if world:
                payload["world"] = world
            
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/world/entities/spawn",
                    json=payload
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("success"):
                    pos = result["position"]
                    return CallToolResult(
                        content=[TextContent(
                            type="text",
                            text=f"✅ Successfully spawned {result['type']} at ({pos['x']:.1f}, {pos['y']:.1f}, {pos['z']:.1f})\nEntity UUID: {result['uuid']}"
                        )]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to spawn entity: {result}")]
                    )
        except Exception as e:
            print(f"Error spawning entity: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def get_blocks(self) -> CallToolResult:
        """Get list of available block types."""
        try:
            async with httpx.AsyncClient() as client:
                response = await client.get(f"{self.api_base}/api/world/blocks/list")
                response.raise_for_status()
                blocks = response.json()
                
                result = f"**Available Block Types ({len(blocks)} total):**\n"
                for block in blocks[:20]:  # Show first 20
                    result += f"- {block['id']} ({block['display_name']})\n"
                
                if len(blocks) > 20:
                    result += f"... and {len(blocks) - 20} more blocks\n"
                
                return CallToolResult(
                    content=[TextContent(type="text", text=result)]
                )
        except Exception as e:
            print(f"Error getting blocks: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def set_blocks(self, start_x: int, start_y: int, start_z: int, blocks: List[List[List[Optional[str]]]], world: str = None) -> CallToolResult:
        """Set blocks in the world."""
        try:
            payload = {
                "startX": start_x,
                "startY": start_y,
                "startZ": start_z,
                "blocks": blocks
            }
            if world:
                payload["world"] = world
            
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/world/blocks/set",
                    json=payload
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("success"):
                    return CallToolResult(
                        content=[TextContent(
                            type="text",
                            text=f"✅ Successfully set {result['blocks_set']} blocks (skipped {result['blocks_skipped']}) in world {result['world']}"
                        )]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to set blocks: {result}")]
                    )
        except Exception as e:
            print(f"Error setting blocks: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def get_blocks_chunk(self, start_x: int, start_y: int, start_z: int, size_x: int, size_y: int, size_z: int, world: str = None) -> CallToolResult:
        """Get a chunk of blocks from the world."""
        try:
            payload = {
                "startX": start_x,
                "startY": start_y,
                "startZ": start_z,
                "sizeX": size_x,
                "sizeY": size_y,
                "sizeZ": size_z
            }
            if world:
                payload["world"] = world
            
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/world/blocks/chunk",
                    json=payload
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("success"):
                    blocks = result["blocks"]
                    block_counts = {}
                    
                    # Count block types
                    for x in range(len(blocks)):
                        for y in range(len(blocks[x])):
                            for z in range(len(blocks[x][y])):
                                block_id = blocks[x][y][z]
                                block_counts[block_id] = block_counts.get(block_id, 0) + 1
                    
                    result_text = f"**Chunk Data ({size_x}x{size_y}x{size_z} blocks):**\n"
                    result_text += f"World: {result['world']}\n"
                    result_text += f"Start Position: ({start_x}, {start_y}, {start_z})\n\n"
                    result_text += "**Block Composition:**\n"
                    
                    for block_id, count in sorted(block_counts.items(), key=lambda x: x[1], reverse=True):
                        percentage = (count / (size_x * size_y * size_z)) * 100
                        result_text += f"- {block_id}: {count} blocks ({percentage:.1f}%)\n"
                    
                    return CallToolResult(
                        content=[TextContent(type="text", text=result_text)]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to get blocks: {result}")]
                    )
        except Exception as e:
            print(f"Error getting block chunk: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def fill_box(self, x1: int, y1: int, z1: int, x2: int, y2: int, z2: int, block_type: str, world: str = None) -> CallToolResult:
        """Fill a box/cuboid with a specific block type between two coordinates."""
        try:
            payload = {
                "x1": x1,
                "y1": y1,
                "z1": z1,
                "x2": x2,
                "y2": y2,
                "z2": z2,
                "blockType": block_type
            }
            if world:
                payload["world"] = world
            
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/world/blocks/fill",
                    json=payload
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("success"):
                    bounds = result["box_bounds"]
                    return CallToolResult(
                        content=[TextContent(
                            type="text",
                            text=f"✅ Successfully filled box with {result['blocks_set']} blocks of {block_type}\n"
                                 f"Box bounds: ({bounds['min']['x']}, {bounds['min']['y']}, {bounds['min']['z']}) to "
                                 f"({bounds['max']['x']}, {bounds['max']['y']}, {bounds['max']['z']})\n"
                                 f"World: {result['world']}\n"
                                 f"Total blocks: {result['total_blocks']}, Failed: {result['blocks_failed']}"
                        )]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to fill box: {result}")]
                    )
        except Exception as e:
            print(f"Error filling box: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def broadcast_message(self, message: str, action_bar: bool = False) -> CallToolResult:
        """Send a message to all players on the server."""
        try:
            payload = {
                "message": message,
                "actionBar": action_bar
            }
            
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/message/broadcast",
                    json=payload
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("success"):
                    location = "action bar" if action_bar else "chat"
                    return CallToolResult(
                        content=[TextContent(
                            type="text",
                            text=f"✅ Message sent to {result['playerCount']} players in {location}\n"
                                 f"Message: \"{message}\""
                        )]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to broadcast message: {result}")]
                    )
        except Exception as e:
            print(f"Error broadcasting message: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def send_message_to_player(self, message: str, player_uuid: str = None, player_name: str = None, action_bar: bool = False) -> CallToolResult:
        """Send a message to a specific player."""
        try:
            if not player_uuid and not player_name:
                return CallToolResult(
                    content=[TextContent(type="text", text="❌ Must provide either player_uuid or player_name")]
                )
            
            payload = {
                "message": message,
                "actionBar": action_bar
            }
            if player_uuid:
                payload["uuid"] = player_uuid
            if player_name:
                payload["name"] = player_name
            
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/message/player",
                    json=payload
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("success"):
                    location = "action bar" if action_bar else "chat"
                    target = f"UUID {player_uuid}" if player_uuid else f"player {player_name}"
                    return CallToolResult(
                        content=[TextContent(
                            type="text",
                            text=f"✅ Message sent to {target} in {location}\n"
                                 f"Message: \"{message}\""
                        )]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to send message: {result}")]
                    )
        except Exception as e:
            print(f"Error sending message to player: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def run(self):
        """Run the MCP server."""
        print("Starting MCP server stdio connection...", file=sys.stderr)
        async with stdio_server() as (read_stream, write_stream):
            print("MCP server connected, initializing...", file=sys.stderr)
            await self.server.run(
                read_stream,
                write_stream,
                InitializationOptions(
                    server_name="minecraft-api",
                    server_version="1.0.0",
                    capabilities=self.server.get_capabilities(
                        notification_options=NotificationOptions(),
                        experimental_capabilities={}
                    )
                )
            )

async def main():
    """Main entry point."""
    print("Main function started", file=sys.stderr)
    try:
        server = MinecraftMCPServer()
        await server.run()
    except Exception as e:
        print(f"Fatal error: {e}", file=sys.stderr)
        raise

if __name__ == "__main__":
    print("Script starting...", file=sys.stderr)
    asyncio.run(main())