import json
from mcp.types import CallToolResult, TextContent


async def handle_get_operation(api_client, operation_id: str, **arguments) -> CallToolResult:
    result = await api_client.get_operation(operation_id)
    return CallToolResult(content=[TextContent(type="text", text=json.dumps(result))],
                          structuredContent=result, isError=result.get("result_status", 200) >= 400)
