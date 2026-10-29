"""MCP handlers for server-side, temporary area annotations."""

import json
import httpx
from mcp.types import CallToolResult, TextContent
from ..utils.formatting import format_error_response
from ..client.operations import PendingOperation, pending_result


async def _result(operation):
    try:
        try:
            payload = await operation
        except httpx.HTTPStatusError as exc:
            payload = exc.response.json()
            if not isinstance(payload, dict) or "error" not in payload:
                raise exc
            payload = {**payload, "success": False}
        return CallToolResult(content=[TextContent(type="text", text=json.dumps(payload))],
                              structuredContent=payload, isError=not payload.get("success", False))
    except PendingOperation as pending:
        return pending_result(pending)
    except Exception as exc:
        return format_error_response(exc, "area marker")


async def handle_mark_area(api_client, bounds, label="", color="yellow", duration_seconds=60,
                           world="minecraft:overworld", player=None, **arguments):
    return await _result(api_client.mark_area(bounds, label, color, duration_seconds, world, player))


async def handle_remove_area_marker(api_client, marker_id, **arguments):
    return await _result(api_client.remove_area_marker(marker_id))
