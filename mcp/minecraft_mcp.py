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
import argparse

import logging
from typing import Any, Dict, List, Optional, Sequence
from urllib.parse import urljoin
import os

import httpx
from dotenv import dotenv_values
from mcp.server import Server
from mcp.server.models import InitializationOptions
from mcp.server.stdio import stdio_server
from mcp.server.sse import SseServerTransport
from starlette.applications import Starlette
from starlette.routing import Route
from starlette.responses import Response
import uvicorn
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

# Local default
BASE_URL = "http://localhost:7070"
# Read from .env file in the same directory as this script
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ENV_FILE = os.path.join(SCRIPT_DIR, ".env")
config = dotenv_values(ENV_FILE)
if "BASE_URL" in config:
    BASE_URL = config["BASE_URL"]

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
                    description="Set blocks in the world using a 3D array of block objects with optional block states. Remember to set any non default states needed.",
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
                    description="Place a line of doors with specified width, facing direction, and properties. Can do single doors.",
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
                    name="place_window_pane_wall",
                    description="Create a vertical wall of window panes between two points with automatic connection states",
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
                            "end_z": {
                                "type": "integer",
                                "description": "Ending Z coordinate (south positive, north negative)"
                            },
                            "height": {
                                "type": "integer",
                                "description": "Height of the window pane wall in blocks",
                                "minimum": 1
                            },
                            "block_type": {
                                "type": "string",
                                "description": "Pane block type (e.g., 'minecraft:glass_pane', 'minecraft:iron_bars')",
                                "default": "minecraft:glass_pane"
                            },
                            "waterlogged": {
                                "type": "boolean",
                                "description": "Whether the panes should be waterlogged",
                                "default": False
                            },
                            "world": {
                                "type": "string",
                                "description": "World name (optional, defaults to minecraft:overworld)",
                                "default": "minecraft:overworld"
                            }
                        },
                        "required": ["start_x", "start_y", "start_z", "end_x", "end_z", "height", "block_type"]
                    }
                ),
                Tool(
                    name="place_torch",
                    description="Place a single torch (ground or wall-mounted) at specified coordinates. For wall torches, facing can be auto-detected or manually specified. Note, wall torches are in the block next to the wall they are attachd to.",
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "x": {
                                "type": "integer",
                                "description": "X coordinate (east positive, west negative)"
                            },
                            "y": {
                                "type": "integer",
                                "description": "Y coordinate (elevation: -64 to 320, sea level at 63)"
                            },
                            "z": {
                                "type": "integer",
                                "description": "Z coordinate (south positive, north negative)"
                            },
                            "block_type": {
                                "type": "string",
                                "description": "Torch type (e.g., 'minecraft:torch' for ground, 'minecraft:wall_torch' for wall-mounted, 'minecraft:soul_wall_torch', 'minecraft:redstone_wall_torch')",
                                "default": "minecraft:wall_torch"
                            },
                            "facing": {
                                "type": "string",
                                "description": "For wall torches: direction the torch faces OUT from the wall (north/south/east/west). If not provided, auto-detects based on adjacent solid blocks.",
                                "enum": ["north", "south", "east", "west"]
                            },
                            "world": {
                                "type": "string",
                                "description": "World name (optional, defaults to minecraft:overworld)",
                                "default": "minecraft:overworld"
                            }
                        },
                        "required": ["x", "y", "z", "block_type"]
                    }
                ),
                Tool(
                    name="place_sign",
                    description="Place a single sign (wall or standing) with custom text on front and back. Supports glowing text.",
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "x": {
                                "type": "integer",
                                "description": "X coordinate (east positive, west negative)"
                            },
                            "y": {
                                "type": "integer",
                                "description": "Y coordinate (elevation: -64 to 320, sea level at 63)"
                            },
                            "z": {
                                "type": "integer",
                                "description": "Z coordinate (south positive, north negative)"
                            },
                            "block_type": {
                                "type": "string",
                                "description": "Sign type (e.g., 'minecraft:oak_wall_sign' for wall, 'minecraft:oak_sign' for standing, 'minecraft:birch_wall_sign', etc.)",
                                "default": "minecraft:oak_wall_sign"
                            },
                            "front_lines": {
                                "type": "array",
                                "description": "Array of 0-4 text lines for the front of the sign",
                                "items": {"type": "string"},
                                "maxItems": 4
                            },
                            "back_lines": {
                                "type": "array",
                                "description": "Array of 0-4 text lines for the back of the sign (optional)",
                                "items": {"type": "string"},
                                "maxItems": 4
                            },
                            "facing": {
                                "type": "string",
                                "description": "For wall signs: direction the sign faces OUT from the wall (north/south/east/west). If not provided, auto-detects based on adjacent solid blocks.",
                                "enum": ["north", "south", "east", "west"]
                            },
                            "rotation": {
                                "type": "integer",
                                "description": "For standing signs: rotation angle 0-15 (0=south, 4=west, 8=north, 12=east). Default: 0",
                                "minimum": 0,
                                "maximum": 15,
                                "default": 0
                            },
                            "glowing": {
                                "type": "boolean",
                                "description": "Whether the sign text should glow (visible in darkness)",
                                "default": False
                            },
                            "world": {
                                "type": "string",
                                "description": "World name (optional, defaults to minecraft:overworld)",
                                "default": "minecraft:overworld"
                            }
                        },
                        "required": ["x", "y", "z", "block_type"]
                    }
                ),
                Tool(
                    name="teleport_player",
                    description="Teleport a player to specified coordinates with optional rotation and dimension",
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "player_name": {
                                "type": "string",
                                "description": "Name of the player to teleport"
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
                            "dimension": {
                                "type": "string",
                                "description": "World dimension (optional, defaults to minecraft:overworld)",
                                "default": "minecraft:overworld"
                            },
                            "yaw": {
                                "type": "number",
                                "description": "Horizontal rotation in degrees (optional, 0=south, 90=west, 180=north, -90=east)",
                                "default": 0.0
                            },
                            "pitch": {
                                "type": "number",
                                "description": "Vertical rotation in degrees (optional, 0=horizontal, 90=down, -90=up)",
                                "default": 0.0
                            }
                        },
                        "required": ["player_name", "x", "y", "z"]
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
                ),
                Tool(
                    name="create_build",
                    description="Create a new build with metadata for organizing building tasks",
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "name": {
                                "type": "string",
                                "description": "Build name"
                            },
                            "description": {
                                "type": "string",
                                "description": "Build description"
                            },
                            "world": {
                                "type": "string",
                                "description": "World name (optional, defaults to minecraft:overworld)",
                                "default": "minecraft:overworld"
                            }
                        },
                        "required": ["name"]
                    }
                ),
                Tool(
                    name="add_build_task",
                    description="Add a building task to a build queue",
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "build_id": {
                                "type": "string",
                                "description": "Build UUID"
                            },
                            "task_type": {
                                "type": "string",
                                "description": "Type of building task",
                                "enum": ["BLOCK_SET", "BLOCK_FILL", "PREFAB_DOOR", "PREFAB_STAIRS", "PREFAB_WINDOW", "PREFAB_TORCH", "PREFAB_SIGN"]
                            },
                            "task_data": {
                                "type": "object",
                                "description": "Task-specific parameters matching the corresponding endpoint schema"
                            }
                        },
                        "required": ["build_id", "task_type", "task_data"]
                    }
                ),
                Tool(
                    name="execute_build",
                    description="Execute all queued tasks in a build",
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "build_id": {
                                "type": "string",
                                "description": "Build UUID"
                            }
                        },
                        "required": ["build_id"]
                    }
                ),
                Tool(
                    name="query_builds_by_location",
                    description="Find builds that intersect with a specified area",
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "min_x": {
                                "type": "integer",
                                "description": "Minimum X coordinate (east positive, west negative)"
                            },
                            "min_y": {
                                "type": "integer",
                                "description": "Minimum Y coordinate (elevation: -64 to 320, sea level at 63)"
                            },
                            "min_z": {
                                "type": "integer",
                                "description": "Minimum Z coordinate (south positive, north negative)"
                            },
                            "max_x": {
                                "type": "integer",
                                "description": "Maximum X coordinate (east positive, west negative)"
                            },
                            "max_y": {
                                "type": "integer",
                                "description": "Maximum Y coordinate (elevation: -64 to 320, sea level at 63)"
                            },
                            "max_z": {
                                "type": "integer",
                                "description": "Maximum Z coordinate (south positive, north negative)"
                            },
                            "world": {
                                "type": "string",
                                "description": "World name (optional, defaults to minecraft:overworld)",
                                "default": "minecraft:overworld"
                            },
                            "include_in_progress": {
                                "type": "boolean",
                                "description": "Whether to include builds that are still in progress (default: false)",
                                "default": False
                            }
                        },
                        "required": ["min_x", "min_y", "min_z", "max_x", "max_y", "max_z"]
                    }
                ),
                Tool(
                    name="get_build_status",
                    description="Get build details, status, and task information",
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "build_id": {
                                "type": "string",
                                "description": "Build UUID"
                            }
                        },
                        "required": ["build_id"]
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
                elif name == "place_window_pane_wall":
                    result = await self.place_window_pane_wall(**arguments)
                    return result.content
                elif name == "place_torch":
                    result = await self.place_torch(**arguments)
                    return result.content
                elif name == "place_sign":
                    result = await self.place_sign(**arguments)
                    return result.content
                elif name == "teleport_player":
                    result = await self.teleport_player(**arguments)
                    return result.content
                elif name == "test_server_connection":
                    result = await self.test_server_connection()
                    return result.content
                elif name == "create_build":
                    result = await self.create_build(**arguments)
                    return result.content
                elif name == "add_build_task":
                    result = await self.add_build_task(**arguments)
                    return result.content
                elif name == "execute_build":
                    result = await self.execute_build(**arguments)
                    return result.content
                elif name == "query_builds_by_location":
                    result = await self.query_builds_by_location(**arguments)
                    return result.content
                elif name == "get_build_status":
                    result = await self.get_build_status(**arguments)
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
    
    async def place_window_pane_wall(self, start_x: int, start_y: int, start_z: int, end_x: int, end_z: int,
                                    height: int, block_type: str, waterlogged: bool = False, world: str = None) -> CallToolResult:
        """Create a vertical wall of window panes between two points."""
        try:
            payload = {
                "startX": start_x,
                "startY": start_y,
                "startZ": start_z,
                "endX": end_x,
                "endZ": end_z,
                "height": height,
                "blockType": block_type,
                "waterlogged": waterlogged
            }
            if world:
                payload["world"] = world
            
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/world/prefabs/window-pane",
                    json=payload
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("success"):
                    return CallToolResult(
                        content=[TextContent(
                            type="text",
                            text=f"✅ Successfully placed {result['panes_placed']} window panes\n"
                                 f"Wall: ({start_x}, {start_y}, {start_z}) to ({end_x}, {start_y + height - 1}, {end_z})\n"
                                 f"Orientation: {result['orientation']}\n"
                                 f"Block Type: {block_type}\n"
                                 f"Waterlogged: {result['waterlogged']}\n"
                                 f"World: {result['world']}"
                        )]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to place window pane wall: {result.get('error', 'Unknown error')}")]
                    )
        except Exception as e:
            print(f"Error placing window pane wall: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )

    async def place_torch(self, x: int, y: int, z: int, block_type: str,
                         facing: str = None, world: str = None) -> CallToolResult:
        """Place a single torch (ground or wall-mounted) at specified coordinates."""
        try:
            payload = {
                "x": x,
                "y": y,
                "z": z,
                "blockType": block_type
            }
            if facing:
                payload["facing"] = facing
            if world:
                payload["world"] = world

            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/world/prefabs/torch",
                    json=payload
                )
                response.raise_for_status()
                result = response.json()

                if result.get("success"):
                    position = result["position"]
                    is_wall_mounted = result.get("wall_mounted", False)

                    if is_wall_mounted:
                        return CallToolResult(
                            content=[TextContent(
                                type="text",
                                text=f"✅ Successfully placed wall torch at ({position['x']}, {position['y']}, {position['z']})\n"
                                     f"Block Type: {result['blockType']}\n"
                                     f"Facing: {result['facing']}\n"
                                     f"World: {result['world']}"
                            )]
                        )
                    else:
                        return CallToolResult(
                            content=[TextContent(
                                type="text",
                                text=f"✅ Successfully placed ground torch at ({position['x']}, {position['y']}, {position['z']})\n"
                                     f"Block Type: {result['blockType']}\n"
                                     f"World: {result['world']}"
                            )]
                        )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to place torch: {result.get('error', 'Unknown error')}")]
                    )
        except Exception as e:
            print(f"Error placing torch: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )

    async def place_sign(self, x: int, y: int, z: int, block_type: str,
                        front_lines: list = None, back_lines: list = None,
                        facing: str = None, rotation: int = None, glowing: bool = False,
                        world: str = None) -> CallToolResult:
        """Place a single sign (wall or standing) with custom text."""
        try:
            payload = {
                "x": x,
                "y": y,
                "z": z,
                "blockType": block_type
            }
            if front_lines:
                payload["frontLines"] = front_lines
            if back_lines:
                payload["backLines"] = back_lines
            if facing:
                payload["facing"] = facing
            if rotation is not None:
                payload["rotation"] = rotation
            if glowing:
                payload["glowing"] = glowing
            if world:
                payload["world"] = world

            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/world/prefabs/sign",
                    json=payload
                )
                response.raise_for_status()
                result = response.json()

                if result.get("success"):
                    position = result["position"]
                    sign_type = result.get("sign_type", "unknown")
                    is_glowing = result.get("glowing", False)

                    response_text = f"✅ Successfully placed {sign_type} sign at ({position['x']}, {position['y']}, {position['z']})\n"
                    response_text += f"Block Type: {result['blockType']}\n"

                    if sign_type == "wall":
                        response_text += f"Facing: {result.get('facing', 'unknown')}\n"
                    elif sign_type == "standing":
                        response_text += f"Rotation: {result.get('rotation', 0)}\n"

                    if is_glowing:
                        response_text += "Glowing: Yes\n"

                    response_text += f"World: {result['world']}"

                    return CallToolResult(
                        content=[TextContent(type="text", text=response_text)]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to place sign: {result.get('error', 'Unknown error')}")]
                    )
        except Exception as e:
            print(f"Error placing sign: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )

    async def teleport_player(self, player_name: str, x: float, y: float, z: float, 
                             dimension: str = None, yaw: float = 0.0, pitch: float = 0.0) -> CallToolResult:
        """Teleport a player to specified coordinates."""
        try:
            payload = {
                "playerName": player_name,
                "x": x,
                "y": y,
                "z": z,
                "yaw": yaw,
                "pitch": pitch
            }
            if dimension:
                payload["dimension"] = dimension
            else:
                payload["dimension"] = "minecraft:overworld"
            
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/players/teleport",
                    json=payload
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("status") == "success":
                    facing = yaw_to_cardinal(yaw)
                    return CallToolResult(
                        content=[TextContent(
                            type="text",
                            text=f"✅ Successfully teleported {result['playerName']} to ({result['x']:.1f}, {result['y']:.1f}, {result['z']:.1f})\n"
                                 f"Dimension: {result['dimension']}\n"
                                 f"Rotation: Yaw {result['yaw']:.1f}°, Pitch {result['pitch']:.1f}°\n"
                                 f"Facing: {facing}"
                        )]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to teleport player: {result.get('message', 'Unknown error')}")]
                    )
        except httpx.HTTPStatusError as e:
            if e.response.status_code == 404:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"❌ Player '{player_name}' not found")]
                )
            else:
                error_data = e.response.json() if e.response.headers.get('content-type', '').startswith('application/json') else {'error': e.response.text}
                return CallToolResult(
                    content=[TextContent(type="text", text=f"❌ Failed to teleport player: {error_data.get('error', 'HTTP error')}")]
                )
        except Exception as e:
            print(f"Error teleporting player: {e}", file=sys.stderr)
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
    
    
    async def create_build(self, name: str, description: str = None, world: str = None) -> CallToolResult:
        """Create a new build with metadata."""
        try:
            payload = {
                "name": name
            }
            if description:
                payload["description"] = description
            if world:
                payload["world"] = world
            else:
                payload["world"] = "minecraft:overworld"
            
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/builds",
                    json=payload
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("success"):
                    build = result["build"]
                    return CallToolResult(
                        content=[TextContent(
                            type="text",
                            text=f"✅ Successfully created build '{build['name']}'\n"
                                 f"Build ID: {build['id']}\n"
                                 f"Description: {build.get('description', 'No description')}\n"
                                 f"World: {build['world']}\n"
                                 f"Status: {build['status']}\n"
                                 f"Created: {build['createdAt']}"
                        )]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to create build: {result.get('error', 'Unknown error')}")]
                    )
        except Exception as e:
            print(f"Error creating build: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def add_build_task(self, build_id: str, task_type: str, task_data: Dict[str, Any]) -> CallToolResult:
        """Add a building task to a build queue."""
        try:
            payload = {
                "taskType": task_type,
                "taskData": task_data
            }
            
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/builds/{build_id}/tasks",
                    json=payload
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("success"):
                    task = result["task"]
                    return CallToolResult(
                        content=[TextContent(
                            type="text",
                            text=f"✅ Successfully added {task_type} task to build\n"
                                 f"Task ID: {task['id']}\n"
                                 f"Build ID: {build_id}\n"
                                 f"Task Order: {task.get('taskOrder', 'N/A')}\n"
                                 f"Status: {task['status']}"
                        )]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to add task: {result.get('error', 'Unknown error')}")]
                    )
        except Exception as e:
            print(f"Error adding build task: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def execute_build(self, build_id: str) -> CallToolResult:
        """Execute all queued tasks in a build."""
        try:
            async with httpx.AsyncClient(timeout=120.0) as client:  # Longer timeout for build execution
                response = await client.post(
                    f"{self.api_base}/api/builds/{build_id}/execute"
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("success"):
                    return CallToolResult(
                        content=[TextContent(
                            type="text",
                            text=f"✅ Successfully executed build {build_id}\n"
                                 f"Tasks executed: {result['tasksExecuted']}\n"
                                 f"Tasks failed: {result['tasksFailed']}\n"
                                 f"Build status: {result.get('buildStatus', 'completed')}\n"
                                 f"Execution time: {result.get('executionTime', 'N/A')}"
                        )]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to execute build: {result.get('error', 'Unknown error')}")]
                    )
        except Exception as e:
            print(f"Error executing build: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def query_builds_by_location(self, min_x: int, min_y: int, min_z: int, 
                                     max_x: int, max_y: int, max_z: int,
                                     world: str = None, include_in_progress: bool = False) -> CallToolResult:
        """Find builds that intersect with a specified area."""
        try:
            payload = {
                "minX": min_x,
                "minY": min_y,
                "minZ": min_z,
                "maxX": max_x,
                "maxY": max_y,
                "maxZ": max_z,
                "includeInProgress": include_in_progress
            }
            if world:
                payload["world"] = world
            else:
                payload["world"] = "minecraft:overworld"
            
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.api_base}/api/builds/query-location",
                    json=payload
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("success"):
                    builds = result["builds"]
                    if not builds:
                        return CallToolResult(
                            content=[TextContent(
                                type="text",
                                text=f"No builds found in area ({min_x}, {min_y}, {min_z}) to ({max_x}, {max_y}, {max_z})"
                            )]
                        )

                    result_text = f"**Found {len(builds)} builds in area ({min_x}, {min_y}, {min_z}) to ({max_x}, {max_y}, {max_z}):**\n\n"

                    for buildResult in builds:
                        build = buildResult['build']
                        intersecting_tasks = buildResult.get('intersectingTasks', [])
                        result_text += f"**{build['name']}** (ID: {build['id']})\n"
                        result_text += f"- Status: {build['status']}\n"
                        result_text += f"- Description: {build.get('description', 'No description')}\n"
                        result_text += f"- Created: {build.get('createdAt', 'N/A')}\n"
                        if build.get('completedAt'):
                            result_text += f"- Completed: {build['completedAt']}\n"
                        result_text += f"- Intersecting Tasks: {len(intersecting_tasks)}\n"
                        result_text += f"- World: {build['world']}\n\n"

                    return CallToolResult(
                        content=[TextContent(type="text", text=result_text)]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to query builds: {result.get('error', 'Unknown error')}")]
                    )
        except Exception as e:
            print(f"Error querying builds by location: {e}", file=sys.stderr)
            return CallToolResult(
                content=[TextContent(type="text", text=f"Error connecting to Minecraft API: {str(e)}")]
            )
    
    async def get_build_status(self, build_id: str) -> CallToolResult:
        """Get build details, status, and task information."""
        try:
            async with httpx.AsyncClient() as client:
                response = await client.get(
                    f"{self.api_base}/api/builds/{build_id}"
                )
                response.raise_for_status()
                result = response.json()
                
                if result.get("success"):
                    build = result["build"]
                    tasks = result.get("tasks", [])

                    result_text = f"**Build Status: {build['name']}**\n\n"
                    result_text += f"**Build Details:**\n"
                    result_text += f"- ID: {build['id']}\n"
                    result_text += f"- Name: {build['name']}\n"
                    result_text += f"- Description: {build.get('description', 'No description')}\n"
                    result_text += f"- Status: {build['status']}\n"
                    result_text += f"- World: {build['world']}\n"
                    result_text += f"- Created: {build.get('createdAt', 'N/A')}\n"
                    if build.get('completedAt'):
                        result_text += f"- Completed: {build['completedAt']}\n"

                    result_text += f"\n**Task Queue ({len(tasks)} tasks):**\n"
                    if not tasks:
                        result_text += "No tasks in queue\n"
                    else:
                        for task in tasks:
                            status_icon = "✅" if task['status'] == 'completed' else "❌" if task['status'] == 'failed' else "⏳"
                            result_text += f"{status_icon} Task {task.get('taskOrder', 'N/A')}: {task.get('taskType', 'unknown')} - {task['status']}\n"
                            if task.get('errorMessage'):
                                result_text += f"   Error: {task['errorMessage']}\n"

                    return CallToolResult(
                        content=[TextContent(type="text", text=result_text)]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"❌ Failed to get build status: {result.get('error', 'Unknown error')}")]
                    )
        except Exception as e:
            print(f"Error getting build status: {e}", file=sys.stderr)
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
    
    async def run_stdio(self):
        """Run the MCP server with stdio transport."""
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

    def create_sse_app(self) -> Starlette:
        """Create a Starlette app for SSE transport."""
        sse = SseServerTransport("/messages")

        async def handle_sse(request):
            async with sse.connect_sse(
                request.scope,
                request.receive,
                request._send
            ) as streams:
                await self.server.run(
                    streams[0],
                    streams[1],
                    InitializationOptions(
                        server_name="minecraft-api",
                        server_version="1.0.0",
                        capabilities=self.server.get_capabilities(
                            notification_options=NotificationOptions(),
                            experimental_capabilities={}
                        )
                    )
                )

        async def handle_messages(request):
            await sse.handle_post_message(request.scope, request.receive, request._send)
            return Response()

        return Starlette(
            debug=True,
            routes=[
                Route("/sse", endpoint=handle_sse),
                Route("/messages", endpoint=handle_messages, methods=["POST"]),
            ],
        )

    async def run(self):
        """Run the MCP server (defaults to stdio for backward compatibility)."""
        await self.run_stdio()

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
    parser = argparse.ArgumentParser(description="Minecraft MCP Server")
    parser.add_argument(
        "--transport",
        choices=["stdio", "sse"],
        default="stdio",
        help="Transport protocol to use (default: stdio)"
    )
    parser.add_argument(
        "--host",
        default="0.0.0.0",
        help="Host to bind HTTP/SSE server to (default: 0.0.0.0)"
    )
    parser.add_argument(
        "--port",
        type=int,
        default=3000,
        help="Port for HTTP/SSE server (default: 3000)"
    )

    args = parser.parse_args()

    print(f"Main function started with transport: {args.transport}", file=sys.stderr)

    try:
        server = MinecraftMCPServer(BASE_URL)

        if args.transport == "stdio":
            await server.run_stdio()
        elif args.transport == "sse":
            print(f"Starting HTTP/SSE server on {args.host}:{args.port}", file=sys.stderr)
            print(f"SSE endpoint: http://{args.host}:{args.port}/sse", file=sys.stderr)
            print(f"Messages endpoint: http://{args.host}:{args.port}/messages", file=sys.stderr)
            app = server.create_sse_app()
            config = uvicorn.Config(
                app,
                host=args.host,
                port=args.port,
                log_level="info"
            )
            server_instance = uvicorn.Server(config)
            await server_instance.serve()
    except Exception as e:
        print(f"Fatal error: {e}", file=sys.stderr)
        raise

if __name__ == "__main__":
    print("Script starting...", file=sys.stderr)
    asyncio.run(main())