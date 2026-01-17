"""
System-related tool handlers for the Minecraft MCP server.

Handles tools for player teleportation and server connection testing.
"""

from typing import Optional
from mcp.types import CallToolResult, TextContent
import httpx

from ..client.minecraft_api import MinecraftAPIClient
from ..utils.formatting import (
    format_success_response,
    format_error_response,
    format_coordinate
)
from ..utils.helpers import coordinate_info_blurb

async def handle_teleport_player(
    api_client: MinecraftAPIClient,
    player_name: str,
    x: float,
    y: float,
    z: float,
    dimension: Optional[str] = None,
    yaw: float = 0.0,
    pitch: float = 0.0,
    **arguments
) -> CallToolResult:
    """
    Teleport a player to specified coordinates with optional rotation.
    
    Args:
        api_client: The Minecraft API client
        player_name: Name of the player to teleport
        x: X coordinate
        y: Y coordinate
        z: Z coordinate
        dimension: World dimension (optional)
        yaw: Horizontal rotation in degrees
        pitch: Vertical rotation in degrees
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with teleport result
    """
    try:
        result = await api_client.teleport_player(
            player_name, x, y, z, dimension, yaw, pitch
        )
        
        if result.get("success"):
            coords = format_coordinate(x, y, z)
            response_text = f"✅ Successfully teleported {player_name} to {coords}"
            if dimension:
                response_text += f" in {dimension}"
            if yaw != 0.0 or pitch != 0.0:
                response_text += f"\nRotation: Yaw {yaw:.1f}°, Pitch {pitch:.1f}°"
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to teleport player: {result}")]
            )
    except Exception as e:
        return format_error_response(e, "teleporting player")


async def handle_test_server_connection(
    api_client: MinecraftAPIClient,
    **arguments
) -> CallToolResult:
    """
    Test if the Minecraft server API is running and responding.
    
    Args:
        api_client: The Minecraft API client
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with connection test result
    """
    try:
        result = await api_client.test_connection()
        
        # Check if we get the expected response
        if result:
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
                    text="⚠️ Minecraft server responded but with unexpected content"
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
                text="❌ Connection to Minecraft server timed out"
            )]
        )
    except Exception as e:
        return format_error_response(e, "testing server connection")

async def handle_coordinate_conventions(
    api_client: MinecraftAPIClient,
    **arguments
) -> CallToolResult:
    return CallToolResult(
        content=[TextContent(type="text", text=coordinate_info_blurb)]
    )