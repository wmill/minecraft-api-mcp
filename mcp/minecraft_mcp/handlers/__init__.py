"""
Tool handler implementations organized by domain.
"""

# Import all handlers for easy access
from .world import (
    handle_get_players,
    handle_get_entities,
    handle_spawn_entity
)

from .blocks import (
    handle_get_blocks,
    handle_set_blocks,
    handle_get_blocks_chunk,
    handle_fill_box,
    handle_get_heightmap
)

from .messages import (
    handle_broadcast_message,
    handle_send_message_to_player
)

from .prefabs import (
    handle_place_nbt_structure,
    handle_place_door_line,
    handle_place_stairs,
    handle_place_window_pane_wall,
    handle_place_torch,
    handle_place_sign
)

from .builds import (
    handle_create_build,
    handle_add_build_task,
    handle_add_build_task_block_set,
    handle_add_build_task_block_fill,
    handle_add_build_task_prefab_door,
    handle_add_build_task_prefab_stairs,
    handle_add_build_task_prefab_window,
    handle_add_build_task_prefab_torch,
    handle_add_build_task_prefab_sign,
    handle_execute_build,
    handle_query_builds_by_location,
    handle_get_build_status
)

from .system import (
    handle_teleport_player,
    handle_test_server_connection
)

__all__ = [
    # World handlers
    "handle_get_players",
    "handle_get_entities",
    "handle_spawn_entity",
    # Block handlers
    "handle_get_blocks",
    "handle_set_blocks",
    "handle_get_blocks_chunk",
    "handle_fill_box",
    "handle_get_heightmap",
    # Message handlers
    "handle_broadcast_message",
    "handle_send_message_to_player",
    # Prefab handlers
    "handle_place_nbt_structure",
    "handle_place_door_line",
    "handle_place_stairs",
    "handle_place_window_pane_wall",
    "handle_place_torch",
    "handle_place_sign",
    # Build handlers
    "handle_create_build",
    "handle_add_build_task",
    "handle_add_build_task_block_set",
    "handle_add_build_task_block_fill",
    "handle_add_build_task_prefab_door",
    "handle_add_build_task_prefab_stairs",
    "handle_add_build_task_prefab_window",
    "handle_add_build_task_prefab_torch",
    "handle_add_build_task_prefab_sign",
    "handle_execute_build",
    "handle_query_builds_by_location",
    "handle_get_build_status",
    # System handlers
    "handle_teleport_player",
    "handle_test_server_connection",
]
