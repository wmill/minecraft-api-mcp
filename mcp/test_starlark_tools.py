"""Registration and configuration tests for the Starlark build service tools."""

from minecraft_mcp.config import STARLARK_SERVICE_URL
from minecraft_mcp.tools.registry import get_handler
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS

from copy import deepcopy
from unittest.mock import AsyncMock

import httpx
import jsonschema
import pytest

from minecraft_mcp.handlers import starlark
from minecraft_mcp.utils.starlark_diagnostics import compact_diagnostics
from minecraft_mcp.utils.starlark_models import StarlarkResult

BUILT = {
    "ok": True, "artifact_id": "slk_0123456789abcdef", "size": [7, 5, 9],
    "block_count": 100, "entity_count": 1, "ground_level": 1, "y_offset": -1,
    "build_ms": 12, "cached": False, "nbt_bytes": 123, "palette": [],
}


@pytest.fixture
def clients(monkeypatch):
    service = AsyncMock()
    service.build.return_value = deepcopy(BUILT)
    service.get_artifact.return_value = deepcopy(BUILT)
    service.get_artifact_nbt.return_value = b"nbt"
    api = AsyncMock()
    api.place_nbt_structure_bytes.return_value = {"success": True, "build_id": "recorded"}
    monkeypatch.setattr(starlark, "_starlark_client", lambda: service)
    return api, service


def checked(result):
    data = result.structuredContent
    jsonschema.validate(data, StarlarkResult.model_json_schema())
    assert result.isError == (not data["ok"])
    return data


def status_error(status, detail):
    request = httpx.Request("POST", "http://service/build")
    return httpx.HTTPStatusError("rejected", request=request,
                                 response=httpx.Response(status, json={"detail": detail}, request=request))


@pytest.mark.parametrize("cached", [False, True])
async def test_compile_only_never_places(clients, cached):
    api, service = clients
    service.build.return_value["cached"] = cached
    data = checked(await starlark.handle_build_starlark_structure(api, "source"))
    assert data["cached"] is cached
    assert data["compilation_ok"] is True
    assert data["artifact_id"] == BUILT["artifact_id"]
    api.place_nbt_structure_bytes.assert_not_called()
    service.get_artifact_nbt.assert_not_called()


@pytest.mark.parametrize("cached", [False, True])
@pytest.mark.parametrize("offset", [False, True])
async def test_combined_placement_reuses_metadata_and_applies_offset_once(clients, cached, offset):
    api, service = clients
    service.build.return_value["cached"] = cached
    data = checked(await starlark.handle_build_starlark_structure(
        api, "source", placement={"x": 10, "y": 64, "z": 20, "apply_y_offset": offset}))
    assert data["placement"]["position"] == {"x": 10, "y": 63 if offset else 64, "z": 20}
    assert data["placement"]["requested_position"]["y"] == 64
    assert data["placement"]["build_id"] == "recorded"
    assert data["compilation_ok"] is True
    service.get_artifact.assert_not_called()
    service.build.assert_awaited_once_with("source", entry="build", props=None, root_size=None)
    api.place_nbt_structure_bytes.assert_awaited_once_with(
        b"nbt", BUILT["artifact_id"] + ".nbt", 10, 63 if offset else 64, 20,
        "minecraft:overworld", "NONE", True, True)


async def test_standalone_placement_reuses_artifact(clients):
    api, service = clients
    data = checked(await starlark.handle_place_starlark_structure(
        api, BUILT["artifact_id"], 1, 65, 2, world="minecraft:the_nether",
        rotation="CLOCKWISE_90", include_entities=False))
    assert data["placement"]["position"]["y"] == 64
    assert data["placement"]["world"] == "minecraft:the_nether"
    service.build.assert_not_called()
    assert api.place_nbt_structure_bytes.call_args.args[-2] is False


async def test_build_failure_groups_diagnostics_and_never_places(clients):
    api, service = clients
    service.build.return_value = {"ok": False, "error_kind": "build_error", "diagnostics": [
        {"code": "block_conflict", "message": "collision", "component_path": "Wall",
         "coordinates": [x, 0, z], "details": {"existingComponent": "Floor"}}
        for x in range(20) for z in range(20)
    ]}
    result = await starlark.handle_build_starlark_structure(api, "source", placement={"x": 0, "y": 64, "z": 0})
    data = checked(result)
    assert data["diagnostic_count"] == 400
    assert len(data["diagnostics"]) == 1
    group = data["diagnostics"][0]
    assert group["count"] == 400
    assert len(group["coordinate_samples"]) == 3
    assert group["details"]["existingComponent"] == "Floor"
    assert "Fixtures" in group["hint"]
    assert len(result.content[0].text) < 1500
    api.place_nbt_structure_bytes.assert_not_called()


def test_distinct_groups_preserve_regions_and_report_omissions():
    result = compact_diagnostics({"diagnostics": [
        {"code": "root_overflow", "message": "outside", "component_path": str(i),
         "region": {"min": [0, 0, 0], "max": [1, 1, 1]}, "coordinates": [2, 0, 0]}
        for i in range(7)
    ]})
    assert result["diagnostic_count"] == 7
    assert result["omitted_group_count"] == 2
    assert [g["component_path"] for g in result["diagnostics"]] == [str(i) for i in range(5)]
    assert result["diagnostics"][0]["region"]["max"] == [1, 1, 1]
    assert "bounds" in result["diagnostics"][0]["hint"]


@pytest.mark.parametrize("status,kind", [(400, "invalid_request"), (422, "invalid_request"), (429, "busy"), (503, "unavailable")])
async def test_service_errors_are_classified(clients, status, kind):
    api, service = clients
    service.build.side_effect = status_error(status, [{"loc": ["body", "root_size", 1], "msg": "expected integer"}])
    result = await starlark.handle_build_starlark_structure(api, "source")
    assert checked(result)["error_kind"] == kind
    if status in (400, 422):
        assert "body.root_size.1" in result.content[0].text


@pytest.mark.parametrize("exception,status", [
    (httpx.ReadTimeout("lost response"), "unknown"),
    (status_error(500, "server failure"), "unknown"),
    (status_error(400, "unknown world"), "failed"),
])
async def test_placement_errors_keep_compilation_and_do_not_retry(clients, exception, status):
    api, service = clients
    api.place_nbt_structure_bytes.side_effect = exception
    data = checked(await starlark.handle_build_starlark_structure(api, "source", placement={"x": 0, "y": 64, "z": 0}))
    assert data["compilation_ok"] is True
    assert data["artifact_id"] == BUILT["artifact_id"]
    assert data["size"] == BUILT["size"]
    assert data["placement"]["status"] == status
    api.place_nbt_structure_bytes.assert_awaited_once()


async def test_backend_reports_placement_failure(clients):
    api, service = clients
    api.place_nbt_structure_bytes.return_value = {"success": False, "error": "failed"}
    data = checked(await starlark.handle_build_starlark_structure(api, "source", placement={"x": 0, "y": 64, "z": 0}))
    assert data["placement"]["status"] == "failed"
    assert "partial" in data["hint"]


async def test_evicted_artifact_is_an_error(clients):
    api, service = clients
    service.get_artifact.side_effect = status_error(404, "missing")
    data = checked(await starlark.handle_place_starlark_structure(api, BUILT["artifact_id"], 1, 64, 2))
    assert data["error_kind"] == "not_found"
    assert "Recompile" in data["hint"]
    api.place_nbt_structure_bytes.assert_not_called()


@pytest.mark.parametrize("placement", [{"x": 0}, {"x": True, "y": 64, "z": 0}, {"x": 0, "y": 64, "z": 0, "rotation": "90"}])
async def test_invalid_placement_rejected_before_compilation(clients, placement):
    api, service = clients
    data = checked(await starlark.handle_build_starlark_structure(api, "source", placement=placement))
    assert data["error_kind"] == "invalid_request"
    service.build.assert_not_called()


async def test_docs_default_to_quickstart_and_support_component(clients):
    api, service = clients
    service.get_catalog.return_value = "docs"
    await starlark.handle_get_starlark_docs(api)
    service.get_catalog.assert_awaited_with("quickstart", None)
    await starlark.handle_get_starlark_docs(api, component="Bench")
    service.get_catalog.assert_awaited_with("quickstart", "Bench")


async def test_client_requests_match_http_contract(monkeypatch):
    from minecraft_mcp.client.starlark_service import StarlarkServiceClient
    from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
    import json

    requests = []
    def respond(request):
        requests.append(request)
        if request.url.path == "/build":
            return httpx.Response(200, json=BUILT)
        if request.url.path.endswith("/place"):
            return httpx.Response(200, json={"success": True})
        return httpx.Response(200, text="docs")

    real_client = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: real_client(
        transport=httpx.MockTransport(respond), **kwargs))
    service = StarlarkServiceClient("http://starlark")
    await service.build("source", entry="custom", props={"width": 3}, root_size=[3, 4, 5])
    assert json.loads(requests[-1].content) == {
        "source": "source", "entry": "custom", "props": {"width": 3}, "root_size": [3, 4, 5]}
    await service.get_catalog()
    assert dict(requests[-1].url.params) == {"topic": "quickstart"}
    await service.get_catalog(component="Bench")
    assert dict(requests[-1].url.params) == {"component": "Bench"}
    await MinecraftAPIClient("http://minecraft").place_nbt_structure_bytes(
        b"nbt", "build.nbt", 1, 63, 2, rotation="CLOCKWISE_90", include_entities=False)
    request = requests[-1]
    assert request.url.path == "/api/world/structure/place"
    assert request.extensions["timeout"]["read"] == 40.0
    assert b'filename="build.nbt"' in request.content
    assert b"CLOCKWISE_90" in request.content
    assert b"false" in request.content


async def test_download_failure_never_uploads_and_preserves_compilation(clients):
    api, service = clients
    service.get_artifact_nbt.side_effect = status_error(404, "evicted")
    data = checked(await starlark.handle_build_starlark_structure(
        api, "source", placement={"x": 1, "y": 64, "z": 2}))
    assert data["compilation_ok"] is True
    assert data["artifact_id"] == BUILT["artifact_id"]
    assert data["placement"]["status"] == "failed"
    api.place_nbt_structure_bytes.assert_not_called()

STARLARK_TOOLS = [
    "build_starlark_structure",
    "place_starlark_structure",
    "get_starlark_docs",
    "list_starlark_examples",
    "get_starlark_example",
]


def test_starlark_tools_are_registered():
    names = {tool.name for tool in TOOL_SCHEMAS}
    for tool_name in STARLARK_TOOLS:
        assert tool_name in names
        assert get_handler(tool_name) is not None


def test_starlark_service_url_has_local_default():
    assert STARLARK_SERVICE_URL == "http://localhost:7090"
