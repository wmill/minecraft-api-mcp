"""Small explicit reservation workflow, independent of MCP transport sessions."""

import json

from mcp.types import CallToolResult, TextContent

from ..utils.formatting import format_error_response


async def _result(operation):
    try:
        payload = await operation
        return CallToolResult(content=[TextContent(type="text", text=json.dumps(payload))],
                              structuredContent=payload)
    except Exception as exc:
        return format_error_response(exc, "managing area reservation")


async def handle_acquire_area_lock(api_client, bounds, world="minecraft:overworld", label=""):
    return await _result(api_client.acquire_area_lock(bounds, world, label))


async def handle_update_area_lock(api_client, lock_id, bounds=None):
    return await _result(api_client.update_area_lock(lock_id, bounds))


async def handle_release_area_lock(api_client, lock_id):
    return await _result(api_client.release_area_lock(lock_id))


async def handle_list_area_locks(api_client, world=None, bounds=None):
    return await _result(api_client.list_area_locks(world, bounds))
