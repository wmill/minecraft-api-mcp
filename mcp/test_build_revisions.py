"""get_build_source, verify_build and revise_build, plus provenance sent with placements."""

from copy import deepcopy
from unittest.mock import AsyncMock

import httpx
import pytest

from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
from minecraft_mcp.handlers import revisions, schematics, starlark
from minecraft_mcp.tools.area_locks import LOCK_WRITE_TOOLS
from minecraft_mcp.tools.registry import get_handler
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS
from minecraft_mcp.utils.formatting import format_dry_run

SCRIPT = 'load("../library/mage_tower/v3.star", "MageTower")\ndef build(height=8):\n    return MageTower(height)\n'
BUILT = {"ok": True, "artifact_id": "slk_fedcba9876543210", "size": [9, 14, 9], "block_count": 400,
         "entity_count": 0, "ground_level": 1, "y_offset": -1, "build_ms": 20, "cached": False,
         "nbt_bytes": 900, "palette": []}


def source_info(**overrides):
    info = {
        "success": True,
        "build": {"id": "b1", "name": "NBT: slk_0123456789abcdef.nbt", "status": "COMPLETED",
                  "world": "minecraft:overworld"},
        "placement": {"type": "NBT_STRUCTURE", "origin": {"x": 100, "y": 69, "z": -40},
                      "size": {"x": 9, "y": 12, "z": 9}, "rotation": "CLOCKWISE_90",
                      "structure_bounds": {"min_x": 92, "min_y": 69, "min_z": -40, "max_x": 100, "max_y": 80, "max_z": -32}},
        "source": {"kind": "starlark", "artifact_id": "slk_0123456789abcdef", "entry": "build",
                   "props": {"height": 8, "roof": "slate"}, "y_offset": -1,
                   "target": {"x": 100, "y": 70, "z": -40}, "source": SCRIPT,
                   "details": {"apply_y_offset": True, "library_loads": ["mage_tower@3"]}},
        "lineage": {"children": []},
        "stored": {"undo_snapshot": True, "redo_snapshot": False, "placed_template": True},
        "undo_available": True, "redo_available": False, "revisable": True,
    }
    for key, value in overrides.items():
        info[key] = value
    return info


@pytest.fixture
def clients(monkeypatch):
    service = AsyncMock()
    service.build.return_value = deepcopy(BUILT)
    service.get_artifact_nbt.return_value = b"nbt"
    api = AsyncMock()
    api.get_build_source.return_value = source_info()
    api.place_nbt_structure_bytes.return_value = {
        "success": True, "build_id": "b2", "undo_available": True,
        "revision": {"replaces_build_id": "b1", "parent_status": "SUPERSEDED", "overwrote_later_builds": 0}}
    monkeypatch.setattr(starlark, "_starlark_client", lambda: service)
    return api, service


def test_tools_registered():
    tools = {tool.name: tool for tool in TOOL_SCHEMAS}
    for name in ("get_build_source", "verify_build", "revise_build"):
        assert get_handler(name) is getattr(revisions, f"handle_{name}")
        assert tools[name].inputSchema["required"] == ["build_id"]
    assert tools["get_build_source"].annotations.readOnlyHint
    assert tools["verify_build"].annotations.readOnlyHint
    assert "revise_build" in LOCK_WRITE_TOOLS
    assert "lock_id" in tools["revise_build"].inputSchema["properties"]
    assert "verify_build" not in LOCK_WRITE_TOOLS


async def test_client_sends_provenance_and_revision_fields(monkeypatch):
    seen = []

    def respond(request):
        seen.append(request)
        return httpx.Response(200, json={"success": True})

    original = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: original(transport=httpx.MockTransport(respond), **kwargs))
    client = MinecraftAPIClient("http://minecraft")
    await client.place_nbt_structure_bytes(b"nbt", "a.nbt", 0, 64, 0, provenance={"kind": "schematic", "schematic_id": "7"},
                                           replaces_build_id="b1", force=True)
    await client.place_nbt_structure_bytes(b"nbt", "a.nbt", 0, 64, 0)
    await client.get_build_source("b1")
    await client.verify_build("b1")

    body = seen[0].content
    assert b'name="provenance"\r\n\r\n{"kind":"schematic","schematic_id":"7"}' in body
    assert b'name="replaces_build_id"\r\n\r\nb1' in body
    assert b'name="force"\r\n\r\ntrue' in body
    assert b"provenance" not in seen[1].content and b"replaces_build_id" not in seen[1].content
    assert [r.url.path for r in seen[2:]] == ["/api/builds/b1/source", "/api/builds/b1/verify"]
    assert seen[2].method == seen[3].method == "GET"


def test_library_loads_are_named_versions():
    assert starlark.library_loads(SCRIPT + 'load("../lib/walls.star", "Wall")') == ["mage_tower@3"]
    assert starlark.library_loads(None) == []


async def test_place_fetches_the_script_when_only_an_artifact_id_is_given(clients):
    api, service = clients
    service.get_artifact.return_value = {**deepcopy(BUILT), "entry": "build", "props": {"height": 9}, "root_size": None}
    service.get_artifact_source.return_value = SCRIPT
    api.place_nbt_structure_bytes.return_value = {"success": True, "build_id": "b9"}
    await starlark.handle_place_starlark_structure(api, BUILT["artifact_id"], 1, 70, 2)
    provenance = api.place_nbt_structure_bytes.await_args.kwargs["provenance"]
    assert provenance["source"] == SCRIPT
    assert provenance["props"] == {"height": 9}
    assert provenance["details"]["library_loads"] == ["mage_tower@3"]
    assert provenance["target"] == {"x": 1, "y": 70, "z": 2} and provenance["y_offset"] == -1


async def test_place_records_an_evicted_script_by_artifact_id(clients):
    api, service = clients
    service.get_artifact.return_value = deepcopy(BUILT)
    service.get_artifact_source.side_effect = httpx.HTTPError("gone")
    api.place_nbt_structure_bytes.return_value = {"success": True, "build_id": "b9"}
    result = await starlark.handle_place_starlark_structure(api, BUILT["artifact_id"], 1, 70, 2)
    assert not result.isError
    assert "source" not in api.place_nbt_structure_bytes.await_args.kwargs["provenance"]


async def test_dry_run_sends_no_provenance(clients):
    api, service = clients
    service.get_artifact.return_value = deepcopy(BUILT)
    api.place_nbt_structure_bytes.return_value = {"success": True, "dry_run": True}
    await starlark.handle_place_starlark_structure(api, BUILT["artifact_id"], 1, 70, 2, dry_run=True)
    assert "provenance" not in api.place_nbt_structure_bytes.await_args.kwargs
    service.get_artifact_source.assert_not_called()


async def test_get_build_source_shows_script_lineage_and_actions(clients):
    api, _ = clients
    api.get_build_source.return_value = source_info(
        lineage={"parent_build_id": "b0", "parent_status": "SUPERSEDED",
                 "children": [{"build_id": "b5", "status": "REVERTED", "created_at": "x"}]},
        build={"id": "b1", "name": "NBT: x", "status": "COMPLETED", "world": "minecraft:overworld", "parent_build_id": "b0"},
        stored={"undo_snapshot": True, "placed_template": True, "revision_base": True})
    result = await revisions.handle_get_build_source(api, "b1")
    text = result.content[0].text
    assert "Starlark artifact slk_0123456789abcdef" in text
    assert '"height": 8' in text and "mage_tower@3" in text
    assert "Requested position (walking plane) (100, 70, -40)" in text
    assert "Revision of build b0 (SUPERSEDED)" in text and "b5 (REVERTED)" in text
    assert "undo_build, verify_build, revise_build" in text
    assert "def build(height=8):" in text

    brief = await revisions.handle_get_build_source(api, "b1", include_source=False)
    assert "def build" not in brief.content[0].text
    assert "source" not in brief.structuredContent["source"]


async def test_get_build_source_explains_why_a_build_is_not_revisable(clients):
    api, _ = clients
    api.get_build_source.return_value = source_info(
        source=None, build={"id": "b1", "name": "NBT: hut.nbt", "status": "COMPLETED", "world": "minecraft:overworld"})
    text = (await revisions.handle_get_build_source(api, "b1")).content[0].text
    assert "Source: not recorded" in text
    assert "not revise_build (no source was recorded" in text


async def test_verify_build_summarizes_drift(clients):
    api, _ = clients
    api.verify_build.return_value = {
        "success": True, "build_id": "b1", "status": "COMPLETED",
        "report": {"template_blocks": 1000, "matching": 980, "missing": 12, "extra": 3, "changed": 5, "match_pct": 98.0,
                   "solid_template_blocks": 400, "solid_matching": 383, "solid_match_pct": 95.8,
                   "top_differences": [{"expected": "minecraft:glass_pane", "found": "minecraft:air", "count": 9}],
                   "samples": [{"x": 1, "y": 70, "z": 2, "expected": "minecraft:glass_pane", "found": "minecraft:air"}]}}
    text = (await revisions.handle_verify_build(api, "b1")).content[0].text
    assert "98.0% of 1000" in text and "95.8% of 400" in text
    assert "Missing (now air): 12" in text
    assert "minecraft:glass_pane -> minecraft:air x9" in text
    assert "(1, 70, 2)" in text
    assert "overwrites these differences" in text


async def test_revise_merges_props_and_replaces_in_place(clients):
    api, service = clients
    result = await revisions.handle_revise_build(api, "b1", props={"height": 12})
    data = result.structuredContent
    assert data["ok"] and not result.isError
    service.build.assert_awaited_once_with(SCRIPT, entry="build", props={"height": 12, "roof": "slate"}, root_size=None)
    args, kwargs = api.place_nbt_structure_bytes.await_args
    # Same requested position and rotation; the new artifact's ground offset is reapplied.
    assert args[2:7] == (100, 69, -40, "minecraft:overworld", "CLOCKWISE_90")
    assert kwargs["replaces_build_id"] == "b1" and kwargs["force"] is False
    assert kwargs["provenance"]["props"] == {"height": 12, "roof": "slate"}
    assert kwargs["provenance"]["artifact_id"] == BUILT["artifact_id"]
    text = result.content[0].text
    assert "Revision of build b1 (props changed)" in text
    assert "Replaced build b1, which is now SUPERSEDED" in text
    assert 'undo_build(build_id="b2")' in text


async def test_revise_dry_run_reports_revision(clients):
    api, _ = clients
    api.place_nbt_structure_bytes.return_value = {
        "success": True, "dry_run": True, "bounds": {}, "overwrite": {"template_blocks": 10},
        "revision": {"replaces_build_id": "b1", "cleared_bounds": {"min_x": 92}, "vs_current": {"unchanged": 7, "replaced": 3},
                     "conflicts": {"total": 0, "builds": [], "blocking": False}}}
    result = await revisions.handle_revise_build(api, "b1", source=SCRIPT.replace("8", "10"), dry_run=True)
    assert api.place_nbt_structure_bytes.await_args.args[9] is True
    assert result.structuredContent["placement"]["revision"]["replaces_build_id"] == "b1"
    assert "first cleared back to its saved terrain" in result.content[0].text
    assert "7 blocks stay the same, 3 change" in result.content[0].text


async def test_revise_moves_and_rotates(clients):
    api, _ = clients
    await revisions.handle_revise_build(api, "b1", x=110, rotation="NONE")
    args = api.place_nbt_structure_bytes.await_args.args
    assert args[2:7] == (110, 69, -40, "minecraft:overworld", "NONE")


async def test_revise_compile_failure_leaves_world_untouched(clients):
    api, service = clients
    service.build.return_value = {"ok": False, "error_kind": "starlark_error",
                                  "diagnostics": [{"code": "E1", "message": "boom"}], "hint": None}
    result = await revisions.handle_revise_build(api, "b1", source="def build(:")
    assert result.isError
    assert "did not compile" in result.content[0].text
    api.place_nbt_structure_bytes.assert_not_called()


async def test_revision_conflict_is_reported_without_change(clients):
    api, _ = clients
    request = httpx.Request("POST", "http://minecraft/api/world/structure/place")
    api.place_nbt_structure_bytes.side_effect = httpx.HTTPStatusError("conflict", request=request, response=httpx.Response(
        409, request=request, json={"success": False, "code": "revision_conflict", "error": "Later builds overlap build b1",
                                    "total": 1, "builds": [{"build_id": "b7", "name": "NBT: well", "status": "COMPLETED"}]}))
    result = await revisions.handle_revise_build(api, "b1", props={"height": 9})
    assert result.isError
    assert result.structuredContent["error_kind"] == "revision_conflict"
    assert "b7 (NBT: well)" in result.content[0].text and "force=true" in result.content[0].text


@pytest.mark.parametrize("info, kwargs, message", [
    (source_info(build={"id": "b1", "name": "x", "status": "SUPERSEDED", "world": "minecraft:overworld"},
                 lineage={"children": [{"build_id": "b2", "status": "COMPLETED"}]}),
     {"props": {"height": 9}}, "replaced by revision b2; revise that build instead"),
    (source_info(build={"id": "b1", "name": "x", "status": "REVERTED", "world": "minecraft:overworld"}),
     {"props": {"height": 9}}, "redo_build it first"),
    (source_info(source=None), {"props": {"height": 9}}, "no source was recorded"),
    (source_info(stored={"undo_snapshot": False}), {"props": {"height": 9}}, "saved terrain snapshot is missing"),
    (source_info(), {}, "Nothing to change"),
    (source_info(), {"props": {"height": 8}}, "Nothing to change"),
    (source_info(), {"schematic_id": "5"}, "not schematic_id"),
])
async def test_revise_rejections(clients, info, kwargs, message):
    api, service = clients
    api.get_build_source.return_value = info
    result = await revisions.handle_revise_build(api, "b1", **kwargs)
    assert result.isError
    assert message in result.content[0].text
    service.build.assert_not_called()
    api.place_nbt_structure_bytes.assert_not_called()


async def test_revise_without_recorded_script_needs_new_source(clients):
    api, service = clients
    info = source_info()
    del info["source"]["source"]
    api.get_build_source.return_value = info
    result = await revisions.handle_revise_build(api, "b1", props={"height": 9})
    assert result.isError and "pass the full source" in result.content[0].text
    await revisions.handle_revise_build(api, "b1", source=SCRIPT)
    service.build.assert_awaited_once()


async def test_revise_schematic_swaps_the_schematic(clients, monkeypatch):
    api, _ = clients
    catalog = AsyncMock()
    catalog.get_schematic.return_value = {"title": "Stone well", "placement": {"ground_level": 2}}
    catalog.get_schematic_nbt.return_value = b"well"
    monkeypatch.setattr(schematics, "_schematic_client", lambda: catalog)
    api.get_build_source.return_value = source_info(source={
        "kind": "schematic", "schematic_id": "100", "y_offset": -1, "target": {"x": 5, "y": 70, "z": 5},
        "details": {"apply_ground_offset": True, "include_entities": False}})
    result = await revisions.handle_revise_build(api, "b1", schematic_id="200")
    catalog.get_schematic.assert_awaited_once_with("200")
    args, kwargs = api.place_nbt_structure_bytes.await_args
    assert args[1] == "200.nbt" and args[2:5] == (5, 70 - schematics._ground_level({"placement": {"ground_level": 2}}), 5)
    assert args[7] is False  # include_entities kept from the original placement
    assert kwargs["replaces_build_id"] == "b1"
    assert kwargs["provenance"]["schematic_id"] == "200"
    assert "Replaced build b1" in result.content[0].text

    nothing = await revisions.handle_revise_build(api, "b1", schematic_id="100")
    assert nothing.isError and "Nothing to change" in nothing.content[0].text
    wrong = await revisions.handle_revise_build(api, "b1", props={"a": 1})
    assert wrong.isError and "schematic placement" in wrong.content[0].text


async def test_place_schematic_records_provenance(clients, monkeypatch):
    api, _ = clients
    catalog = AsyncMock()
    catalog.get_schematic.return_value = {"title": "Barn"}
    catalog.get_schematic_nbt.return_value = b"barn"
    monkeypatch.setattr(schematics, "_schematic_client", lambda: catalog)
    api.place_nbt_structure_bytes.return_value = {"success": True, "build_id": "b3", "undo_available": True}
    await schematics.handle_place_schematic(api, "42", 1, 70, 2)
    provenance = api.place_nbt_structure_bytes.await_args.kwargs["provenance"]
    assert provenance == {"kind": "schematic", "schematic_id": "42", "y_offset": 0, "target": {"x": 1, "y": 70, "z": 2},
                          "details": {"apply_ground_offset": True, "include_entities": True, "title": "Barn"}}
    assert api.place_nbt_structure_bytes.await_args.kwargs["replaces_build_id"] is None


def test_dry_run_text_flags_blocking_conflicts():
    text = format_dry_run({"overwrite": {}, "bounds": {}, "revision": {
        "replaces_build_id": "b1", "cleared_bounds": {}, "conflicts": {
            "total": 1, "blocking": True, "builds": [{"build_id": "b7", "name": "well"}]}}})
    assert "Later builds overlapping it would be erased: 1 (b7 (well))" in text
    assert "refused unless force=true" in text



async def test_undoing_a_revision_says_the_previous_version_is_back():
    from minecraft_mcp.handlers import builds
    api = AsyncMock()
    api.undo_build.return_value = {"success": True, "build_id": "b2", "undo_available": False, "redo_available": True,
                                   "parent_build_id": "b1", "parent_status": "COMPLETED"}
    text = (await builds.handle_undo_build(api, "b2")).content[0].text
    assert "This was a revision: build b1 is back in the world (COMPLETED)." in text
    api.redo_build.return_value = {"success": True, "build_id": "b2", "undo_available": True,
                                   "parent_build_id": "b1", "parent_status": "SUPERSEDED"}
    text = (await builds.handle_redo_build(api, "b2")).content[0].text
    assert "Build b1, which this revision replaced, is SUPERSEDED." in text
