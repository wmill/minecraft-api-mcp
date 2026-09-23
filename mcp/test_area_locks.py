import asyncio
import json
from unittest.mock import AsyncMock

import anyio
import httpx
from mcp.shared.memory import create_connected_server_and_client_session

from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
from minecraft_mcp.handlers import schematics, starlark
from minecraft_mcp.server import MinecraftMCPServer
from minecraft_mcp.tools.area_locks import LOCK_WRITE_TOOLS
from minecraft_mcp.tools.registry import get_handler
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS
from minecraft_mcp.utils.formatting import format_error_response
from minecraft_mcp.utils.starlark_models import Placement, StarlarkResult


CONFLICT = {
    "success": False, "code": "area_locked", "error": "Placement area overlaps an active reservation",
    "reservation": {"label": "other house", "world": "minecraft:overworld",
                    "bounds": {"min_x": 0, "min_y": 60, "min_z": 0, "max_x": 9, "max_y": 80, "max_z": 9},
                    "expires_at": "2026-01-01T00:15:00Z"},
}


def conflict_exception():
    response = httpx.Response(409, json=CONFLICT, request=httpx.Request("POST", "http://minecraft/api/world/structure/place"))
    return httpx.HTTPStatusError("conflict", request=response.request, response=response)


def mock_http(monkeypatch, handler):
    original = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: original(transport=httpx.MockTransport(handler), **kwargs))


def test_schema_registry_and_optional_tokens():
    tools = {tool.name: tool for tool in TOOL_SCHEMAS}
    for name in LOCK_WRITE_TOOLS:
        assert "lock_id" in tools[name].inputSchema["properties"]
        assert "lock_id" not in tools[name].inputSchema.get("required", [])
    for name in ("acquire_area_lock", "update_area_lock", "release_area_lock", "list_area_locks"):
        assert name in tools
        assert get_handler(name) is not None
    assert "lock_id" not in tools["get_players"].inputSchema["properties"]


async def test_concurrent_mcp_calls_keep_tokens_isolated_and_surface_conflicts(monkeypatch):
    requests = []

    async def respond(request):
        await asyncio.sleep(0)
        requests.append(request)
        return httpx.Response(409, json=CONFLICT)

    mock_http(monkeypatch, respond)
    server = MinecraftMCPServer("http://minecraft")
    args = dict(x1=0, y1=60, z1=0, x2=9, y2=80, z2=9, block_type="minecraft:stone")
    with anyio.fail_after(10):
        async with create_connected_server_and_client_session(server.server) as client:
            results = await asyncio.gather(
                client.call_tool("fill_box", {**args, "lock_id": "first"}),
                client.call_tool("fill_box", {**args, "lock_id": "second"}),
                client.call_tool("fill_box", args),
            )
    assert sorted(r.headers.get("X-Area-Lock-Id", "") for r in requests) == ["", "first", "second"]
    assert all("lock_id" not in json.loads(r.content) for r in requests)
    assert server.api_client.lock_id is None
    for result in results:
        assert result.isError
        assert result.structuredContent == CONFLICT
        assert "other house" in result.content[0].text


async def test_management_tools_retain_token_arguments_and_wire_shapes(monkeypatch):
    requests = []

    def respond(request):
        requests.append(request)
        return httpx.Response(200, json={"success": True})

    mock_http(monkeypatch, respond)
    server = MinecraftMCPServer("http://minecraft")
    bounds = CONFLICT["reservation"]["bounds"]
    with anyio.fail_after(10):
        async with create_connected_server_and_client_session(server.server) as client:
            for name, arguments in [
                ("acquire_area_lock", {"bounds": bounds, "label": "house"}),
                ("update_area_lock", {"lock_id": "token"}),
                ("update_area_lock", {"lock_id": "token", "bounds": bounds}),
                ("list_area_locks", {"world": "minecraft:overworld", "bounds": bounds}),
                ("release_area_lock", {"lock_id": "token"}),
            ]:
                result = await client.call_tool(name, arguments)
                assert not result.isError, result
    assert [r.method for r in requests] == ["POST", "PATCH", "PATCH", "GET", "DELETE"]
    assert json.loads(requests[0].content) == {"bounds": bounds, "world": "minecraft:overworld", "label": "house"}
    assert json.loads(requests[1].content) == {}
    assert json.loads(requests[2].content) == {"bounds": bounds}
    assert requests[1].url.path == "/api/area-locks/token"
    assert requests[3].url.params["min_y"] == "60"
    assert all("X-Area-Lock-Id" not in r.headers for r in requests)


async def test_multipart_and_queued_execution_forward_token(monkeypatch):
    requests = []

    def respond(request):
        requests.append(request)
        return httpx.Response(200, json={"success": True})

    mock_http(monkeypatch, respond)
    client = MinecraftAPIClient("http://minecraft").with_area_lock("owner")
    await client.place_nbt_structure_bytes(b"nbt", "house.nbt", 1, 60, 2)
    await client.execute_build("build")
    await client.replay_build("build")
    assert all(r.headers["X-Area-Lock-Id"] == "owner" for r in requests)
    assert requests[0].headers["content-type"].startswith("multipart/form-data")
    assert [r.url.path for r in requests[1:]] == ["/api/builds/build/execute", "/api/builds/build/replay"]


async def test_starlark_preserves_lock_details_and_artifact_for_retry(monkeypatch):
    api = AsyncMock()
    api.place_nbt_structure_bytes.side_effect = conflict_exception()
    service = AsyncMock()
    service.get_artifact_nbt.return_value = b"nbt"
    result = await starlark._place(api, service, {"artifact_id": "artifact"}, Placement(x=0, y=60, z=0))
    assert result.isError
    payload = StarlarkResult.model_validate(result.structuredContent)
    assert payload.error_kind == "area_locked"
    assert payload.lock_error == CONFLICT
    assert payload.artifact_id == "artifact"
    assert payload.placement.status == "failed"
    api.place_nbt_structure_bytes.assert_awaited_once()


async def test_schematic_preserves_lock_details(monkeypatch):
    service = AsyncMock()
    service.get_schematic.return_value = {}
    service.get_schematic_nbt.return_value = b"nbt"
    monkeypatch.setattr(schematics, "_schematic_client", lambda: service)
    api = AsyncMock()
    api.place_nbt_structure_bytes.side_effect = conflict_exception()
    result = await schematics.handle_place_schematic(api, "house", 0, 60, 0)
    assert result.isError
    assert result.structuredContent == CONFLICT


def test_invalid_expired_and_outside_tokens_remain_structured_errors():
    for code in ("invalid_area_lock", "outside_area_lock"):
        response = httpx.Response(409, json={"code": code, "error": "cannot write"},
                                  request=httpx.Request("POST", "http://minecraft"))
        result = format_error_response(httpx.HTTPStatusError("conflict", request=response.request, response=response))
        assert result.isError
        assert result.structuredContent["code"] == code
