"""
Block-related tool handlers for the Minecraft MCP server.

Handles tools for block manipulation, querying, and heightmaps.
"""

import json
from typing import Any, Dict, List, Optional
from mcp.types import CallToolResult, TextContent

from ..client.minecraft_api import MinecraftAPIClient
from ..utils.formatting import (
    format_success_response,
    format_error_response,
    format_list_with_limit,
    format_entity_info,
    format_block_counts,
    format_coordinate_range
)


async def handle_get_blocks(api_client: MinecraftAPIClient, **arguments) -> CallToolResult:
    """
    Get list of all available block types.
    
    Args:
        api_client: The Minecraft API client
        **arguments: Tool arguments (none for this tool)
        
    Returns:
        CallToolResult with formatted block list
    """
    try:
        result = await api_client.get_blocks()
        
        response_text = f"**Available Block Types ({len(result)} total):**\n"
        response_text += format_list_with_limit(result, limit=20, item_formatter=format_entity_info)
        
        return format_success_response(response_text)
    except Exception as e:
        return format_error_response(e, "getting blocks")


async def handle_set_blocks(
    api_client: MinecraftAPIClient,
    start_x: int,
    start_y: int,
    start_z: int,
    blocks: List[List[List[Optional[Dict[str, Any]]]]],
    world: str = None,
    **arguments
) -> CallToolResult:
    """
    Set blocks in the world using a 3D array.
    
    Args:
        api_client: The Minecraft API client
        start_x: Starting X coordinate
        start_y: Starting Y coordinate
        start_z: Starting Z coordinate
        blocks: 3D array of block objects
        world: World name (optional)
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with set blocks result
    """
    try:
        result = await api_client.set_blocks(start_x, start_y, start_z, blocks, world)
        
        if result.get("success"):
            response_text = f"✅ Successfully set {result['blocks_set']} blocks (skipped {result['blocks_skipped']}) in world {result['world']}"
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to set blocks: {result}")]
            )
    except Exception as e:
        return format_error_response(e, "setting blocks")


async def handle_get_blocks_chunk(
    api_client: MinecraftAPIClient,
    start_x: int,
    start_y: int,
    start_z: int,
    size_x: int,
    size_y: int,
    size_z: int,
    world: str = None,
    **arguments
) -> CallToolResult:
    """
    Get a chunk of blocks from the world.

    Args:
        api_client: The Minecraft API client
        start_x: Starting X coordinate
        start_y: Starting Y coordinate
        start_z: Starting Z coordinate
        size_x: Size in X dimension
        size_y: Size in Y dimension
        size_z: Size in Z dimension
        world: World name (optional)
        **arguments: Additional arguments (ignored)

    Returns:
        CallToolResult with raw chunk JSON data
    """
    try:
        result = await api_client.get_blocks_chunk(
            start_x, start_y, start_z,
            size_x, size_y, size_z,
            world
        )

        if result.get("success"):
            return CallToolResult(
                content=[TextContent(type="text", text=json.dumps(result, indent=2))]
            )
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to get blocks: {result}")]
            )
    except Exception as e:
        return format_error_response(e, "getting block chunk")


async def handle_fill_box(
    api_client: MinecraftAPIClient,
    x1: int,
    y1: int,
    z1: int,
    x2: int,
    y2: int,
    z2: int,
    block_type: str,
    world: str = None,
    notify_neighbors: bool = False,
    **arguments
) -> CallToolResult:
    """
    Fill a cuboid/box with a specific block type between two coordinates.

    Args:
        api_client: The Minecraft API client
        x1: First corner X coordinate
        y1: First corner Y coordinate
        z1: First corner Z coordinate
        x2: Second corner X coordinate
        y2: Second corner Y coordinate
        z2: Second corner Z coordinate
        block_type: Block type identifier
        world: World name (optional)
        notify_neighbors: Whether to notify neighboring blocks of changes (default: false)
        **arguments: Additional arguments (ignored)

    Returns:
        CallToolResult with fill result
    """
    try:
        result = await api_client.fill_box(x1, y1, z1, x2, y2, z2, block_type, world, notify_neighbors)

        if result.get("success"):
            range_str = format_coordinate_range(x1, y1, z1, x2, y2, z2)
            response_text = f"✅ Successfully filled {result['blocks_set']} blocks with {block_type} {range_str} in world {result['world']}"
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to fill box: {result}")]
            )
    except Exception as e:
        return format_error_response(e, "filling box")


async def handle_get_heightmap(
    api_client: MinecraftAPIClient,
    x1: int,
    z1: int,
    x2: int,
    z2: int,
    heightmap_type: str = "WORLD_SURFACE",
    world: str = None,
    **arguments
) -> CallToolResult:
    """
    Get topographical heightmap for a rectangular area.
    
    Args:
        api_client: The Minecraft API client
        x1: First corner X coordinate
        z1: First corner Z coordinate
        x2: Second corner X coordinate
        z2: Second corner Z coordinate
        heightmap_type: Type of heightmap
        world: World name (optional)
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with heightmap data
    """
    try:
        result = await api_client.get_heightmap(x1, z1, x2, z2, heightmap_type, world)
        
        if result.get("success"):
            heightmap = result.get("heights") or result.get("heightmap") or []
            size = result.get("size") or {}
            width = size.get("x", len(heightmap))
            length = size.get("z", len(heightmap[0]) if heightmap else 0)

            # Calculate statistics
            all_heights = [h for row in heightmap for h in row]
            min_height = min(all_heights) if all_heights else None
            max_height = max(all_heights) if all_heights else None
            avg_height = (sum(all_heights) / len(all_heights)) if all_heights else None

            response_text = f"**Heightmap Data ({width}x{length}):**\n"
            response_text += f"World: {result.get('world', world)}\n"
            response_text += f"Type: {result.get('heightmap_type', heightmap_type)}\n"

            area_bounds = result.get("area_bounds")
            if area_bounds:
                min_bounds = area_bounds.get("min", {})
                max_bounds = area_bounds.get("max", {})
                response_text += (
                    f"Area: from ({min_bounds.get('x')}, {min_bounds.get('z')}) "
                    f"to ({max_bounds.get('x')}, {max_bounds.get('z')})\n\n"
                )
            else:
                response_text += f"Area: from ({x1}, {z1}) to ({x2}, {z2})\n\n"

            response_text += "**Height Statistics:**\n"
            height_range = result.get("height_range") or {}
            range_min = height_range.get("min", min_height)
            range_max = height_range.get("max", max_height)
            response_text += f"- Minimum: {range_min if range_min is not None else 'n/a'}\n"
            response_text += f"- Maximum: {range_max if range_max is not None else 'n/a'}\n"
            response_text += f"- Average: {avg_height:.1f}\n" if avg_height is not None else "- Average: n/a\n"
            
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to get heightmap: {result}")]
            )
    except Exception as e:
        return format_error_response(e, "getting heightmap")
