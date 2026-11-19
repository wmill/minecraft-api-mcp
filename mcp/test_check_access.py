import json
from unittest.mock import AsyncMock, patch

import httpx
import pytest

from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
from minecraft_mcp.handlers.access import handle_check_access
from minecraft_mcp.tools.area_locks import LOCK_WRITE_TOOLS
from minecraft_mcp.tools.registry import TOOL_HANDLERS
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS
from minecraft_mcp.utils.access_models import AccessResult

P = lambda x, y, z: {"x": x, "y": y, "z": z}  # noqa: E731


def sample():
    return {
        "success": True, "world": "minecraft:overworld", "profile": "player", "doors": "wooden",
        "start": {"requested": P(10, 64, 0), "valid": True, "position": P(10, 64, 0)},
        "targets": [
            {"name": "inside", "requested": P(0, 63, 0), "position": P(0, 64, 0), "adjusted": True,
             "via": P(3, 64, 0), "status": "reachable", "route_length": 12, "via_crossed": True},
            {"name": "floor 2", "requested": P(1, 68, 1), "position": P(1, 68, 1), "status": "unreachable",
             "reason": "no_route_within_search_box", "nearest_reached": {"position": P(1, 64, 1), "distance": 4.0}},
        ],
        "map": {"region": {"min_x": -3, "min_y": 63, "min_z": -3, "max_x": 3, "max_y": 73, "max_z": 3},
                "standable": 99, "reachable": 25, "complete": True,
                "floors": [{"y": 64, "standable": 25, "reachable": 25, "outdoor": 0, "fraction": 1.0},
                           {"y": 68, "standable": 25, "reachable": 0, "outdoor": 0, "fraction": 0.0},
                           {"y": 72, "standable": 49, "reachable": 0, "outdoor": 49, "fraction": 0.0}],
                "floors_truncated": False, "unreached_region_count": 2,
                "unreached_regions": [
                    {"cells": 25, "outdoor": False, "bounds": {"min_x": -2, "min_y": 68, "min_z": -2,
                                                               "max_x": 2, "max_y": 68, "max_z": 2}, "sample": P(-2, 68, -2)},
                    {"cells": 49, "outdoor": True, "bounds": {"min_x": -3, "min_y": 72, "min_z": -3,
                                                              "max_x": 3, "max_y": 72, "max_z": 3}, "sample": P(-3, 72, -3)}]},
        "search": {"box": {"min_x": -19, "min_y": 47, "min_z": -19, "max_x": 26, "max_y": 89, "max_z": 19},
                   "max_nodes": 50000, "expanded_nodes": 1800, "reached_positions": 1800,
                   "node_limit_hit": False, "touched_box_edge": True, "touched_unloaded_chunks": False},
        "limitations": "Estimate of player movement.",
    }


def test_registration_and_read_only_contract():
    tool = next(t for t in TOOL_SCHEMAS if t.name == "check_access")
    assert TOOL_HANDLERS[tool.name] is handle_check_access
    assert tool.annotations.readOnlyHint
    assert tool.name not in LOCK_WRITE_TOOLS
    assert tool.inputSchema["required"] == ["start"]
    assert tool.outputSchema == AccessResult.model_json_schema()


@pytest.mark.asyncio
async def test_summary_lists_targets_floors_and_indoor_gaps():
    client = AsyncMock(spec=MinecraftAPIClient)
    client.check_access.return_value = sample()
    result = await handle_check_access(client, start=P(10, 64, 0), targets=[{"x": 0, "y": 63, "z": 0}],
                                       map={"build_id": "b"}, margin=None)
    assert not result.isError
    text = result.content[0].text
    assert "inside at (0,64,0) (adjusted from (0,63,0)): reachable, 12 moves via (3,64,0)" in text
    assert "floor 2 at (1,68,1): UNREACHABLE (no_route_within_search_box); nearest reached (1,64,1), 4.0 blocks away" in text
    assert "y68: 0/25 reachable (0%)" in text
    assert "25 cells x-2..2 y68..68" in text
    assert "49 unreached outdoor cells" in text
    assert result.structuredContent["targets"][1]["status"] == "unreachable"
    client.check_access.assert_awaited_once_with(
        {"start": P(10, 64, 0), "targets": [{"x": 0, "y": 63, "z": 0}], "map": {"build_id": "b"}})


@pytest.mark.asyncio
async def test_http_error_payload_is_structured():
    error = {"success": False, "code": "chunks_not_loaded", "error": "1 chunk(s) ... not loaded",
             "missing_chunks": [{"chunk_x": 5, "chunk_z": 0}]}

    def respond(request):
        assert request.url.path == "/api/world/access/check"
        assert json.loads(request.content) == {"start": P(80, 64, 0), "targets": [P(0, 64, 0)], "profile": "villager"}
        return httpx.Response(400, json=error)

    client = MinecraftAPIClient("http://test")
    with patch.object(client, "_http_client", side_effect=lambda **kw: httpx.AsyncClient(transport=httpx.MockTransport(respond), **kw)):
        result = await handle_check_access(client, start=P(80, 64, 0), targets=[P(0, 64, 0)], profile="villager")
    assert result.isError
    assert result.structuredContent == error
    assert "chunks_not_loaded" in result.content[0].text


@pytest.mark.asyncio
async def test_invalid_start_and_garbage_response():
    client = AsyncMock(spec=MinecraftAPIClient)
    payload = sample()
    payload["start"] = {"requested": P(0, 70, 0), "valid": False, "reason": "not_standable", "suggestion": P(0, 64, 1)}
    payload["targets"] = [{"name": "t", "requested": P(1, 64, 0), "status": "unknown", "reason": "invalid_start"}]
    payload["map"] = {"status": "unknown", "reason": "invalid_start"}
    client.check_access.return_value = payload
    result = await handle_check_access(client, start=P(0, 70, 0))
    assert "not a standable feet position; try (0,64,1)" in result.content[0].text
    assert "Map: unknown (invalid_start)" in result.content[0].text

    client.check_access.return_value = {"success": True, "targets": "nope"}
    bad = await handle_check_access(client, start=P(0, 64, 0))
    assert bad.isError and bad.structuredContent["code"] == "invalid_response"
