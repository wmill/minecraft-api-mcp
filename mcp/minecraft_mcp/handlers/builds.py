"""
Build management tool handlers for the Minecraft MCP server.

Handles tools for creating builds, adding tasks to builds, executing builds, and querying build status.
"""

from typing import Any, Dict, List, Optional
from mcp.types import CallToolResult, TextContent

from ..client.minecraft_api import MinecraftAPIClient
from ..utils.formatting import (
    format_success_response,
    format_error_response
)


async def handle_add_build_task(
    api_client: MinecraftAPIClient,
    **arguments
) -> CallToolResult:
    """
    Deprecated handler for add_build_task tool.
    
    This tool is deprecated. Users should use the specific add_build_task_* tools instead.
    
    Args:
        api_client: The Minecraft API client
        **arguments: Any arguments (ignored)
        
    Returns:
        CallToolResult with deprecation message
    """
    return CallToolResult(
        content=[TextContent(
            type="text",
            text="❌ This tool is deprecated. Please use one of the following specific tools instead:\n"
                 "- add_build_task_block_set: For setting blocks\n"
                 "- add_build_task_block_fill: For filling areas\n"
                 "- add_build_task_prefab_door: For placing doors\n"
                 "- add_build_task_prefab_stairs: For placing stairs\n"
                 "- add_build_task_prefab_window: For placing windows\n"
                 "- add_build_task_prefab_torch: For placing torches\n"
                 "- add_build_task_prefab_sign: For placing signs"
        )]
    )


async def handle_create_build(
    api_client: MinecraftAPIClient,
    name: str,
    description: Optional[str] = None,
    world: Optional[str] = None,
    **arguments
) -> CallToolResult:
    """
    Create a new build with metadata for organizing building tasks.
    
    Args:
        api_client: The Minecraft API client
        name: Build name
        description: Build description (optional)
        world: World name (optional)
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with build creation result
    """
    try:
        result = await api_client.create_build(name, description, world)
        
        if result.get("success"):
            build = result["build"]
            response_text = f"✅ Successfully created build '{build['name']}'\n"
            response_text += f"Build ID: {build['id']}\n"
            response_text += f"Description: {build.get('description', 'No description')}\n"
            response_text += f"World: {build['world']}\n"
            response_text += f"Status: {build['status']}\n"
            response_text += f"Created: {build['created_at']}"
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to create build: {result.get('error', 'Unknown error')}")]
            )
    except Exception as e:
        return format_error_response(e, "creating build")


async def handle_add_build_task_block_set(
    api_client: MinecraftAPIClient,
    build_id: str,
    start_x: int,
    start_y: int,
    start_z: int,
    blocks: List[List[List[Optional[Dict[str, Any]]]]],
    world: Optional[str] = None,
    **arguments
) -> CallToolResult:
    """
    Add a BLOCK_SET task to a build queue.
    
    Args:
        api_client: The Minecraft API client
        build_id: Build UUID
        start_x: Starting X coordinate
        start_y: Starting Y coordinate
        start_z: Starting Z coordinate
        blocks: 3D array of block objects
        world: World name (optional)
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with task addition result
    """
    try:
        task_data = {
            "start_x": start_x,
            "start_y": start_y,
            "start_z": start_z,
            "blocks": blocks,
        }
        if world:
            task_data["world"] = world
        
        result = await api_client.add_build_task(build_id, "BLOCK_SET", task_data)
        
        if result.get("success"):
            task = result["task"]
            response_text = f"✅ Successfully added BLOCK_SET task to build\n"
            response_text += f"Task ID: {task['id']}\n"
            response_text += f"Build ID: {build_id}\n"
            response_text += f"Task Order: {task.get('task_order', 'N/A')}\n"
            response_text += f"Status: {task['status']}"
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to add task: {result.get('error', 'Unknown error')}")]
            )
    except Exception as e:
        return format_error_response(e, "adding build task")


async def handle_add_build_task_block_fill(
    api_client: MinecraftAPIClient,
    build_id: str,
    x1: int,
    y1: int,
    z1: int,
    x2: int,
    y2: int,
    z2: int,
    block_type: str,
    world: Optional[str] = None,
    **arguments
) -> CallToolResult:
    """
    Add a BLOCK_FILL task to a build queue.
    
    Args:
        api_client: The Minecraft API client
        build_id: Build UUID
        x1: First corner X coordinate
        y1: First corner Y coordinate
        z1: First corner Z coordinate
        x2: Second corner X coordinate
        y2: Second corner Y coordinate
        z2: Second corner Z coordinate
        block_type: Block type identifier
        world: World name (optional)
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with task addition result
    """
    try:
        task_data = {
            "x1": x1,
            "y1": y1,
            "z1": z1,
            "x2": x2,
            "y2": y2,
            "z2": z2,
            "block_type": block_type,
        }
        if world:
            task_data["world"] = world
        
        result = await api_client.add_build_task(build_id, "BLOCK_FILL", task_data)
        
        if result.get("success"):
            task = result["task"]
            response_text = f"✅ Successfully added BLOCK_FILL task to build\n"
            response_text += f"Task ID: {task['id']}\n"
            response_text += f"Build ID: {build_id}\n"
            response_text += f"Task Order: {task.get('task_order', 'N/A')}\n"
            response_text += f"Status: {task['status']}"
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to add task: {result.get('error', 'Unknown error')}")]
            )
    except Exception as e:
        return format_error_response(e, "adding build task")


async def handle_add_build_task_prefab_door(
    api_client: MinecraftAPIClient,
    build_id: str,
    start_x: int,
    start_y: int,
    start_z: int,
    facing: str,
    block_type: str = "minecraft:oak_door",
    width: int = 1,
    hinge: str = "left",
    double_doors: bool = False,
    open: bool = False,
    world: Optional[str] = None,
    **arguments
) -> CallToolResult:
    """
    Add a PREFAB_DOOR task to a build queue.
    
    Args:
        api_client: The Minecraft API client
        build_id: Build UUID
        start_x: Starting X coordinate
        start_y: Starting Y coordinate
        start_z: Starting Z coordinate
        facing: Direction the doors should face
        block_type: Door block type
        width: Number of doors
        hinge: Door hinge position
        double_doors: Whether to alternate hinges
        open: Whether doors start open
        world: World name (optional)
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with task addition result
    """
    try:
        task_data = {
            "start_x": start_x,
            "start_y": start_y,
            "start_z": start_z,
            "facing": facing,
            "block_type": block_type,
            "width": width,
            "hinge": hinge,
            "double_doors": double_doors,
            "open": open,
        }
        if world:
            task_data["world"] = world
        
        result = await api_client.add_build_task(build_id, "PREFAB_DOOR", task_data)
        
        if result.get("success"):
            task = result["task"]
            response_text = f"✅ Successfully added PREFAB_DOOR task to build\n"
            response_text += f"Task ID: {task['id']}\n"
            response_text += f"Build ID: {build_id}\n"
            response_text += f"Task Order: {task.get('task_order', 'N/A')}\n"
            response_text += f"Status: {task['status']}"
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to add task: {result.get('error', 'Unknown error')}")]
            )
    except Exception as e:
        return format_error_response(e, "adding build task")


async def handle_add_build_task_prefab_stairs(
    api_client: MinecraftAPIClient,
    build_id: str,
    start_x: int,
    start_y: int,
    start_z: int,
    end_x: int,
    end_y: int,
    end_z: int,
    staircase_direction: str,
    block_type: str = "minecraft:stone",
    stair_type: str = "minecraft:stone_stairs",
    fill_support: bool = False,
    world: Optional[str] = None,
    **arguments
) -> CallToolResult:
    """
    Add a PREFAB_STAIRS task to a build queue.
    
    Args:
        api_client: The Minecraft API client
        build_id: Build UUID
        start_x: Starting X coordinate
        start_y: Starting Y coordinate
        start_z: Starting Z coordinate
        end_x: Ending X coordinate
        end_y: Ending Y coordinate
        end_z: Ending Z coordinate
        staircase_direction: Orientation of the staircase
        block_type: Base block type
        stair_type: Stair block type
        fill_support: Whether to fill underneath
        world: World name (optional)
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with task addition result
    """
    try:
        task_data = {
            "start_x": start_x,
            "start_y": start_y,
            "start_z": start_z,
            "end_x": end_x,
            "end_y": end_y,
            "end_z": end_z,
            "block_type": block_type,
            "stair_type": stair_type,
            "staircase_direction": staircase_direction,
            "fill_support": fill_support,
        }
        if world:
            task_data["world"] = world
        
        result = await api_client.add_build_task(build_id, "PREFAB_STAIRS", task_data)
        
        if result.get("success"):
            task = result["task"]
            response_text = f"✅ Successfully added PREFAB_STAIRS task to build\n"
            response_text += f"Task ID: {task['id']}\n"
            response_text += f"Build ID: {build_id}\n"
            response_text += f"Task Order: {task.get('task_order', 'N/A')}\n"
            response_text += f"Status: {task['status']}"
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to add task: {result.get('error', 'Unknown error')}")]
            )
    except Exception as e:
        return format_error_response(e, "adding build task")


async def handle_add_build_task_prefab_window(
    api_client: MinecraftAPIClient,
    build_id: str,
    start_x: int,
    start_y: int,
    start_z: int,
    end_x: int,
    end_z: int,
    height: int,
    block_type: str = "minecraft:glass_pane",
    waterlogged: bool = False,
    world: Optional[str] = None,
    **arguments
) -> CallToolResult:
    """
    Add a PREFAB_WINDOW task to a build queue.
    
    Args:
        api_client: The Minecraft API client
        build_id: Build UUID
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
        CallToolResult with task addition result
    """
    try:
        task_data = {
            "start_x": start_x,
            "start_y": start_y,
            "start_z": start_z,
            "end_x": end_x,
            "end_z": end_z,
            "height": height,
            "block_type": block_type,
            "waterlogged": waterlogged,
        }
        if world:
            task_data["world"] = world
        
        result = await api_client.add_build_task(build_id, "PREFAB_WINDOW", task_data)
        
        if result.get("success"):
            task = result["task"]
            response_text = f"✅ Successfully added PREFAB_WINDOW task to build\n"
            response_text += f"Task ID: {task['id']}\n"
            response_text += f"Build ID: {build_id}\n"
            response_text += f"Task Order: {task.get('task_order', 'N/A')}\n"
            response_text += f"Status: {task['status']}"
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to add task: {result.get('error', 'Unknown error')}")]
            )
    except Exception as e:
        return format_error_response(e, "adding build task")


async def handle_add_build_task_prefab_torch(
    api_client: MinecraftAPIClient,
    build_id: str,
    x: int,
    y: int,
    z: int,
    block_type: str = "minecraft:wall_torch",
    facing: Optional[str] = None,
    world: Optional[str] = None,
    **arguments
) -> CallToolResult:
    """
    Add a PREFAB_TORCH task to a build queue.
    
    Args:
        api_client: The Minecraft API client
        build_id: Build UUID
        x: X coordinate
        y: Y coordinate
        z: Z coordinate
        block_type: Torch type
        facing: For wall torches, direction the torch faces
        world: World name (optional)
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with task addition result
    """
    try:
        task_data = {
            "x": x,
            "y": y,
            "z": z,
            "block_type": block_type,
        }
        if facing:
            task_data["facing"] = facing
        if world:
            task_data["world"] = world
        
        result = await api_client.add_build_task(build_id, "PREFAB_TORCH", task_data)
        
        if result.get("success"):
            task = result["task"]
            response_text = f"✅ Successfully added PREFAB_TORCH task to build\n"
            response_text += f"Task ID: {task['id']}\n"
            response_text += f"Build ID: {build_id}\n"
            response_text += f"Task Order: {task.get('task_order', 'N/A')}\n"
            response_text += f"Status: {task['status']}"
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to add task: {result.get('error', 'Unknown error')}")]
            )
    except Exception as e:
        return format_error_response(e, "adding build task")


async def handle_add_build_task_prefab_sign(
    api_client: MinecraftAPIClient,
    build_id: str,
    x: int,
    y: int,
    z: int,
    block_type: str = "minecraft:oak_wall_sign",
    front_lines: Optional[List[str]] = None,
    back_lines: Optional[List[str]] = None,
    facing: Optional[str] = None,
    rotation: int = 0,
    glowing: bool = False,
    world: Optional[str] = None,
    **arguments
) -> CallToolResult:
    """
    Add a PREFAB_SIGN task to a build queue.
    
    Args:
        api_client: The Minecraft API client
        build_id: Build UUID
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
        CallToolResult with task addition result
    """
    try:
        task_data = {
            "x": x,
            "y": y,
            "z": z,
            "block_type": block_type,
            "rotation": rotation,
            "glowing": glowing,
        }
        if front_lines is not None:
            task_data["front_lines"] = front_lines
        if back_lines is not None:
            task_data["back_lines"] = back_lines
        if facing:
            task_data["facing"] = facing
        if world:
            task_data["world"] = world
        
        result = await api_client.add_build_task(build_id, "PREFAB_SIGN", task_data)
        
        if result.get("success"):
            task = result["task"]
            response_text = f"✅ Successfully added PREFAB_SIGN task to build\n"
            response_text += f"Task ID: {task['id']}\n"
            response_text += f"Build ID: {build_id}\n"
            response_text += f"Task Order: {task.get('task_order', 'N/A')}\n"
            response_text += f"Status: {task['status']}"
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to add task: {result.get('error', 'Unknown error')}")]
            )
    except Exception as e:
        return format_error_response(e, "adding build task")


async def handle_execute_build(
    api_client: MinecraftAPIClient,
    build_id: str,
    **arguments
) -> CallToolResult:
    """
    Execute all queued tasks in a build.
    
    Args:
        api_client: The Minecraft API client
        build_id: Build UUID
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with execution result
    """
    try:
        result = await api_client.execute_build(build_id)
        
        if result.get("success"):
            response_text = f"Executed build {build_id}\n"
            response_text += f"Success: {result['success']}\n"
            response_text += f"Message: {result['message']}\n"
            response_text += f"Build status: {result['status']}\n"
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to execute build: {result.get('error', 'Unknown error')}")]
            )
    except Exception as e:
        return format_error_response(e, "executing build")


async def handle_query_builds_by_location(
    api_client: MinecraftAPIClient,
    min_x: int,
    min_y: int,
    min_z: int,
    max_x: int,
    max_y: int,
    max_z: int,
    world: Optional[str] = None,
    include_in_progress: bool = False,
    **arguments
) -> CallToolResult:
    """
    Find builds that intersect with a specified area.
    
    Args:
        api_client: The Minecraft API client
        min_x: Minimum X coordinate
        min_y: Minimum Y coordinate
        min_z: Minimum Z coordinate
        max_x: Maximum X coordinate
        max_y: Maximum Y coordinate
        max_z: Maximum Z coordinate
        world: World name (optional)
        include_in_progress: Whether to include builds in progress
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with list of builds in the area
    """
    try:
        result = await api_client.query_builds_by_location(
            min_x, min_y, min_z, max_x, max_y, max_z,
            world, include_in_progress
        )
        
        if result.get("success"):
            builds = result["builds"]
            if not builds:
                response_text = f"No builds found in area ({min_x}, {min_y}, {min_z}) to ({max_x}, {max_y}, {max_z})"
                return format_success_response(response_text)
            
            response_text = f"**Found {len(builds)} builds in area ({min_x}, {min_y}, {min_z}) to ({max_x}, {max_y}, {max_z}):**\n\n"
            
            for build_result in builds:
                build = build_result['build']
                intersecting_tasks = build_result.get('intersectingTasks', [])
                response_text += f"**{build['name']}** (ID: {build['id']})\n"
                response_text += f"- Status: {build['status']}\n"
                response_text += f"- Description: {build.get('description', 'No description')}\n"
                response_text += f"- Created: {build.get('created_at', 'N/A')}\n"
                if build.get('completed_at'):
                    response_text += f"- Completed: {build['completed_at']}\n"
                response_text += f"- Intersecting Tasks: {len(intersecting_tasks)}\n"
                response_text += f"- World: {build['world']}\n\n"
            
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to query builds: {result.get('error', 'Unknown error')}")]
            )
    except Exception as e:
        return format_error_response(e, "querying builds by location")


async def handle_get_build_status(
    api_client: MinecraftAPIClient,
    build_id: str,
    **arguments
) -> CallToolResult:
    """
    Get build details, status, and task information.
    
    Args:
        api_client: The Minecraft API client
        build_id: Build UUID
        **arguments: Additional arguments (ignored)
        
    Returns:
        CallToolResult with build status and task details
    """
    try:
        result = await api_client.get_build_status(build_id)
        
        if result.get("success"):
            build = result["build"]
            tasks = result.get("tasks", [])
            
            response_text = f"**Build Status: {build['name']}**\n\n"
            response_text += f"**Build Details:**\n"
            response_text += f"- ID: {build['id']}\n"
            response_text += f"- Name: {build['name']}\n"
            response_text += f"- Description: {build.get('description', 'No description')}\n"
            response_text += f"- Status: {build['status']}\n"
            response_text += f"- World: {build['world']}\n"
            response_text += f"- Created: {build.get('created_at', 'N/A')}\n"
            if build.get('completed_at'):
                response_text += f"- Completed: {build['completed_at']}\n"
            
            response_text += f"\n**Task Queue ({len(tasks)} tasks):**\n"
            if not tasks:
                response_text += "No tasks in queue\n"
            else:
                for task in tasks:
                    status_icon = "✅" if task['status'] == 'completed' else "❌" if task['status'] == 'failed' else "⏳"
                    response_text += f"{status_icon} Task {task.get('task_order', 'N/A')}: {task.get('task_type', 'unknown')} - {task['status']}\n"
                    if task.get('error_message'):
                        response_text += f"   Error: {task['error_message']}\n"
            
            return format_success_response(response_text)
        else:
            return CallToolResult(
                content=[TextContent(type="text", text=f"❌ Failed to get build status: {result.get('error', 'Unknown error')}")]
            )
    except Exception as e:
        return format_error_response(e, "getting build status")
