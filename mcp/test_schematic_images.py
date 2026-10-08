import base64
from unittest.mock import AsyncMock

import httpx

from minecraft_mcp.client.schematic_service import SchematicServiceClient
from minecraft_mcp.handlers import schematics
from minecraft_mcp.tools.registry import get_handler
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS

PNG = b"\x89PNG\r\n\x1a\nfake"


def status_error(status, body):
    response = httpx.Response(status, json=body, request=httpx.Request("GET", "http://schematics/x"))
    return httpx.HTTPStatusError("err", request=response.request, response=response)


def service(monkeypatch):
    client = AsyncMock()
    monkeypatch.setattr(schematics, "_schematic_client", lambda: client)
    return client


def test_image_tool_registered_read_only():
    tools = {tool.name: tool for tool in TOOL_SCHEMAS}
    tool = tools["get_schematic_image"]
    assert get_handler("get_schematic_image") is schematics.handle_get_schematic_image
    assert tool.annotations.readOnlyHint is True
    assert "lock_id" not in tool.inputSchema["properties"]
    assert "include_thumbnails" in tools["search_schematics"].inputSchema["properties"]


async def test_client_requests_view_with_size(monkeypatch):
    seen = []

    def respond(request):
        seen.append(request)
        return httpx.Response(200, content=PNG, headers={"content-type": "image/png"})

    original = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kw: original(transport=httpx.MockTransport(respond), **kw))
    assert await SchematicServiceClient("http://schematics").get_schematic_image("4", "south", 512) == PNG
    assert str(seen[0].url) == "http://schematics/schematics/4/images/south?max_px=512"


async def test_get_schematic_image_returns_png_and_orientation(monkeypatch):
    client = service(monkeypatch)
    client.get_schematic_image.return_value = PNG
    result = await schematics.handle_get_schematic_image(None, "4")
    image, text = result.content
    assert image.type == "image" and base64.b64decode(image.data) == PNG
    assert "south view faces +Z" in text.text
    client.get_schematic_image.assert_awaited_once_with("4", "sheet", None)


async def test_get_schematic_image_reports_missing_and_unavailable(monkeypatch):
    client = service(monkeypatch)
    client.get_schematic_image.side_effect = status_error(404, {"detail": "schematic not found"})
    result = await schematics.handle_get_schematic_image(None, "6591", "iso")
    assert result.isError and "schematic not found" in result.content[0].text

    client.get_schematic_image.side_effect = httpx.ConnectError("refused")
    result = await schematics.handle_get_schematic_image(None, "4")
    assert "unavailable" in result.content[0].text


async def test_search_thumbnails_are_optional_and_best_effort(monkeypatch):
    client = service(monkeypatch)
    rows = [{"schematic_id": str(i), "title": f"House {i}"} for i in range(7)]
    client.search_schematics.return_value = {"results": rows, "source": "local"}

    plain = await schematics.handle_search_schematics(None, "house")
    assert len(plain.content) == 1
    client.get_schematic_image.assert_not_called()

    client.get_schematic_image.side_effect = [PNG, status_error(404, {}), PNG, PNG, PNG]
    result = await schematics.handle_search_schematics(None, "house", include_thumbnails=True)
    images = [c for c in result.content if c.type == "image"]
    assert len(images) == 4
    assert client.get_schematic_image.await_count == 5
    assert client.get_schematic_image.await_args_list[0].args == ("0", "iso", schematics.THUMBNAIL_PX)
    assert "first 5 results" in result.content[0].text


async def test_get_schematic_points_to_preview_unless_no_views(monkeypatch):
    client = service(monkeypatch)
    client.get_schematic.return_value = {"schematic_id": "4", "title": "Manor", "views": ["iso", "top"]}
    assert "get_schematic_image" in (await schematics.handle_get_schematic(None, "4")).content[0].text
    client.get_schematic.return_value = {"schematic_id": "4", "title": "Manor", "views": []}
    assert "get_schematic_image" not in (await schematics.handle_get_schematic(None, "4")).content[0].text
