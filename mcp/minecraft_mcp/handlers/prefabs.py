"""
Prefab-related tool handlers for the Minecraft MCP server.

Handles tools for placing prefabricated structures like doors, stairs, windows, torches, and signs.
"""

from typing import List, Optional
from mcp.types import CallToolResult, TextContent

from ..client.minecraft_api import MinecraftAPIClient
from ..utils.formatting import (
    format_success_response,
    format_error_response,
    format_success_with_position,
    format_success_with_count
)


async def handle_place_nbt_structure(
    api_client: MinecraftAPIClient,
    nbt_file_data: str,
    filename: str,
    x: int,
    y: int,
    z: int,
    world: Optional[str] = None,
    rotation: str = "NONE",
    include_entities: bool = True,
    replace_blocks: bool = True,
    **arguments
) -> CallToolResult:
    """
    Place an NBT structure file at specified coordinates.
    
    Args:
        api_client: The Minecraft API client
        nbt_file_data: Base64-encoded NBT structure file data
        filename: Original filename of the NBT structure
        x: X coordinate
        y: Y coordinate
        z: Z coordinate
        world: World name (optional)
        rotation: Structure rotation
        include_entities: Whether to include entities
        replace_blocks: Whether to replace existing blocks
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with placement result
    """
    try:
        result = await api_client.place_nbt_structure(
            nbt_file_data, filename, x, y, z, world,
            rotation, include_entities, replace_blocks
        )
        
        if result.get("success"):
            position = {"x": x, "y": y, "z": z}
            extra_info = f"Filename: {filename}\nRotation: {rotation}"
            return format_success_with_position("placed", "NBT structure", position, extra_info)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to place NBT structure: {result}")]
            )
    except Exception as e:
        return format_error_response(e, "placing NBT structure")


async def handle_place_door_line(
    api_client: MinecraftAPIClient,
    start_x: int,
    start_y: int,
    start_z: int,
    facing: str,
    block_type: str,
    width: int = 1,
    hinge: str = "left",
    double_doors: bool = False,
    open: bool = False,
    world: Optional[str] = None,
    **arguments
) -> CallToolResult:
    """
    Place a line of doors with specified width and properties.
    
    Args:
        api_client: The Minecraft API client
        start_x: Starting X coordinate
        start_y: Starting Y coordinate
        start_z: Starting Z coordinate
        facing: Direction the doors should face
        block_type: Door block type
        width: Number of doors to place
        hinge: Door hinge position
        double_doors: Whether to alternate hinges
        open: Whether doors start open
        world: World name (optional)
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with placement result
    """
    try:
        result = await api_client.place_door_line(
            start_x, start_y, start_z, facing, block_type,
            width, hinge, double_doors, open, world
        )
        
        if result.get("success"):
            location = f"at ({start_x}, {start_y}, {start_z})"
            return format_success_with_count("placed", width, "door(s)", location)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to place doors: {result}")]
            )
    except Exception as e:
        return format_error_response(e, "placing doors")


async def handle_place_stairs(
    api_client: MinecraftAPIClient,
    start_x: int,
    start_y: int,
    start_z: int,
    end_x: int,
    end_y: int,
    end_z: int,
    block_type: str,
    stair_type: str,
    staircase_direction: str,
    fill_support: bool = False,
    world: Optional[str] = None,
    **arguments
) -> CallToolResult:
    """
    Build a wide staircase between two points.
    
    Args:
        api_client: The Minecraft API client
        start_x: Starting X coordinate
        start_y: Starting Y coordinate
        start_z: Starting Z coordinate
        end_x: Ending X coordinate
        end_y: Ending Y coordinate
        end_z: Ending Z coordinate
        block_type: Base block type
        stair_type: Stair block type
        staircase_direction: Orientation of the staircase
        fill_support: Whether to fill underneath
        world: World name (optional)
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with placement result
    """
    try:
        result = await api_client.place_stairs(
            start_x, start_y, start_z, end_x, end_y, end_z,
            block_type, stair_type, staircase_direction, fill_support, world
        )
        
        if result.get("success"):
            blocks_placed = result.get("blocks_placed", "unknown")
            location = f"from ({start_x}, {start_y}, {start_z}) to ({end_x}, {end_y}, {end_z})"
            response_text = f"✅ Successfully built staircase with {blocks_placed} blocks {location}"
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to place stairs: {result}")]
            )
    except Exception as e:
        return format_error_response(e, "placing stairs")


async def handle_place_window_pane_wall(
    api_client: MinecraftAPIClient,
    start_x: int,
    start_y: int,
    start_z: int,
    end_x: int,
    end_z: int,
    height: int,
    block_type: str,
    waterlogged: bool = False,
    world: Optional[str] = None,
    **arguments
) -> CallToolResult:
    """
    Create a vertical wall of window panes between two points.
    
    Args:
        api_client: The Minecraft API client
        start_x: Starting X coordinate
        start_y: Starting Y coordinate
        start_z: Starting Z coordinate
        end_x: Ending X coordinate
        end_z: Ending Z coordinate
        height: Height of the wall
        block_type: Pane block type
        waterlogged: Whether panes should be waterlogged
        world: World name (optional)
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with placement result
    """
    try:
        result = await api_client.place_window_pane_wall(
            start_x, start_y, start_z, end_x, end_z,
            height, block_type, waterlogged, world
        )
        
        if result.get("success"):
            blocks_placed = result.get("blocks_placed", "unknown")
            location = f"from ({start_x}, {start_y}, {start_z}) to ({end_x}, {start_y + height - 1}, {end_z})"
            return format_success_with_count("placed", blocks_placed, "pane(s)", location)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to place window panes: {result}")]
            )
    except Exception as e:
        return format_error_response(e, "placing window panes")


async def handle_place_torch(
    api_client: MinecraftAPIClient,
    x: int,
    y: int,
    z: int,
    block_type: str,
    facing: Optional[str] = None,
    world: Optional[str] = None,
    **arguments
) -> CallToolResult:
    """
    Place a single torch at specified coordinates.
    
    Args:
        api_client: The Minecraft API client
        x: X coordinate
        y: Y coordinate
        z: Z coordinate
        block_type: Torch type
        facing: For wall torches, direction the torch faces
        world: World name (optional)
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with placement result
    """
    try:
        result = await api_client.place_torch(x, y, z, block_type, facing, world)
        
        if result.get("success"):
            position = {"x": x, "y": y, "z": z}
            return format_success_with_position("placed", block_type, position)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to place torch: {result}")]
            )
    except Exception as e:
        return format_error_response(e, "placing torch")


async def handle_place_sign(
    api_client: MinecraftAPIClient,
    x: int,
    y: int,
    z: int,
    block_type: str,
    front_lines: Optional[List[str]] = None,
    back_lines: Optional[List[str]] = None,
    facing: Optional[str] = None,
    rotation: Optional[int] = None,
    glowing: bool = False,
    world: Optional[str] = None,
    **arguments
) -> CallToolResult:
    """
    Place a single sign with custom text.
    
    Args:
        api_client: The Minecraft API client
        x: X coordinate
        y: Y coordinate
        z: Z coordinate
        block_type: Sign type
        front_lines: Array of text lines for the front
        back_lines: Array of text lines for the back
        facing: For wall signs, direction the sign faces
        rotation: For standing signs, rotation angle
        glowing: Whether the sign text should glow
        world: World name (optional)
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with placement result
    """
    try:
        result = await api_client.place_sign(
            x, y, z, block_type, front_lines, back_lines,
            facing, rotation, glowing, world
        )
        
        if result.get("success"):
            position = {"x": x, "y": y, "z": z}
            extra_info = None
            if front_lines:
                extra_info = f"Text: {' / '.join(front_lines)}"
            return format_success_with_position("placed", block_type, position, extra_info)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to place sign: {result}")]
            )
    except Exception as e:
        return format_error_response(e, "placing sign")
