import httpx
import pytest
from unittest.mock import AsyncMock

from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
from minecraft_mcp.client import operations
from minecraft_mcp.handlers.messages import handle_broadcast_message
from minecraft_mcp.handlers.schematics import handle_place_schematic
from minecraft_mcp.tools.registry import get_handler
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS


def http_mock(monkeypatch, handler):
    original = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: original(transport=httpx.MockTransport(handler), **kwargs))
    monkeypatch.setattr(operations, "POLL_SECONDS", 0.001)


@pytest.mark.parametrize("final_status", [200, 409, 500])
async def test_pending_poll_retains_original_status_and_body(monkeypatch, final_status):
    requests = []
    def handler(request):
        requests.append(request)
        if request.method == "POST":
            return httpx.Response(202, json={"operation_id": "op", "status_url": "/api/operations/op", "status": "queued"})
        assert request.extensions["timeout"]["read"] <= operations.POLL_DEADLINE_SECONDS
        if len(requests) == 2:
            return httpx.Response(200, json={"status": "running"})
        return httpx.Response(200, json={"status": "succeeded" if final_status == 200 else "failed",
                                        "result_status": final_status, "result": {"success": final_status == 200, "detail": "original"}})
    http_mock(monkeypatch, handler)
    client = MinecraftAPIClient("http://minecraft")
    if final_status == 200:
        assert await client.broadcast_message("test") == {"success": True, "detail": "original"}
    else:
        with pytest.raises(httpx.HTTPStatusError) as failure:
            await client.broadcast_message("test")
        assert failure.value.response.status_code == final_status
        assert failure.value.response.json()["detail"] == "original"
    assert requests[0].headers["Idempotency-Key"]
    assert requests[1].url.path == "/api/operations/op"


async def test_lost_response_reuses_key_and_missing_record_never_resubmits(monkeypatch):
    keys = []
    def handler(request):
        if request.method == "POST":
            keys.append(request.headers["Idempotency-Key"])
            if len(keys) == 1:
                raise httpx.ReadError("response lost", request=request)
            return httpx.Response(202, json={"operation_id": "op"})
        return httpx.Response(404, json={"error": "unknown"})
    http_mock(monkeypatch, handler)
    with pytest.raises(RuntimeError, match="outcome is unknown"):
        await MinecraftAPIClient("http://minecraft").broadcast_message("test")
    assert len(keys) == 2 and keys[0] == keys[1]


async def test_deadline_returns_distinct_pending_result_without_success(monkeypatch):
    http_mock(monkeypatch, lambda request: httpx.Response(202, json={"operation_id": "op"}))
    monkeypatch.setattr(operations, "POLL_DEADLINE_SECONDS", 0)
    result = await handle_broadcast_message(MinecraftAPIClient("http://minecraft"), "test")
    assert result.structuredContent["status"] == "pending"
    assert result.structuredContent["operation_id"] == "op"
    assert "get_operation" in result.content[0].text
    assert "success" not in result.content[0].text.lower()


async def test_immediate_result_and_get_operation_registration(monkeypatch):
    http_mock(monkeypatch, lambda request: httpx.Response(200, json={"success": True}))
    assert await MinecraftAPIClient("http://minecraft").broadcast_message("test") == {"success": True}
    assert "get_operation" in {tool.name for tool in TOOL_SCHEMAS}
    assert get_handler("get_operation") is not None


async def test_schematic_pending_never_claims_placement_succeeded(monkeypatch):
    from minecraft_mcp.handlers import schematics
    service = AsyncMock()
    service.get_schematic.return_value = {"placeable": True}
    service.get_nbt_bytes.return_value = b"nbt"
    monkeypatch.setattr(schematics, "_schematic_client", lambda: service)
    api = AsyncMock()
    api.place_nbt_structure_bytes.side_effect = operations.PendingOperation("op", "/api/operations/op")
    result = await handle_place_schematic(api, "id", 0, 64, 0)
    assert result.structuredContent["status"] == "pending"
    assert "success" not in result.content[0].text.lower()


async def test_starlark_pending_matches_declared_output_schema(monkeypatch):
    import jsonschema
    from minecraft_mcp.handlers import starlark
    from minecraft_mcp.utils.starlark_models import Placement
    service = AsyncMock()
    service.get_artifact_nbt.return_value = b"nbt"
    api = AsyncMock()
    api.place_nbt_structure_bytes.side_effect = operations.PendingOperation("op", "/api/operations/op")
    result = await starlark._place(api, service, {"artifact_id": "artifact", "y_offset": 0}, Placement(x=0, y=64, z=0))
    assert result.structuredContent["status"] == "pending"
    tool = next(tool for tool in TOOL_SCHEMAS if tool.name == "place_starlark_structure")
    jsonschema.validate(result.structuredContent, tool.outputSchema)


@pytest.mark.parametrize("kind", ["reservation", "marker"])
async def test_management_handlers_preserve_pending_result(kind):
    from minecraft_mcp.handlers import area_locks, markers
    async def pending():
        raise operations.PendingOperation("op", "/api/operations/op")
    handler = area_locks._result if kind == "reservation" else markers._result
    result = await handler(pending())
    assert result.structuredContent["status"] == "pending"
