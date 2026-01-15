"""
Message-related tool handlers for the Minecraft MCP server.

Handles tools for broadcasting messages and sending messages to specific players.
"""

from typing import Optional
from mcp.types import CallToolResult, TextContent

from ..client.minecraft_api import MinecraftAPIClient
from ..utils.formatting import (
    format_success_response,
    format_error_response,
    format_validation_error
)


async def handle_broadcast_message(
    api_client: MinecraftAPIClient,
    message: str,
    action_bar: bool = False,
    **arguments
) -> CallToolResult:
    """
    Send a message to all players on the server.
    
    Args:
        api_client: The Minecraft API client
        message: Message text to send
        action_bar: If true, shows in action bar; if false, shows in chat
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with broadcast result
    """
    try:
        result = await api_client.broadcast_message(message, action_bar)
        
        if result.get("success"):
            location = "action bar" if action_bar else "chat"
            response_text = f"✅ Successfully broadcast message to all players in {location}"
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to broadcast message: {result}")]
            )
    except Exception as e:
        return format_error_response(e, "broadcasting message")


async def handle_send_message_to_player(
    api_client: MinecraftAPIClient,
    message: str,
    player_uuid: Optional[str] = None,
    player_name: Optional[str] = None,
    action_bar: bool = False,
    **arguments
) -> CallToolResult:
    """
    Send a message to a specific player.
    
    Args:
        api_client: The Minecraft API client
        message: Message text to send
        player_uuid: Player's UUID (takes priority over name)
        player_name: Player's name (used if UUID not provided)
        action_bar: If true, shows in action bar; if false, shows in chat
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with message result
    """
    try:
        if not player_uuid and not player_name:
            return format_validation_error("Must provide either player_uuid or player_name")
        
        result = await api_client.send_message_to_player(
            message,
            player_uuid,
            player_name,
            action_bar
        )
        
        if result.get("success"):
            player_identifier = player_uuid if player_uuid else player_name
            location = "action bar" if action_bar else "chat"
            response_text = f"✅ Successfully sent message to player {player_identifier} in {location}"
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to send message: {result}")]
            )
    except ValueError as e:
        return format_validation_error(str(e))
    except Exception as e:
        return format_error_response(e, "sending message to player")
