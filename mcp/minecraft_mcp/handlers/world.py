"""
World-related tool handlers for the Minecraft MCP server.

Handles tools for players, entities, and world information.
"""

from ..client.operations import PendingOperation, pending_result

from typing import Any, Dict

import httpx
from mcp.types import CallToolResult, TextContent

from ..client.minecraft_api import MinecraftAPIClient
from ..utils.formatting import (
    format_success_response,
    format_error_response,
    format_list_with_limit,
    format_player_info,
    format_entity_info,
    format_success_with_position,
    format_coordinate
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
    except PendingOperation as pending:
        return pending_result(pending)
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
    except PendingOperation as pending:
        return pending_result(pending)
    except Exception as e:
        return format_error_response(e, "getting entities")


async def handle_spawn_entity(
    api_client: MinecraftAPIClient,
    entity_type: str,
    x: float,
    y: float,
    z: float,
    world: str = None,
    count: int = None,
    spread: float = None,
    options: Dict[str, Any] = None,
    **arguments
) -> CallToolResult:
    """
    Spawn one or more customised entities at specified coordinates.
    
    Args:
        api_client: The Minecraft API client
        entity_type: Entity type identifier (e.g., 'minecraft:zombie')
        x: X coordinate
        y: Y coordinate
        z: Z coordinate
        world: World name (optional)
        count: Number of entities to spawn (optional)
        spread: Scatter radius for extra entities (optional)
        options: Customisation options, including nested passengers (optional)
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with spawn result
    """
    try:
        result = await api_client.spawn_entity(entity_type, x, y, z, world, count, spread, options)
    except httpx.HTTPStatusError as e:
        try:
            message = e.response.json().get("error")
        except ValueError:
            message = None
        if message:
            return CallToolResult(
                isError=True,
                content=[TextContent(type="text", text=f"❌ Failed to spawn entity: {message}")]
            )
        return format_error_response(e, "spawning entity")
    except PendingOperation as pending:
        return pending_result(pending)
    except Exception as e:
        return format_error_response(e, "spawning entity")

    if not result.get("success"):
        return CallToolResult(
            content=[TextContent(type="text", text=f"❌ Failed to spawn entity: {result}")]
        )

    entities = result.get("entities") or []
    ignored = result.get("ignored") or []
    if len(entities) <= 1 and not any(e.get("passengers") for e in entities) and not ignored:
        return format_success_with_position(
            "spawned",
            result['type'],
            result['position'],
            f"Entity UUID: {result['uuid']}"
        )

    total = sum(_count_entities(e) for e in entities)
    lines = [f"✅ Successfully spawned {len(entities)} x {result['type']} ({total} entities including passengers)"]
    for entity in entities:
        lines.extend(_describe_spawned(entity, 0))
    if ignored:
        lines.append("⚠️ Ignored (not applicable to that entity type): " + ", ".join(ignored))
    return CallToolResult(content=[TextContent(type="text", text="\n".join(lines))])


def _count_entities(entity: Dict[str, Any]) -> int:
    return 1 + sum(_count_entities(p) for p in entity.get("passengers") or [])


def _describe_spawned(entity: Dict[str, Any], depth: int) -> list[str]:
    pos = entity["position"]
    prefix = "  " * depth + ("- " if depth == 0 else "↳ ")
    lines = [f"{prefix}{entity['type']} at {format_coordinate(pos['x'], pos['y'], pos['z'])} (UUID {entity['uuid']})"]
    for passenger in entity.get("passengers") or []:
        lines.extend(_describe_spawned(passenger, depth + 1))
    return lines
