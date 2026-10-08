import json
from unittest.mock import AsyncMock

import httpx
import pytest

from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
from minecraft_mcp.handlers import builds, prefabs, schematics, starlark
from minecraft_mcp.tools.area_locks import LOCK_WRITE_TOOLS
from minecraft_mcp.tools.registry import get_handler
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS
from minecraft_mcp.utils.formatting import format_dry_run, undo_hint


DRY_RUN = {
    "success": True, "dry_run": True, "world": "minecraft:overworld", "rotation": "NONE",
    "position": {"x": 0, "y": 64, "z": 0},
    "bounds": {"min_x": 0, "min_y": 64, "min_z": 0, "max_x": 4, "max_y": 68, "max_z": 4},
    "structure_size": {"x": 5, "y": 5, "z": 5},
    "overwrite": {"template_blocks": 125, "replaced": 30, "unchanged": 5, "air_carved": 12, "placed_into_air": 40,
                  "replaced_by_category": {"terrain": 20, "logs_leaves": 10, "vegetation": 0, "fluids": 0,
                                           "block_entities": 0, "other": 0},
                  "top_replaced": [{"block": "minecraft:dirt", "count": 18}, {"block": "minecraft:oak_log", "count": 10}]},
    "lock_check": {"ok": False, "code": "area_locked", "error": "Placement area overlaps an active reservation"},
    "reservations": {"status": "available", "total": 1, "truncated": False,
                     "entries": [{"label": "bakery", "bounds": {}, "expires_at": "x"}]},
    "builds": {"status": "available", "total": 0, "truncated": False, "entries": []},
    "undo_snapshot": True,
}


def mock_http(monkeypatch, handler):
    original = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: original(transport=httpx.MockTransport(handler), **kwargs))


def test_undo_tool_registered_with_lock_token():
    tools = {tool.name: tool for tool in TOOL_SCHEMAS}
    assert get_handler("undo_build") is builds.handle_undo_build
    assert "undo_build" in LOCK_WRITE_TOOLS
    assert tools["undo_build"].inputSchema["required"] == ["build_id"]
    assert "lock_id" in tools["undo_build"].inputSchema["properties"]
    for name in ("place_nbt_structure", "place_schematic", "place_starlark_structure"):
        assert "dry_run" in tools[name].inputSchema["properties"]
    assert "dry_run" in tools["build_starlark_structure"].inputSchema["properties"]["placement"]["properties"]


def test_dry_run_summary_mentions_overwrites_and_conflicts():
    text = format_dry_run(DRY_RUN)
    assert "not changed" in text
    assert "would replace 30" in text and "12 carved to air" in text
    assert "terrain 20, logs_leaves 10" in text and "vegetation" not in text
    assert "minecraft:dirt x18" in text
    assert "area_locked" in text
    assert "Overlapping reservations: 1 (bakery)" in text


def test_undo_hint():
    assert "undo_build" in undo_hint({"undo_available": True, "build_id": "b1"})
    assert "snapshot_too_large" in undo_hint({"undo_available": False, "undo_unavailable_reason": "snapshot_too_large"})
    assert undo_hint({}) == ""


async def test_client_sends_dry_run_field_and_undo_request(monkeypatch):
    seen = []

    async def respond(request):
        seen.append(request)
        if request.url.path.endswith("/undo"):
            return httpx.Response(200, json={"success": True, "build_id": "b1"})
        return httpx.Response(200, json=DRY_RUN)

    mock_http(monkeypatch, respond)
    client = MinecraftAPIClient("http://minecraft").with_area_lock("token")
    await client.place_nbt_structure_bytes(b"nbt", "a.nbt", 0, 64, 0, dry_run=True)
    await client.undo_build("b1", force=True)

    assert b'name="dry_run"\r\n\r\ntrue' in seen[0].content
    assert seen[1].url.path == "/api/builds/b1/undo"
    assert json.loads(seen[1].content) == {"force": True}
    assert seen[1].headers["X-Area-Lock-Id"] == "token"


async def test_placement_without_dry_run_omits_field(monkeypatch):
    seen = []

    async def respond(request):
        seen.append(request)
        return httpx.Response(200, json={"success": True})

    mock_http(monkeypatch, respond)
    await MinecraftAPIClient("http://minecraft").place_nbt_structure_bytes(b"nbt", "a.nbt", 0, 64, 0)
    assert b'name="dry_run"' not in seen[0].content


async def test_nbt_handler_reports_dry_run_and_undo():
    api = AsyncMock()
    api.place_nbt_structure.return_value = DRY_RUN
    result = await prefabs.handle_place_nbt_structure(api, "bmJ0", "a.nbt", 0, 64, 0, dry_run=True)
    assert "Dry run only" in result.content[0].text
    assert result.structuredContent["overwrite"]["replaced"] == 30

    api.place_nbt_structure.return_value = {"success": True, "build_id": "b1", "undo_available": True}
    result = await prefabs.handle_place_nbt_structure(api, "bmJ0", "a.nbt", 0, 64, 0)
    assert 'undo_build(build_id="b1")' in result.content[0].text


async def test_schematic_handler_explains_ground_offset(monkeypatch):
    service = AsyncMock()
    service.get_schematic.return_value = {"title": "Hut", "image_metadata": {"placement": {"ground_level": 1}}}
    service.get_schematic_nbt.return_value = b"nbt"
    monkeypatch.setattr(schematics, "_schematic_client", lambda: service)
    api = AsyncMock()
    api.place_nbt_structure_bytes.return_value = {"success": True, "build_id": "b1", "undo_available": True}

    result = await schematics.handle_place_schematic(api, "42", 0, 69, 0)

    text = result.content[0].text
    assert "Structure origin y=68 (requested walking plane y=69, ground_level offset 1)" in text
    assert "undo_build" in text
    assert api.place_nbt_structure_bytes.call_args.args[-1] is False

    api.place_nbt_structure_bytes.return_value = DRY_RUN
    result = await schematics.handle_place_schematic(api, "42", 0, 69, 0, dry_run=True)
    assert "Dry run only" in result.content[0].text
    assert api.place_nbt_structure_bytes.call_args.args[-1] is True


async def test_starlark_dry_run_outcome(monkeypatch):
    service = AsyncMock()
    service.get_artifact.return_value = {"artifact_id": "slk_1", "y_offset": -1}
    service.get_artifact_nbt.return_value = b"nbt"
    monkeypatch.setattr(starlark, "_starlark_client", lambda: service)
    api = AsyncMock()
    api.place_nbt_structure_bytes.return_value = DRY_RUN

    result = await starlark.handle_place_starlark_structure(api, "slk_1", 0, 64, 0, dry_run=True)

    placement = result.structuredContent["placement"]
    assert not result.isError
    assert placement["status"] == "dry_run"
    assert placement["overwrite"]["air_carved"] == 12
    assert placement["lock_check"]["ok"] is False
    assert api.place_nbt_structure_bytes.call_args.args[-1] is True


@pytest.mark.parametrize("status,payload,expected", [
    (409, {"success": False, "code": "undo_conflict", "error": "Later builds overlap",
           "builds": [{"build_id": "b2", "name": "cottage", "status": "COMPLETED"}]}, "b2: cottage"),
    (400, {"error": "No undo snapshot exists for this build"}, "No undo snapshot"),
])
async def test_undo_handler_surfaces_errors(status, payload, expected):
    api = AsyncMock()
    response = httpx.Response(status, json=payload, request=httpx.Request("POST", "http://minecraft/api/builds/b1/undo"))
    api.undo_build.side_effect = httpx.HTTPStatusError("err", request=response.request, response=response)

    result = await builds.handle_undo_build(api, "b1")

    assert result.isError
    assert expected in result.content[0].text
    assert result.structuredContent == payload


async def test_undo_handler_success():
    api = AsyncMock()
    api.undo_build.return_value = {"success": True, "build_id": "b1", "status": "REVERTED", "overwrote_later_builds": 2}
    result = await builds.handle_undo_build(api, "b1", force=True)
    assert not result.isError
    assert "reverted" in result.content[0].text and "2 later" in result.content[0].text
    api.undo_build.assert_awaited_once_with("b1", True)
