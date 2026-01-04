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

Rotation System (Yaw and Pitch):
Player and entity rotations are defined by yaw (horizontal) and pitch (vertical) angles:

Yaw (horizontal rotation):
- Yaw = 0° → facing south (positive Z direction)
- Yaw = 90° → facing west (negative X direction)  
- Yaw = 180° or -180° → facing north (negative Z direction)
- Yaw = -90° → facing east (positive X direction)

Pitch (vertical rotation):
- Pitch = 0° → looking straight ahead (horizontal)
- Pitch = 90° → looking straight down
- Pitch = -90° → looking straight up

Warning! Pay attention to the fact that Minecraft coordinates have 0 yaw as south. In most engines it is east or north so don't let that trip you up.
Similarly positive Z is south. 
"""

import asyncio

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

BASE_URL = "http://localhost:7070"

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
    def __init__(self, api_base: str):
        self.api_base = api_base
        self.server = Server("minecraft-api")
        print(f"Initialized server with API base: {safe_url(api_base)}", file=sys.stderr)
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
                    description="Set blocks in the world using a 3D array of block objects with optional block states",
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
                                "description": "3D array of block objects (use null for no change). Each block object has blockName and optional blockStates.",
                                "items": {
                                    "type": "array",
                                    "items": {
                                        "type": "array",
                                        "items": {
                                            "oneOf": [
                                                {"type": "null"},
                                                {
                                                    "type": "object",
                                                    "properties": {
                                                        "blockName": {
                                                            "type": "string",
                                                            "description": "Block identifier (e.g., 'minecraft:oak_door')"
                                                        },
                                                        "blockStates": {
                                                            "type": "object",
                                                            "description": "Optional block state properties (e.g., {'facing': 'north', 'open': 'false'})",
                                                            "additionalProperties": {"type": "string"}
                                                        }
                                                    },
                                                    "required": ["blockName"]
                                                }
                                            ]
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
                    description="Fill a cuboid/box with a specific block type between two coordinates.",
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
                                "description": "Block type identifier (e.g., 'minecraft:stone', 'minecraft:oak_wood'). 'minecraft:air' can be used to clear an area."
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
                ),
                Tool(
                    name="get_heightmap",
                    description="Get topographical heightmap for a rectangular area - useful for building placement and terrain analysis",
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "x1": {
                                "type": "integer",
                                "description": "First corner X coordinate (east positive, west negative)"
                            },
                            "z1": {
                                "type": "integer",
                                "description": "First corner Z coordinate (south positive, north negative)"
                            },
                            "x2": {
                                "type": "integer",
                                "description": "Second corner X coordinate (east positive, west negative)"
                            },
                            "z2": {
                                "type": "integer",
                                "description": "Second corner Z coordinate (south positive, north negative)"
                            },
                            "heightmap_type": {
                                "type": "string",
                                "description": "Type of heightmap to generate",
                                "enum": ["WORLD_SURFACE", "MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES", "OCEAN_FLOOR"],
                                "default": "WORLD_SURFACE"
                            },
                            "world": {
                                "type": "string",
                                "description": "World name (optional, defaults to minecraft:overworld)",
                                "default": "minecraft:overworld"
                            }
                        },
                        "required": ["x1", "z1", "x2", "z2"]
                    }
                ),
                Tool(
                    name="place_nbt_structure",
                    description="Place an NBT structure file at specified coordinates in the world",
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "nbt_file_data": {
                                "type": "string",
                                "description": "Base64-encoded NBT structure file data"
                            },
                            "filename": {
                                "type": "string",
                                "description": "Original filename of the NBT structure (for reference)"
                            },
                            "x": {
                                "type": "integer",
                                "description": "X coordinate to place structure (east positive, west negative)"
                            },
                            "y": {
                                "type": "integer",
                                "description": "Y coordinate to place structure (elevation: -64 to 320, sea level at 63)"
                            },
                            "z": {
                                "type": "integer",
                                "description": "Z coordinate to place structure (south positive, north negative)"
                            },
                            "world": {
                                "type": "string",
                                "description": "World name (optional, defaults to minecraft:overworld)",
                                "default": "minecraft:overworld"
                            },
                            "rotation": {
                                "type": "string",
                                "description": "Structure rotation (optional, defaults to NONE)",
                                "enum": ["NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"],
                                "default": "NONE"
                            },
                            "include_entities": {
                                "type": "boolean",
                                "description": "Whether to include entities from the NBT structure (default: true)",
                                "default": True
                            },
                            "replace_blocks": {
                                "type": "boolean",
                                "description": "Whether to replace existing blocks (default: true)",
                                "default": True
                            }
                        },
                        "required": ["nbt_file_data", "filename", "x", "y", "z"]
                    }
                ),
                Tool(
                    name="place_door_line",
                    description="Place a line of doors with specified width, facing direction, and properties",
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
                            "width": {
                                "type": "integer",
                                "description": "Number of doors to place in a row (default: 1)",
                                "default": 1,
                                "minimum": 1
                            },
                            "facing": {
                                "type": "string",
                                "description": "Direction the doors should face",
                                "enum": ["north", "south", "east", "west"]
                            },
                            "block_type": {
                                "type": "string",
                                "description": "Door block type (e.g., 'minecraft:oak_door', 'minecraft:iron_door')",
                                "default": "minecraft:oak_door"
                            },
                            "hinge": {
                                "type": "string",
                                "description": "Door hinge position",
                                "enum": ["left", "right"],
                                "default": "left"
                            },
                            "double_doors": {
                                "type": "boolean",
                                "decription": "Whether to alternate door hinges so they pair up to double doors",
                                "default": False
                            },
                            "open": {
                                "type": "boolean",
                                "description": "Whether doors start in open position",
                                "default": False
                            },
                            "world": {
                                "type": "string",
                                "description": "World name (optional, defaults to minecraft:overworld)",
                                "default": "minecraft:overworld"
                            }
                        },
                        "required": ["start_x", "start_y", "start_z", "facing", "block_type"]
                    }
                ),
                Tool(
                    name="place_stairs",
                    description="Build a wide staircase between two points with automatically calculated stair block facing",
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
                            "end_x": {
                                "type": "integer",
                                "description": "Ending X coordinate (east positive, west negative)"
                            },
                            "end_y": {
                                "type": "integer",
                                "description": "Ending Y coordinate (elevation: -64 to 320, sea level at 63)"
                            },
                            "end_z": {
                                "type": "integer",
                                "description": "Ending Z coordinate (south positive, north negative)"
                            },
                            "block_type": {
                                "type": "string",
                                "description": "Base block type for solid sections (e.g., 'minecraft:oak_planks')",
                                "default": "minecraft:stone"
                            },
                            "stair_type": {
                                "type": "string",
                                "description": "Stair block type (e.g., 'minecraft:oak_stairs')",
                                "default": "minecraft:stone_stairs"
                            },
                            "staircase_direction": {
                                "type": "string",
                                "description": "Orientation of the staircase structure (determines width calculation)",
                                "enum": ["north", "south", "east", "west"]
                            },
                            "fill_support": {
                                "type": "boolean",
                                "description": "Whether to fill underneath the staircase for support",
                                "default": False
                            },
                            "world": {
                                "type": "string",
                                "description": "World name (optional, defaults to minecraft:overworld)",
                                "default": "minecraft:overworld"
                            }
                        },
                        "required": ["start_x", "start_y", "start_z", "end_x", "end_y", "end_z", "block_type", "stair_type", "staircase_direction"]
                    }
                ),
                Tool(
                    name="test_server_connection",
                    description="Test if the Minecraft server API is running and responding to requests",
                    inputSchema={
                        "type": "object",
                        "properties": {},
                        "required": []
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
                elif name == "get_heightmap":
                    result = await self.get_heightmap(**arguments)
                    return result.content
                elif name == "place_nbt_structure":
                    result = await self.place_nbt_structure(**arguments)
                    return result.content
                elif name == "place_door_line":
                    result = await self.place_door_line(**arguments)
                    return result.content
                elif name == "place_stairs":
                    result = await self.place_stairs(**arguments)
                    return result.content
                elif name == "test_server_connection":
                    result = await self.test_server_connection()
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
                    facing = yaw_to_cardinal(float(rot['yaw']))
                    result += f"- **{player['name']}** (UUID: {player['uuid']})\n"
                    result += f"  Position: ({pos['x']:.1f}, {pos['y']:.1f}, {pos['z']:.1f})\n"
                    result += f"  Rotation: Yaw {rot['yaw']:.1f}°, Pitch {rot['pitch']:.1f}°\n"
                    result += f"  Facing: {facing}\n"
                
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
    
    async def set_blocks(self, start_x: int, start_y: int, start_z: int, blocks: List[List[List[Optional[Dict[str, Any]]]]], world: str = None) -> CallToolResult:
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
                                block_data = blocks[x][y][z]
                                if isinstance(block_data, dict):
                                    block_id = block_data.get("blockName", "unknown")
                                else:
                                    block_id = str(block_data)  # fallback for any remaining string format
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
    
    async def get_heightmap(self, x1: int, z1: int, x2: int, z2: int, heightmap_type: str = "WORLD_SURFACE", world: str = None) -> CallToolResult:
        """Get heightmap/topography for a rectangular area."""
        try:
            payload = {
                "x1": x1,
                "z1": z1,
                "x2": x2,
                "z2": z2,
                "heightmapType": heightmap_type
            }
            if world:
                payload["world"] = world
            
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/world/blocks/heightmap",
                    json=payload
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("success"):
                    bounds = result["area_bounds"]
                    size = result["size"]
                    height_range = result["height_range"]
                    heights = result["heights"]
                    
                    # Create a formatted height visualization
                    height_text = f"**Heightmap Analysis ({size['x']}x{size['z']} area):**\n"
                    height_text += f"Area: ({bounds['min']['x']}, {bounds['min']['z']}) to ({bounds['max']['x']}, {bounds['max']['z']})\n"
                    height_text += f"World: {result['world']}\n"
                    height_text += f"Heightmap Type: {result['heightmap_type']}\n"
                    height_text += f"Height Range: {height_range['min']} to {height_range['max']} blocks\n"
                    height_text += f"Elevation Difference: {height_range['max'] - height_range['min']} blocks\n\n"
                    
                    # Add terrain analysis
                    total_points = size['x'] * size['z']
                    flat_points = 0
                    steep_points = 0
                    
                    # Analyze terrain characteristics
                    if size['x'] > 1 and size['z'] > 1:
                        for x in range(size['x'] - 1):
                            for z in range(size['z'] - 1):
                                current_height = heights[x][z]
                                right_diff = abs(heights[x + 1][z] - current_height) if x + 1 < size['x'] else 0
                                down_diff = abs(heights[x][z + 1] - current_height) if z + 1 < size['z'] else 0
                                max_diff = max(right_diff, down_diff)
                                
                                if max_diff <= 1:
                                    flat_points += 1
                                elif max_diff >= 3:
                                    steep_points += 1
                        
                        analyzed_points = (size['x'] - 1) * (size['z'] - 1)
                        if analyzed_points > 0:
                            flat_percent = (flat_points / analyzed_points) * 100
                            steep_percent = (steep_points / analyzed_points) * 100
                            height_text += f"**Terrain Analysis:**\n"
                            height_text += f"- Flat areas (≤1 block difference): {flat_percent:.1f}%\n"
                            height_text += f"- Steep areas (≥3 block difference): {steep_percent:.1f}%\n"
                            height_text += f"- Moderate slopes: {100 - flat_percent - steep_percent:.1f}%\n\n"
                    
                    # Add a small sample of the heightmap for visualization (if small enough)
                    if size['x'] <= 20 and size['z'] <= 20:
                        height_text += "**Height Grid:**\n```\n"
                        for z in range(size['z']):
                            row = []
                            for x in range(size['x']):
                                row.append(f"{heights[x][z]:3d}")
                            height_text += " ".join(row) + "\n"
                        height_text += "```\n"
                    else:
                        height_text += f"Area too large to display height grid ({size['x']}x{size['z']} points)\n"
                    
                    # Add building recommendations
                    elevation_diff = height_range['max'] - height_range['min']
                    if elevation_diff <= 2:
                        height_text += "\n**Building Recommendation:** ✅ Excellent for construction - very flat terrain"
                    elif elevation_diff <= 5:
                        height_text += "\n**Building Recommendation:** ✅ Good for construction - minor leveling needed"
                    elif elevation_diff <= 10:
                        height_text += "\n**Building Recommendation:** ⚠️ Moderate terrain - consider terracing or foundation work"
                    else:
                        height_text += "\n**Building Recommendation:** ❌ Challenging terrain - significant earthwork required"
                    
                    return CallToolResult(
                        content=[TextContent(type="text", text=height_text)]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to get heightmap: {result}")]
                    )
        except Exception as e:
            print(f"Error getting heightmap: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def place_nbt_structure(self, nbt_file_data: str, filename: str, x: int, y: int, z: int, world: str = None, 
                                rotation: str = "NONE", include_entities: bool = True, replace_blocks: bool = True) -> CallToolResult:
        """Place an NBT structure file at specified coordinates."""
        import base64
        try:
            # Decode base64 file data
            try:
                missing_padding = len(nbt_file_data) % 4
                if missing_padding:
                    nbt_file_data += '=' * (4 - missing_padding)
                nbt_data = base64.b64decode(nbt_file_data)
            except (base64.binascii.Error, ValueError) as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"❌ Invalid base64 data: {str(e)}")]
                )
                
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"❌ Error decoding base64 data: {str(e)}")]
                )
            
            # Prepare multipart form data
            files = {'nbt_file': (filename, nbt_data, 'application/octet-stream')}
            
            data = {
                'x': str(x),
                'y': str(y), 
                'z': str(z),
                'rotation': rotation,
                'include_entities': str(include_entities).lower(),
                'replace_blocks': str(replace_blocks).lower()
            }
            if world:
                data['world'] = world
            
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/world/structure/place",
                    files=files,
                    data=data,
                    timeout=60.0  # Longer timeout for structure placement
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("success"):
                    pos = result["position"]
                    size = result["structure_size"]
                    return CallToolResult(
                        content=[TextContent(
                            type="text",
                            text=f"✅ Successfully placed NBT structure '{filename}'\n"
                                 f"Position: ({pos['x']}, {pos['y']}, {pos['z']})\n"
                                 f"Size: {size['x']}x{size['y']}x{size['z']} blocks\n"
                                 f"World: {result['world']}\n"
                                 f"Rotation: {result['rotation']}\n"
                                 f"Entities included: {result['include_entities']}\n"
                                 f"Blocks replaced: {result['replace_blocks']}"
                        )]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to place structure: {result.get('error', 'Unknown error')}")]
                    )
        except Exception as e:
            print(f"Error placing NBT structure: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def place_door_line(self, start_x: int, start_y: int, start_z: int, facing: str, block_type: str, 
                             width: int = 1, hinge: str = "left", double_doors: bool = False, open: bool = False, world: str = None) -> CallToolResult:
        """Place a line of doors at specified coordinates."""
        try:
            payload = {
                "startX": start_x,
                "startY": start_y,
                "startZ": start_z,
                "width": width,
                "facing": facing,
                "blockType": block_type,
                "hinge": hinge,
                "open": open,
                "doubleDoors": double_doors
            }
            if world:
                payload["world"] = world
            
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/world/prefabs/door",
                    json=payload
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("success"):
                    return CallToolResult(
                        content=[TextContent(
                            type="text",
                            text=f"✅ Successfully placed {result['doors_placed']} {block_type} doors\n"
                                 f"Position: ({start_x}, {start_y}, {start_z})\n"
                                 f"Facing: {result['facing']}, Hinge: {result['hinge']}, Open: {result['open']}\n"
                                 f"World: {result['world']}"
                        )]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to place doors: {result.get('error', 'Unknown error')}")]
                    )
        except Exception as e:
            print(f"Error placing door line: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def place_stairs(self, start_x: int, start_y: int, start_z: int, end_x: int, end_y: int, end_z: int,
                          block_type: str, stair_type: str, staircase_direction: str, 
                          fill_support: bool = False, world: str = None) -> CallToolResult:
        """Build a wide staircase between two points."""
        try:
            payload = {
                "startX": start_x,
                "startY": start_y,
                "startZ": start_z,
                "endX": end_x,
                "endY": end_y,
                "endZ": end_z,
                "blockType": block_type,
                "stairType": stair_type,
                "staircaseDirection": staircase_direction,
                "fillSupport": fill_support
            }
            if world:
                payload["world"] = world
            
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/world/prefabs/stairs",
                    json=payload
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("success"):
                    return CallToolResult(
                        content=[TextContent(
                            type="text",
                            text=f"✅ Successfully built staircase with {result['blocks_placed']} blocks\n"
                                 f"From: ({start_x}, {start_y}, {start_z}) to ({end_x}, {end_y}, {end_z})\n"
                                 f"Staircase Direction: {result['staircaseDirection']}\n"
                                 f"Block Type: {block_type}, Stair Type: {stair_type}\n"
                                 f"Fill Support: {result['fill_support']}\n"
                                 f"World: {result['world']}"
                        )]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to build staircase: {result.get('error', 'Unknown error')}")]
                    )
        except Exception as e:
            print(f"Error building staircase: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def test_server_connection(self) -> CallToolResult:
        """Test if the Minecraft server API is running and responding."""
        try:
            async with httpx.AsyncClient(timeout=5.0) as client:
                response = await client.get(f"{self.api_base}/")
                response.raise_for_status()
                
                # Check if we get the expected "Hello World" response
                response_text = response.text.strip()
                if response_text == "Hello World":
                    return CallToolResult(
                        content=[TextContent(
                            type="text", 
                            text="✅ Minecraft server is ONLINE and responding correctly"
                        )]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(
                            type="text", 
                            text=f"⚠️ Minecraft server responded but with unexpected content: '{response_text}'"
                        )]
                    )
        except httpx.ConnectError:
            return CallToolResult(
                content=[TextContent(
                    type="text", 
                    text="❌ Cannot connect to Minecraft server - server is OFFLINE or not running"
                )]
            )
        except httpx.TimeoutException:
            return CallToolResult(
                content=[TextContent(
                    type="text", 
                    text="❌ Minecraft server connection TIMEOUT - server may be overloaded"
                )]
            )
        except httpx.HTTPStatusError as e:
            return CallToolResult(
                content=[TextContent(
                    type="text", 
                    text=f"❌ Minecraft server returned HTTP error {e.response.status_code}: {e.response.text}"
                )]
            )
        except Exception as e:
            return CallToolResult(
                content=[TextContent(
                    type="text", 
                    text=f"❌ Error testing server connection: {str(e)}"
                )]
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

def yaw_to_cardinal(yaw: float) -> str:
    # Normalize to [-180, 180)
    yaw = ((yaw + 180) % 360) - 180

    if -45 <= yaw < 45:
        return "SOUTH"
    elif 45 <= yaw < 135:
        return "WEST"
    elif yaw >= 135 or yaw < -135:
        return "NORTH"
    else:  # -135 <= yaw < -45
        return "EAST"

def safe_url(url: str) -> str:
    return str(httpx.URL(url).copy_with(password="****"))

async def main():
    """Main entry point."""
    print("Main function started", file=sys.stderr)
    try:
        server = MinecraftMCPServer(BASE_URL)
        await server.run()
    except Exception as e:
        print(f"Fatal error: {e}", file=sys.stderr)
        raise

if __name__ == "__main__":
    print("Script starting...", file=sys.stderr)
    asyncio.run(main())