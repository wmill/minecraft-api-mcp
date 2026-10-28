"""
Block-related tool handlers for the Minecraft MCP server.

Handles tools for block manipulation, querying, and heightmaps.
"""

from ..client.operations import PendingOperation, pending_result

import base64
import json
from typing import Any, Dict, List, Optional
from mcp.types import CallToolResult, ImageContent, TextContent

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
    except PendingOperation as pending:
        return pending_result(pending)
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
    except PendingOperation as pending:
        return pending_result(pending)
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
        # Limit total blocks to 125 (5x5x5) to avoid large JSON responses
        total_blocks = size_x * size_y * size_z
        if total_blocks > 125:
            return CallToolResult(
                content=[TextContent(
                    type="text",
                    text=f"❌ Chunk size too large: {size_x}x{size_y}x{size_z} = {total_blocks} blocks. Maximum is 125 blocks (e.g., 5x5x5)."
                )]
            )

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
    except PendingOperation as pending:
        return pending_result(pending)
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
    except PendingOperation as pending:
        return pending_result(pending)
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
        CallToolResult with raw heightmap JSON data
    """
    try:
        result = await api_client.get_heightmap(x1, z1, x2, z2, heightmap_type, world)

        if result.get("success"):
            return CallToolResult(
                content=[TextContent(type="text", text=json.dumps(result, indent=2))]
            )
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to get heightmap: {result}")]
            )
    except PendingOperation as pending:
        return pending_result(pending)
    except Exception as e:
        return format_error_response(e, "getting heightmap")


async def handle_summarize_heightmap(
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
    Get a summarized terrain analysis for a rectangular heightmap area.
    """
    try:
        result = await api_client.get_heightmap(x1, z1, x2, z2, heightmap_type, world)

        if result.get("success"):
            heightmap = result.get("heights") or []
            size = result.get("size") or {}
            width = size.get("x", len(heightmap))
            length = size.get("z", len(heightmap[0]) if heightmap else 0)

            all_heights = [h for row in heightmap for h in row]
            min_height = min(all_heights) if all_heights else None
            max_height = max(all_heights) if all_heights else None
            avg_height = (sum(all_heights) / len(all_heights)) if all_heights else None

            response_text = f"**Heightmap Summary ({width}x{length}):**\n"
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
                content=[TextContent(type="text", text=f"❌ Failed to summarize heightmap: {result}")]
            )
    except PendingOperation as pending:
        return pending_result(pending)
    except Exception as e:
        return format_error_response(e, "summarizing heightmap")


HEIGHTMAP_PREVIEW_ADVISORY = (
    "This is a flat-shaded isometric surface preview derived from the selected "
    "heightmap. It is not a textured world render, and it only shows one "
    "surface voxel per sampled column."
)


async def handle_preview_heightmap(
    api_client: MinecraftAPIClient,
    x1: int,
    z1: int,
    x2: int,
    z2: int,
    heightmap_type: str = "WORLD_SURFACE",
    world: str = None,
    iso_scale: Optional[int] = None,
    view_direction: Optional[str] = None,
    **arguments
) -> CallToolResult:
    """
    Render an isometric PNG terrain preview derived from sampled heightmap data.
    """
    try:
        result = await api_client.preview_heightmap(
            x1,
            z1,
            x2,
            z2,
            heightmap_type=heightmap_type,
            world=world,
            iso_scale=iso_scale,
            view_direction=view_direction,
        )

        if result.get("status_code") != 200 or "png_bytes" not in result:
            return CallToolResult(
                content=[TextContent(
                    type="text",
                    text=f"❌ Heightmap preview failed: {result.get('error', 'Unknown error')}"
                )]
            )

        encoded = base64.b64encode(result["png_bytes"]).decode("ascii")
        return CallToolResult(
            content=[
                ImageContent(type="image", mimeType="image/png", data=encoded),
                TextContent(type="text", text=HEIGHTMAP_PREVIEW_ADVISORY),
            ]
        )
    except PendingOperation as pending:
        return pending_result(pending)
    except Exception as e:
        return format_error_response(e, "rendering heightmap preview")


async def handle_map_terrain(
    api_client: MinecraftAPIClient,
    x1: int,
    z1: int,
    x2: int,
    z2: int,
    heightmap_type: str = "WORLD_SURFACE",
    world: str = None,
    layers: Optional[List[str]] = None,
    px_per_block: Optional[int] = None,
    contour_interval: Optional[int] = None,
    **arguments
) -> CallToolResult:
    """
    Render top-down relief (labelled contours, spot heights) and surface-material maps.
    """
    try:
        result = await api_client.map_terrain(
            x1,
            z1,
            x2,
            z2,
            heightmap_type=heightmap_type,
            world=world,
            layers=layers,
            px_per_block=px_per_block,
            contour_interval=contour_interval,
        )

        if result.get("status_code") != 200 or not result.get("success"):
            return CallToolResult(
                content=[TextContent(
                    type="text",
                    text=f"❌ Terrain map failed: {result.get('error', 'Unknown error')}"
                )]
            )

        content: List[Any] = []
        for key in ("relief_png", "surface_png"):
            if result.get(key):
                content.append(ImageContent(type="image", mimeType="image/png", data=result[key]))
        content.append(TextContent(type="text", text=format_terrain_map_summary(result)))
        return CallToolResult(content=content)
    except PendingOperation as pending:
        return pending_result(pending)
    except Exception as e:
        return format_error_response(e, "rendering terrain map")


def format_terrain_map_summary(result: Dict[str, Any]) -> str:
    """The exact numbers behind the map images, and how to read them."""
    bounds = result.get("area_bounds") or {}
    lo, hi = bounds.get("min", {}), bounds.get("max", {})
    size = result.get("size") or {}
    y = result.get("y") or {}
    interval = result.get("contour_interval")

    lines = [
        f"**Terrain map** X {lo.get('x')}..{hi.get('x')}, Z {lo.get('z')}..{hi.get('z')} "
        f"({size.get('x')}x{size.get('z')} columns), {result.get('world')}, {result.get('heightmap_type')}",
        f"Top-down, north up: +X east (right), +Z south (down). 1 block = {result.get('px_per_block')} px; "
        f"grid every {result.get('grid_step')} blocks, labelled with world X (top) and Z (left).",
        f"Heights are standing Y (first air above the surface). "
        f"Y min/median/max: {y.get('min')}/{y.get('median')}/{y.get('max')}. "
        f"Contours every {interval}, bold every {interval * 5 if interval else '?'}; "
        f"dots are spot heights at grid-cell centres.",
    ]
    coverage = result.get("coverage") or {}
    if coverage:
        parts = [f"{name} {fraction * 100:.0f}%" if fraction >= 0.01 else f"{name} <1%"
                 for name, fraction in coverage.items()]
        lines.append("Surface coverage (top block): " + ", ".join(parts) + ".")
    if result.get("labels") is False:
        lines.append("⚠️ The server could not draw text, so the images have no labels; use the numbers above.")
    return "\n".join(lines)

