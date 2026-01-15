"""
Tool schema definitions for the Minecraft MCP Server.

This module contains all tool schemas separated from their implementations.
Each schema defines the tool's name, description, and input parameters.
"""

from mcp.types import Tool


# World Tools
TOOL_GET_PLAYERS = Tool(
    name="get_players",
    description="Get list of all players currently online with their positions and rotations",
    inputSchema={
        "type": "object",
        "properties": {},
        "required": []
    }
)

TOOL_GET_ENTITIES = Tool(
    name="get_entities",
    description="Get list of all available entity types that can be spawned",
    inputSchema={
        "type": "object",
        "properties": {},
        "required": []
    }
)

TOOL_SPAWN_ENTITY = Tool(
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
)


# Block Tools
TOOL_GET_BLOCKS = Tool(
    name="get_blocks",
    description="Get list of all available block types",
    inputSchema={
        "type": "object",
        "properties": {},
        "required": []
    }
)

TOOL_SET_BLOCKS = Tool(
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
)

TOOL_GET_BLOCKS_CHUNK = Tool(
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
)

TOOL_FILL_BOX = Tool(
    name="fill_box",
    description="Fill a cuboid/box with a specific block type between two coordinates. Can also be used to clear space by filling with minecraft:air.",
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
)

TOOL_GET_HEIGHTMAP = Tool(
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
)


# Message Tools
TOOL_BROADCAST_MESSAGE = Tool(
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
)

TOOL_SEND_MESSAGE_TO_PLAYER = Tool(
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


# Prefab Tools
TOOL_PLACE_NBT_STRUCTURE = Tool(
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
)

TOOL_PLACE_DOOR_LINE = Tool(
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
)

TOOL_PLACE_STAIRS = Tool(
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
)

TOOL_PLACE_WINDOW_PANE_WALL = Tool(
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
)

TOOL_PLACE_TORCH = Tool(
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
)

TOOL_PLACE_SIGN = Tool(
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
)


# System Tools
TOOL_TELEPORT_PLAYER = Tool(
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
)

TOOL_TEST_SERVER_CONNECTION = Tool(
    name="test_server_connection",
    description="Test if the Minecraft server API is running and responding to requests",
    inputSchema={
        "type": "object",
        "properties": {},
        "required": []
    }
)


# Build Management Tools
TOOL_CREATE_BUILD = Tool(
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
)

TOOL_ADD_BUILD_TASK = Tool(
    name="add_build_task",
    description="deprecated, use add_build_task_* tools for clearer inputs",
    inputSchema={
        "type": "object",
        "properties": {},
        "required": []
    }
)

TOOL_ADD_BUILD_TASK_SINGLE_BLOCK_SET = Tool(
    name="add_build_task_single_block_set",
    description="Add a task to place a single block with optional block states to a build queue. This is a simpler alternative to add_build_task_block_set when you only need to place one block.",
    inputSchema={
        "type": "object",
        "properties": {
            "build_id": {
                "type": "string",
                "description": "Build UUID"
            },
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
            "block_name": {
                "type": "string",
                "description": "Block identifier (e.g., 'minecraft:stone', 'minecraft:oak_door')"
            },
            "block_states": {
                "type": "string",
                "description": "Optional JSON string of block state properties (e.g., '{\"facing\": \"south\", \"open\": \"false\"}'). Leave empty or omit for default block states.",
                "default": "{}"
            },
            "world": {
                "type": "string",
                "description": "World name (optional, defaults to minecraft:overworld)",
                "default": "minecraft:overworld"
            },
            "description": {
                "type": "string",
                "description": "Description of task (optional)",
                "default": ""
            }
        },
        "required": ["build_id", "x", "y", "z", "block_name"]
    }
)

TOOL_ADD_BUILD_TASK_BLOCK_SET = Tool(
    name="add_build_task_block_set",
    description="Add a BLOCK_SET task to a build queue for placing multiple blocks in a 3D array. For single blocks, use add_build_task_single_block_set instead.",
    inputSchema={
        "type": "object",
        "properties": {
            "build_id": {
                "type": "string",
                "description": "Build UUID"
            },
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
                "description": "3D array of block objects (use null for no change). Each block object has block_name and optional block_states.",
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
                                        "block_name": {
                                            "type": "string",
                                            "description": "Block identifier (e.g., 'minecraft:oak_door')"
                                        },
                                        "block_states": {
                                            "type": "object",
                                            "description": "Optional block state properties (e.g., {'facing': 'north', 'open': 'false'})",
                                            "additionalProperties": {"type": "string"}
                                        }
                                    },
                                    "required": ["block_name"]
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
            },
            "description": {
                "type": "string",
                "description": "Description of task (optional)",
                "default": ""
            }
        },
        "required": ["build_id", "start_x", "start_y", "start_z", "blocks"]
    }
)

TOOL_ADD_BUILD_TASK_BLOCK_FILL = Tool(
    name="add_build_task_block_fill",
    description="Add a BLOCK_FILL task to a build queue",
    inputSchema={
        "type": "object",
        "properties": {
            "build_id": {
                "type": "string",
                "description": "Build UUID"
            },
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
            },
            "description": {
                "type": "string",
                "description": "Description of task (optional)",
                "default": ""               
            }
        },
        "required": ["build_id", "x1", "y1", "z1", "x2", "y2", "z2", "block_type"]
    }
)

TOOL_ADD_BUILD_TASK_PREFAB_DOOR = Tool(
    name="add_build_task_prefab_door",
    description="Add a PREFAB_DOOR task to a build queue",
    inputSchema={
        "type": "object",
        "properties": {
            "build_id": {
                "type": "string",
                "description": "Build UUID"
            },
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
                "description": "Whether to alternate door hinges so they pair up to double doors",
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
            },
            "description": {
                "type": "string",
                "description": "Description of task (optional)",
                "default": ""               
            }
        },
        "required": ["build_id", "start_x", "start_y", "start_z", "facing", "block_type"]
    }
)

TOOL_ADD_BUILD_TASK_PREFAB_STAIRS = Tool(
    name="add_build_task_prefab_stairs",
    description="Add a PREFAB_STAIRS task to a build queue",
    inputSchema={
        "type": "object",
        "properties": {
            "build_id": {
                "type": "string",
                "description": "Build UUID"
            },
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
            },
            "description": {
                "type": "string",
                "description": "Description of task (optional)",
                "default": ""               
            }
        },
        "required": ["build_id", "start_x", "start_y", "start_z", "end_x", "end_y", "end_z", "block_type", "stair_type", "staircase_direction"]
    }
)

TOOL_ADD_BUILD_TASK_PREFAB_WINDOW = Tool(
    name="add_build_task_prefab_window",
    description="Add a PREFAB_WINDOW task to a build queue",
    inputSchema={
        "type": "object",
        "properties": {
            "build_id": {
                "type": "string",
                "description": "Build UUID"
            },
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
            },
            "description": {
                "type": "string",
                "description": "Description of task (optional)",
                "default": ""               
            }
        },
        "required": ["build_id", "start_x", "start_y", "start_z", "end_x", "end_z", "height", "block_type"]
    }
)

TOOL_ADD_BUILD_TASK_PREFAB_TORCH = Tool(
    name="add_build_task_prefab_torch",
    description="Add a PREFAB_TORCH task to a build queue",
    inputSchema={
        "type": "object",
        "properties": {
            "build_id": {
                "type": "string",
                "description": "Build UUID"
            },
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
            },
            "description": {
                "type": "string",
                "description": "Description of task (optional)",
                "default": ""               
            }
        },
        "required": ["build_id", "x", "y", "z", "block_type"]
    }
)

TOOL_ADD_BUILD_TASK_PREFAB_SIGN = Tool(
    name="add_build_task_prefab_sign",
    description="Add a PREFAB_SIGN task to a build queue",
    inputSchema={
        "type": "object",
        "properties": {
            "build_id": {
                "type": "string",
                "description": "Build UUID"
            },
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
            },
            "description": {
                "type": "string",
                "description": "Description of task (optional)",
                "default": ""               
            }
        },
        "required": ["build_id", "x", "y", "z", "block_type"]
    }
)

TOOL_EXECUTE_BUILD = Tool(
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
)

TOOL_QUERY_BUILDS_BY_LOCATION = Tool(
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
)

TOOL_GET_BUILD_STATUS = Tool(
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


# Export all tool schemas as a list
TOOL_SCHEMAS = [
    # World tools
    TOOL_GET_PLAYERS,
    TOOL_GET_ENTITIES,
    TOOL_SPAWN_ENTITY,
    # Block tools
    TOOL_GET_BLOCKS,
    TOOL_SET_BLOCKS,
    TOOL_GET_BLOCKS_CHUNK,
    TOOL_FILL_BOX,
    TOOL_GET_HEIGHTMAP,
    # Message tools
    TOOL_BROADCAST_MESSAGE,
    TOOL_SEND_MESSAGE_TO_PLAYER,
    # Prefab tools
    TOOL_PLACE_NBT_STRUCTURE,
    TOOL_PLACE_DOOR_LINE,
    TOOL_PLACE_STAIRS,
    TOOL_PLACE_WINDOW_PANE_WALL,
    TOOL_PLACE_TORCH,
    TOOL_PLACE_SIGN,
    # System tools
    TOOL_TELEPORT_PLAYER,
    TOOL_TEST_SERVER_CONNECTION,
    # Build management tools
    TOOL_CREATE_BUILD,
    TOOL_ADD_BUILD_TASK,
    TOOL_ADD_BUILD_TASK_SINGLE_BLOCK_SET,
    TOOL_ADD_BUILD_TASK_BLOCK_SET,
    TOOL_ADD_BUILD_TASK_BLOCK_FILL,
    TOOL_ADD_BUILD_TASK_PREFAB_DOOR,
    TOOL_ADD_BUILD_TASK_PREFAB_STAIRS,
    TOOL_ADD_BUILD_TASK_PREFAB_WINDOW,
    TOOL_ADD_BUILD_TASK_PREFAB_TORCH,
    TOOL_ADD_BUILD_TASK_PREFAB_SIGN,
    TOOL_EXECUTE_BUILD,
    TOOL_QUERY_BUILDS_BY_LOCATION,
    TOOL_GET_BUILD_STATUS,
]
