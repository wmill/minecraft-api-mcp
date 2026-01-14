"""
Response formatting utilities for the Minecraft MCP server.

Provides consistent formatting for success and error responses across all tool handlers.
"""

from typing import Any, Dict, List, Optional
from mcp.types import CallToolResult, TextContent


def format_success_response(text: str) -> CallToolResult:
    """
    Format a successful tool response.
    
    Args:
        text: The success message text
        
    Returns:
        CallToolResult with the formatted success message
    """
    return CallToolResult(
        content=[TextContent(type="text", text=text)]
    )


def format_error_response(error: Exception, context: str = "") -> CallToolResult:
    """
    Format an error response with consistent error messaging.
    
    Args:
        error: The exception that occurred
        context: Optional context about what operation failed
        
    Returns:
        CallToolResult with the formatted error message
    """
    error_text = f"Error connecting to Minecraft API: {str(error)}"
    if context:
        error_text = f"Error {context}: {str(error)}"
    
    return CallToolResult(
        content=[TextContent(type="text", text=error_text)]
    )


def format_api_error(result: Dict[str, Any], operation: str) -> CallToolResult:
    """
    Format an error response when the API returns a failure result.
    
    Args:
        result: The API response dictionary
        operation: Description of the operation that failed
        
    Returns:
        CallToolResult with the formatted error message
    """
    error_msg = result.get('error', 'Unknown error')
    return CallToolResult(
        content=[TextContent(type="text", text=f"❌ Failed to {operation}: {error_msg}")]
    )


def format_validation_error(message: str) -> CallToolResult:
    """
    Format a validation error response.
    
    Args:
        message: The validation error message
        
    Returns:
        CallToolResult with the formatted validation error
    """
    return CallToolResult(
        content=[TextContent(type="text", text=f"❌ {message}")]
    )


def format_coordinate(x: float, y: float, z: float, precision: int = 1) -> str:
    """
    Format coordinates as a string.
    
    Args:
        x: X coordinate
        y: Y coordinate
        z: Z coordinate
        precision: Number of decimal places (default: 1)
        
    Returns:
        Formatted coordinate string like "(x, y, z)"
    """
    return f"({x:.{precision}f}, {y:.{precision}f}, {z:.{precision}f})"


def format_coordinate_range(x1: int, y1: int, z1: int, x2: int, y2: int, z2: int) -> str:
    """
    Format a coordinate range as a string.
    
    Args:
        x1, y1, z1: First corner coordinates
        x2, y2, z2: Second corner coordinates
        
    Returns:
        Formatted range string like "from (x1, y1, z1) to (x2, y2, z2)"
    """
    return f"from ({x1}, {y1}, {z1}) to ({x2}, {y2}, {z2})"


def format_list_with_limit(items: List[str], limit: int = 20, item_formatter: Optional[callable] = None) -> str:
    """
    Format a list of items with an optional limit and overflow message.
    
    Args:
        items: List of items to format
        limit: Maximum number of items to show (default: 20)
        item_formatter: Optional function to format each item (default: str)
        
    Returns:
        Formatted string with items and overflow message if needed
    """
    if item_formatter is None:
        item_formatter = str
    
    result = ""
    for item in items[:limit]:
        result += f"- {item_formatter(item)}\n"
    
    if len(items) > limit:
        result += f"... and {len(items) - limit} more items\n"
    
    return result


def format_player_info(player: Dict[str, Any], facing: str) -> str:
    """
    Format player information as a string.
    
    Args:
        player: Player data dictionary with name, uuid, position, rotation
        facing: Cardinal direction the player is facing
        
    Returns:
        Formatted player info string
    """
    pos = player['position']
    rot = player['rotation']
    
    result = f"- **{player['name']}** (UUID: {player['uuid']})\n"
    result += f"  Position: {format_coordinate(pos['x'], pos['y'], pos['z'])}\n"
    result += f"  Rotation: Yaw {rot['yaw']:.1f}°, Pitch {rot['pitch']:.1f}°\n"
    result += f"  Facing: {facing}\n"
    
    return result


def format_entity_info(entity: Dict[str, Any]) -> str:
    """
    Format entity information as a string.
    
    Args:
        entity: Entity data dictionary with id and display_name
        
    Returns:
        Formatted entity info string
    """
    return f"{entity['id']} ({entity['display_name']})"


def format_block_counts(block_counts: Dict[str, int], total_blocks: int) -> str:
    """
    Format block count statistics as a string.
    
    Args:
        block_counts: Dictionary mapping block IDs to counts
        total_blocks: Total number of blocks
        
    Returns:
        Formatted block statistics string
    """
    result = ""
    
    # Sort by count descending
    sorted_blocks = sorted(block_counts.items(), key=lambda x: x[1], reverse=True)
    
    for block_id, count in sorted_blocks:
        percentage = (count / total_blocks * 100) if total_blocks > 0 else 0
        result += f"- {block_id}: {count} blocks ({percentage:.1f}%)\n"
    
    return result


def format_success_with_position(operation: str, entity_type: str, position: Dict[str, float], 
                                  extra_info: Optional[str] = None) -> CallToolResult:
    """
    Format a success response that includes position information.
    
    Args:
        operation: Description of the operation (e.g., "spawned", "placed")
        entity_type: Type of entity/structure that was placed
        position: Position dictionary with x, y, z keys
        extra_info: Optional additional information to append
        
    Returns:
        CallToolResult with formatted success message
    """
    text = f"✅ Successfully {operation} {entity_type} at {format_coordinate(position['x'], position['y'], position['z'])}"
    if extra_info:
        text += f"\n{extra_info}"
    
    return CallToolResult(
        content=[TextContent(type="text", text=text)]
    )


def format_success_with_count(operation: str, count: int, item_type: str, 
                               location: Optional[str] = None) -> CallToolResult:
    """
    Format a success response that includes a count.
    
    Args:
        operation: Description of the operation (e.g., "placed", "filled")
        count: Number of items affected
        item_type: Type of items (e.g., "blocks", "doors")
        location: Optional location description
        
    Returns:
        CallToolResult with formatted success message
    """
    text = f"✅ Successfully {operation} {count} {item_type}"
    if location:
        text += f" {location}"
    
    return CallToolResult(
        content=[TextContent(type="text", text=text)]
    )
