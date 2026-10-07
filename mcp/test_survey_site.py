import json
from unittest.mock import AsyncMock, patch

import httpx
import pytest

from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
from minecraft_mcp.handlers.survey import handle_survey_site
from minecraft_mcp.tools.registry import TOOL_HANDLERS
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS
from minecraft_mcp.utils.survey_models import SurveyResult


def sample():
    return {
        "success": True, "world": "minecraft:overworld", "surveyed_at": "2026-10-07T12:00:00Z",
        "bounds": {"min_x": 0, "min_z": 0, "max_x": 39, "max_z": 39},
        "size": {"x": 40, "z": 40, "columns": 1600},
        "ground": {"resolved_columns": 1600, "unresolved_columns": 0, "unresolved_reasons": {},
                   "min_y": 64, "max_y": 64, "median_y": 64, "range": 0},
        "slope": {"adjacent_pairs": 3120, "mean_step": 0.0, "max_step": 0},
        "water": {"columns": 0, "fraction": 0.0}, "lava": {"columns": 0, "fraction": 0.0},
        "vegetation": {"columns": 0, "fraction": 0.0},
        "grading": {"suggested_walking_y": 64, "cut_blocks": 0, "fill_blocks": 0, "unavailable_reason": None},
        "reservations": {"status": "available", "total": 0, "truncated": False, "entries": []},
        "builds": {"status": "unavailable", "total": None, "truncated": False, "entries": []},
        "limitations": "Observations, not a reservation or proof of unused land.",
    }


def test_registration_and_read_only_contract():
    tool = next(t for t in TOOL_SCHEMAS if t.name == "survey_site")
    assert TOOL_HANDLERS[tool.name] is handle_survey_site
    assert tool.annotations.readOnlyHint
    assert "lock_id" not in tool.inputSchema["properties"]
    assert tool.outputSchema == SurveyResult.model_json_schema()


@pytest.mark.asyncio
async def test_success_is_compact_and_preserves_unknown_history():
    client = AsyncMock(spec=MinecraftAPIClient)
    client.survey_site.return_value = sample()
    result = await handle_survey_site(client, 0, 0, 39, 39)
    assert not result.isError
    assert result.structuredContent == sample()
    assert "builds: unavailable" in result.content[0].text
    assert "Suggested walking-plane Y 64" in result.content[0].text
    assert len(result.model_dump_json().encode()) < 6000
    client.survey_site.assert_awaited_once_with(0, 0, 39, 39, None)


@pytest.mark.asyncio
async def test_unresolved_ground_never_suggests_a_y():
    client = AsyncMock(spec=MinecraftAPIClient)
    payload = sample()
    payload["grading"] = {"suggested_walking_y": None, "cut_blocks": None, "fill_blocks": None,
                          "unavailable_reason": "unresolved_ground"}
    client.survey_site.return_value = payload
    result = await handle_survey_site(client, 0, 0, 39, 39)
    assert not result.isError
    assert "No suggested Y: unresolved_ground" in result.content[0].text
    assert result.structuredContent["grading"]["suggested_walking_y"] is None


@pytest.mark.asyncio
async def test_http_client_and_structured_unloaded_error():
    error = {"success": False, "code": "unloaded_chunks", "error": "Chunks not loaded",
             "missing_chunks": [{"x": -1, "z": 0}]}

    def respond(request):
        assert request.url.path == "/api/world/blocks/survey"
        assert json.loads(request.content) == {"x1": -1, "z1": 0, "x2": 39, "z2": 39, "world": "minecraft:overworld"}
        assert "x-area-lock-id" not in request.headers
        return httpx.Response(400, json=error)

    client = MinecraftAPIClient("http://test")
    with patch.object(client, "_http_client", side_effect=lambda **kw: httpx.AsyncClient(transport=httpx.MockTransport(respond), **kw)):
        result = await handle_survey_site(client, -1, 0, 39, 39, "minecraft:overworld")
    assert result.isError
    assert result.structuredContent == error
    assert "1 chunks" in result.content[0].text


@pytest.mark.asyncio
async def test_unavailable_and_malformed_responses():
    client = AsyncMock(spec=MinecraftAPIClient)
    client.survey_site.side_effect = httpx.ConnectError("offline")
    result = await handle_survey_site(client, 0, 0, 0, 0)
    assert result.isError and result.structuredContent["code"] == "unavailable"
    client.survey_site.side_effect = None
    client.survey_site.return_value = {"success": True}
    result = await handle_survey_site(client, 0, 0, 0, 0)
    assert result.isError and result.structuredContent["code"] == "invalid_response"
