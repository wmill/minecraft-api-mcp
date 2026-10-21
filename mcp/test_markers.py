import json
import httpx
import pytest
from mcp.shared.memory import create_connected_server_and_client_session
from minecraft_mcp.server import MinecraftMCPServer
from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS
from minecraft_mcp.tools.registry import get_handler


BOUNDS = dict(min_x=-10, min_y=60, min_z=-20, max_x=9, max_y=70, max_z=-1)


def mock_http(monkeypatch, handler):
    original = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: original(transport=httpx.MockTransport(handler), **kwargs))


async def test_markers_roundtrip_over_mcp(monkeypatch):
    requests = []

    def respond(request):
        requests.append(request)
        if request.method == "POST":
            return httpx.Response(200, json={"success": True, "marker_id": "marker-123", "expires_at": "2026-01-01T00:01:00Z"})
        return httpx.Response(200, json={"success": True, "marker_id": "marker-123", "removed": len(requests) == 2})

    mock_http(monkeypatch, respond)
    server = MinecraftMCPServer("http://minecraft")
    async with create_connected_server_and_client_session(server.server) as client:
        tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        assert "mark_area" in tools and "remove_area_marker" in tools
        result = await client.call_tool("mark_area", dict(bounds=BOUNDS, label="Station", player="Alex", color="cyan"))
        assert not result.isError
        assert result.structuredContent["marker_id"] == "marker-123"
        assert requests[0].url.path == "/api/world/markers"
        assert json.loads(requests[0].content) == dict(bounds=BOUNDS, label="Station", player="Alex", color="cyan",
                                                       duration_seconds=60, world="minecraft:overworld")
        for expected in (True, False):
            result = await client.call_tool("remove_area_marker", {"marker_id": "marker-123"})
            assert not result.isError
            assert result.structuredContent["removed"] is expected
        assert requests[-1].method == "DELETE"
        assert requests[-1].url.path == "/api/world/markers/marker-123"


async def test_api_validation_error_survives_mcp(monkeypatch):
    mock_http(monkeypatch, lambda request: httpx.Response(400, json={"success": False, "error": "Unknown world: missing"}))
    handler = get_handler("mark_area")
    result = await handler(MinecraftAPIClient("http://minecraft"), bounds=BOUNDS, world="missing")
    assert result.isError
    assert result.structuredContent["error"] == "Unknown world: missing"


@pytest.mark.parametrize("name", ["mark_area", "remove_area_marker"])
def test_marker_tools_have_handlers_and_do_not_require_reservations(name):
    tool = next(tool for tool in TOOL_SCHEMAS if tool.name == name)
    assert get_handler(name) is not None
    assert not tool.annotations.destructiveHint
    assert "lock_id" not in tool.inputSchema["properties"]
