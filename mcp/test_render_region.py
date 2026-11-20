import base64
from unittest.mock import AsyncMock

import httpx
import pytest

from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
from minecraft_mcp.client.starlark_service import StarlarkServiceClient
from minecraft_mcp.handlers import render
from minecraft_mcp.handlers.render import handle_render_region
from minecraft_mcp.tools.area_locks import LOCK_WRITE_TOOLS
from minecraft_mcp.tools.registry import TOOL_HANDLERS
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS

NBT = b"\x1f\x8bregion"
PNG = b"\x89PNGimage"
INFO = {"x-region-origin": "100,60,-20", "x-region-size": "20,12,16", "x-region-world": "minecraft:overworld"}


def status_error(status: int, body=None, text: str = "") -> httpx.HTTPStatusError:
    request = httpx.Request("GET", "http://test")
    response = httpx.Response(status, json=body, request=request) if body is not None else \
        httpx.Response(status, text=text, request=request)
    return httpx.HTTPStatusError("error", request=request, response=response)


@pytest.fixture
def clients(monkeypatch):
    api = AsyncMock(spec=MinecraftAPIClient)
    api.capture_region.return_value = (NBT, dict(INFO))
    service = AsyncMock(spec=StarlarkServiceClient)
    service.upload_render.return_value = {"artifact_id": "rgn_0123456789abcdef", "size": [20, 12, 16], "cached": False}
    service.get_artifact_preview.return_value = (PNG, {"x-artifact-size": "20x12x16"})
    monkeypatch.setattr(render, "_starlark_client", lambda: service)
    return api, service


def text(result) -> str:
    return "\n".join(item.text for item in result.content if item.type == "text")


def test_registration_is_read_only():
    tool = next(t for t in TOOL_SCHEMAS if t.name == "render_region")
    assert TOOL_HANDLERS[tool.name] is handle_render_region
    assert tool.annotations.readOnlyHint
    assert tool.name not in LOCK_WRITE_TOOLS
    assert "lock_id" not in tool.inputSchema["properties"]


@pytest.mark.asyncio
async def test_build_render_captures_uploads_and_returns_image(clients):
    api, service = clients
    result = await handle_render_region(api, build_id="b-1", margin=4)
    assert not result.isError
    api.capture_region.assert_awaited_once_with(
        {"world": None, "build_id": "b-1", "margin": 4, "below": None, "above": None})
    service.upload_render.assert_awaited_once_with(NBT, "100,60,-20", "minecraft:overworld")
    service.get_artifact_preview.assert_awaited_once_with("rgn_0123456789abcdef", "sheet", None, None)
    assert result.content[0].type == "image" and base64.b64decode(result.content[0].data) == PNG
    body = text(result)
    assert "build b-1 in context" in body and "x 100..119, y 60..71, z -20..-5" in body
    assert "north up" in body


@pytest.mark.asyncio
async def test_world_cuts_become_local_and_are_range_checked(clients):
    api, service = clients
    result = await handle_render_region(api, x1=100, y1=60, z1=-20, x2=119, y2=71, z2=-5, view="iso", cut_y=65)
    assert not result.isError
    service.get_artifact_preview.assert_awaited_once_with("rgn_0123456789abcdef", "iso", None, {"cut_y": 5})
    assert "world y=65 (local y=5)" in text(result)

    outside = await handle_render_region(api, x1=100, y1=60, z1=-20, x2=119, y2=71, z2=-5, cut_x=99)
    assert outside.isError and "x 100..119" in text(outside)


@pytest.mark.asyncio
async def test_floors_are_reported_in_world_y(clients):
    api, service = clients
    service.get_artifact_preview.return_value = (PNG, {"x-preview-floors": "4:6,9:11"})
    result = await handle_render_region(api, build_id="b", view="floors")
    assert "floor y=64 (cut y=66), floor y=69 (cut y=71)" in text(result)


@pytest.mark.asyncio
async def test_removed_builds_are_flagged(clients):
    api, _ = clients
    api.capture_region.return_value = (NBT, {**INFO, "x-build-removed": "true"})
    result = await handle_render_region(api, build_id="b")
    assert "undone or superseded" in text(result)


@pytest.mark.asyncio
@pytest.mark.parametrize("kwargs", [
    {},
    {"build_id": "b", "x1": 0},
    {"x1": 0, "y1": 0, "z1": 0, "x2": 1, "y2": 1},
    {"x1": 0, "y1": 0, "z1": 0, "x2": 1, "y2": 1, "z2": 1, "margin": 2},
    {"build_id": "b", "cut_x": 1, "cut_y": 2},
    {"build_id": "b", "view": "section"},
])
async def test_invalid_arguments_never_call_the_server(clients, kwargs):
    api, _ = clients
    result = await handle_render_region(api, **kwargs)
    assert result.isError and "invalid_request" in text(result)
    api.capture_region.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("body, expected", [
    ({"code": "chunks_not_loaded", "error": "2 chunk(s) are not loaded",
      "missing_chunks": [{"x": 1, "z": 2}, {"x": 3, "z": 4}]}, ["chunks_not_loaded", "chunks 1,2, 3,4", "Teleport"]),
    ({"code": "region_too_large", "error": "too big"}, ["region_too_large", "build_id"]),
    ({"code": "build_not_found", "error": "No build b"}, ["build_not_found", "query_builds_by_location"]),
])
async def test_capture_errors_have_next_steps(clients, body, expected):
    api, service = clients
    api.capture_region.side_effect = status_error(409, body)
    body_text = text(await handle_render_region(api, build_id="b"))
    for fragment in expected:
        assert fragment in body_text
    service.upload_render.assert_not_awaited()


@pytest.mark.asyncio
async def test_service_failures(clients):
    api, service = clients
    service.upload_render.side_effect = httpx.ConnectError("refused")
    result = await handle_render_region(api, build_id="b")
    assert result.isError and "render_service_unavailable" in text(result)

    service.upload_render.side_effect = status_error(413, {"detail": "the NBT is larger than 16 bytes"})
    assert "region_too_large" in text(await handle_render_region(api, build_id="b"))

    service.upload_render.side_effect = None
    service.get_artifact_preview.side_effect = status_error(503, {"detail": "preview unavailable: timeout"})
    assert "render_unavailable" in text(await handle_render_region(api, build_id="b"))

    api.capture_region.side_effect = httpx.ConnectError("refused")
    assert "unavailable" in text(await handle_render_region(api, build_id="b"))


@pytest.mark.asyncio
async def test_clients_match_http_contract(monkeypatch):
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if request.url.path == "/api/world/region/nbt":
            return httpx.Response(200, content=NBT, headers={"X-Region-Origin": "1,2,3", "X-Region-Size": "4,5,6"})
        return httpx.Response(200, json={"artifact_id": "rgn_0123456789abcdef", "size": [4, 5, 6], "cached": True})

    real_client = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: real_client(
        transport=httpx.MockTransport(handler), **{k: v for k, v in kwargs.items() if k != "transport"}))
    data, info = await MinecraftAPIClient("http://mc").capture_region({"build_id": "b", "margin": None, "world": None})
    assert data == NBT and info == {"x-region-origin": "1,2,3", "x-region-size": "4,5,6"}
    assert seen[0].method == "GET" and dict(seen[0].url.params) == {"build_id": "b"}
    assert "idempotency-key" not in seen[0].headers

    uploaded = await StarlarkServiceClient("http://slk").upload_render(NBT, "1,2,3", "minecraft:overworld")
    assert uploaded["artifact_id"] == "rgn_0123456789abcdef"
    assert seen[1].method == "POST" and seen[1].url.path == "/renders" and seen[1].content == NBT
    assert seen[1].headers["x-region-origin"] == "1,2,3"
    assert seen[1].headers["content-type"] == "application/octet-stream"
