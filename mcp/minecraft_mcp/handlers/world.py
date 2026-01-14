"""
World-related tool handlers for the Minecraft MCP server.

Handles tools for players, entities, and world information.
"""

from typing import Any, Dict
from mcp.types import CallToolResult, TextContent

from ..client.minecraft_api import MinecraftAPIClient
from ..utils.formatting import (
    format_success_response,
    format_error_response,
    format_list_with_limit,
    format_player_info,
    format_entity_info,
    format_success_with_position
)
from ..utils.helpers import yaw_to_cardinal


async def handle_get_players(api_client: MinecraftAPIClient, **arguments) -> CallToolResult:
    """
    Get list of all players currently online with their positions and rotations.
    
    Args:
        api_client: The Minecraft API client
        **arguments: Tool arguments (none for this tool)
        
    Returns:
        CallToolResult with formatted player list
    """
    try:
        result = await api_client.get_players()
        
        response_text = "**Online Players:**\n"
        for player in result:
            facing = yaw_to_cardinal(float(player['rotation']['yaw']))
            response_text += format_player_info(player, facing)
        
        return format_success_response(response_text)
    except Exception as e:
        return format_error_response(e, "getting players")


async def handle_get_entities(api_client: MinecraftAPIClient, **arguments) -> CallToolResult:
    """
    Get list of all available entity types that can be spawned.
    
    Args:
        api_client: The Minecraft API client
        **arguments: Tool arguments (none for this tool)
        
    Returns:
        CallToolResult with formatted entity list
    """
    try:
        result = await api_client.get_entities()
        
        response_text = f"**Available Entity Types ({len(result)} total):**\n"
        response_text += format_list_with_limit(result, limit=20, item_formatter=format_entity_info)
        
        return format_success_response(response_text)
    except Exception as e:
        return format_error_response(e, "getting entities")


async def handle_spawn_entity(
    api_client: MinecraftAPIClient,
    entity_type: str,
    x: float,
    y: float,
    z: float,
    world: str = None,
    **arguments
) -> CallToolResult:
    """
    Spawn an entity at specified coordinates.
    
    Args:
        api_client: The Minecraft API client
        entity_type: Entity type identifier (e.g., 'minecraft:zombie')
        x: X coordinate
        y: Y coordinate
        z: Z coordinate
        world: World name (optional)
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with spawn result
    """
    try:
        result = await api_client.spawn_entity(entity_type, x, y, z, world)
        
        if result.get("success"):
            extra_info = f"Entity UUID: {result['uuid']}"
            return format_success_with_position(
                "spawned",
                result['type'],
                result['position'],
                extra_info
            )
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to spawn entity: {result}")]
            )
    except Exception as e:
        return format_error_response(e, "spawning entity")
