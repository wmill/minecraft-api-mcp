"""
Tool registry for the Minecraft MCP Server.

This module maps tool names to their handler functions, providing a central
registry for tool discovery and routing.
"""

from typing import Callable, Optional
from mcp.types import CallToolResult

from ..handlers import world, blocks, messages, prefabs, builds, system


# Tool handler type
ToolHandler = Callable[..., CallToolResult]


# Map tool names to handler functions
TOOL_HANDLERS: dict[str, ToolHandler] = {
    # World tools
    "get_players": world.handle_get_players,
    "get_entities": world.handle_get_entities,
    "spawn_entity": world.handle_spawn_entity,
    
    # Block tools
    "get_blocks": blocks.handle_get_blocks,
    "set_blocks": blocks.handle_set_blocks,
    "get_blocks_chunk": blocks.handle_get_blocks_chunk,
    "fill_box": blocks.handle_fill_box,
    "get_heightmap": blocks.handle_get_heightmap,
    
    # Message tools
    "broadcast_message": messages.handle_broadcast_message,
    "send_message_to_player": messages.handle_send_message_to_player,
    
    # Prefab tools
    "place_nbt_structure": prefabs.handle_place_nbt_structure,
    "place_door_line": prefabs.handle_place_door_line,
    "place_stairs": prefabs.handle_place_stairs,
    "place_window_pane_wall": prefabs.handle_place_window_pane_wall,
    "place_torch": prefabs.handle_place_torch,
    "place_sign": prefabs.handle_place_sign,
    "place_ladder": prefabs.handle_place_ladder,
    
    # System tools
    "teleport_player": system.handle_teleport_player,
    "test_server_connection": system.handle_test_server_connection,
    "get_coordinate_conventions": system.handle_coordinate_conventions,
    
    # Build management tools
    "create_build": builds.handle_create_build,
    "add_build_task": builds.handle_add_build_task,  # Deprecated
    "add_build_task_single_block_set": builds.handle_add_build_task_single_block_set,
    "add_build_task_block_set": builds.handle_add_build_task_block_set,
    "add_build_task_block_fill": builds.handle_add_build_task_block_fill,
    "add_build_task_prefab_door": builds.handle_add_build_task_prefab_door,
    "add_build_task_prefab_stairs": builds.handle_add_build_task_prefab_stairs,
    "add_build_task_prefab_window": builds.handle_add_build_task_prefab_window,
    "add_build_task_prefab_torch": builds.handle_add_build_task_prefab_torch,
    "add_build_task_prefab_sign": builds.handle_add_build_task_prefab_sign,
    "add_build_task_prefab_ladder": builds.handle_add_build_task_prefab_ladder,
    "execute_build": builds.handle_execute_build,
    "query_builds_by_location": builds.handle_query_builds_by_location,
    "get_build_status": builds.handle_get_build_status,
}


def get_handler(tool_name: str) -> Optional[ToolHandler]:
    """
    Get the handler function for a tool name.
    
    Args:
        tool_name: Name of the tool to get handler for
        
    Returns:
        Handler function if found, None otherwise
    """
    return TOOL_HANDLERS.get(tool_name)
