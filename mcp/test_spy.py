"""Spy mode: sanitized call events in a spool directory and the read-only viewer."""

import base64
import json
from unittest.mock import AsyncMock

import anyio
import pytest
from mcp.shared.memory import create_connected_server_and_client_session
from mcp.types import CallToolResult, ImageContent, TextContent
from starlette.testclient import TestClient

from minecraft_mcp import server as server_module
from minecraft_mcp import spy as spy_module
from minecraft_mcp.server import MinecraftMCPServer
from minecraft_mcp.spy import REDACTED, Spy, SpoolSpy, make_spy, sanitize_args
from minecraft_mcp.spy_viewer.app import create_app

PNG = b"\x89PNG\r\n\x1a\nfake"


def image_result(text="done", error=False):
    return CallToolResult(
        content=[TextContent(type="text", text=text),
                 ImageContent(type="image", data=base64.b64encode(PNG).decode(), mimeType="image/png")],
        structuredContent={"ok": not error}, isError=error,
    )


def files(directory):
    return sorted(p.name for p in directory.iterdir())


def test_sanitize_redacts_tokens_and_marks_scripts():
    args = sanitize_args("build_starlark_structure", {
        "source": "def build(ctx):\n    pass\n", "lock_id": "secret", "placement": {"lock_id": "x", "x": 1},
        "reservation_token": "t",
    })
    assert args["source"] == {"kind": "starlark", "code": "def build(ctx):\n    pass\n"}
    assert args["lock_id"] == REDACTED
    assert args["reservation_token"] == REDACTED
    assert args["placement"] == {"lock_id": REDACTED, "x": 1}


def test_sanitize_shortens_block_arrays_and_long_lists():
    blocks = [[["minecraft:stone"] * 20 for _ in range(8)] for _ in range(32)]
    args = sanitize_args("set_blocks", {"start_x": 1, "blocks": blocks, "points": list(range(50)), "few": [1, 2]})
    assert args == {"start_x": 1, "blocks": "[32×8×20 blocks]", "points": "[list: 50 items]", "few": [1, 2]}


def test_spool_spy_writes_start_end_and_images(tmp_path):
    spy = SpoolSpy(tmp_path)
    call_id = spy.start("map_terrain", {"x1": 0, "lock_id": "secret"})
    start = json.loads((tmp_path / f"{call_id}-start.json").read_text())
    assert start["tool"] == "map_terrain" and start["args"] == {"x1": 0, "lock_id": REDACTED}

    spy.end(call_id, "map_terrain", image_result("relief map"), 1.25)
    end = json.loads((tmp_path / f"{call_id}-end.json").read_text())
    assert end["duration_ms"] == 1250 and end["is_error"] is False and end["text"] == "relief map"
    assert end["images"] == [f"{call_id}-0.png"]
    assert (tmp_path / f"{call_id}-0.png").read_bytes() == PNG
    assert not [n for n in files(tmp_path) if n.endswith(".tmp")]


def test_lock_tool_results_are_redacted(tmp_path):
    spy = SpoolSpy(tmp_path)
    call_id = spy.start("acquire_area_lock", {})
    spy.end(call_id, "acquire_area_lock", CallToolResult(content=[TextContent(type="text", text="lock abc123")]), 0.1)
    assert json.loads((tmp_path / f"{call_id}-end.json").read_text())["text"] == "[redacted]"


def test_pruning_keeps_newest_calls(tmp_path, monkeypatch):
    monkeypatch.setattr(spy_module, "KEEP_CALLS", 3)
    monkeypatch.setattr(spy_module, "PRUNE_EVERY", 1)
    spy = SpoolSpy(tmp_path)
    ids = []
    for _ in range(5):
        call_id = spy.start("get_players", {})
        spy.end(call_id, "get_players", image_result(), 0.0)
        ids.append(call_id)
    remaining = files(tmp_path)
    assert not any(n.startswith(ids[0]) or n.startswith(ids[1]) for n in remaining)
    assert all(f"{i}-start.json" in remaining for i in ids[2:])


def test_make_spy_off_when_unset(tmp_path):
    assert type(make_spy("")) is Spy
    assert isinstance(make_spy(str(tmp_path / "spool")), SpoolSpy)


async def call(server, name, arguments):
    with anyio.fail_after(10):
        async with create_connected_server_and_client_session(server.server) as client:
            return await client.call_tool(name, arguments)


async def test_dispatch_records_call_without_changing_result(tmp_path, monkeypatch):
    expected = image_result("built")
    monkeypatch.setattr(server_module, "get_handler", lambda name: AsyncMock(return_value=expected))
    server = MinecraftMCPServer("http://unused", spy=SpoolSpy(tmp_path))
    assert await call(server, "build_starlark_structure", {"source": "def build(ctx): pass"}) == expected
    names = files(tmp_path)
    assert len([n for n in names if n.endswith("-start.json")]) == 1
    assert len([n for n in names if n.endswith("-end.json")]) == 1
    assert len([n for n in names if n.endswith(".png")]) == 1


async def test_dispatch_records_handler_errors(tmp_path, monkeypatch):
    monkeypatch.setattr(server_module, "get_handler", lambda name: AsyncMock(side_effect=RuntimeError("boom")))
    server = MinecraftMCPServer("http://unused", spy=SpoolSpy(tmp_path))
    result = await call(server, "get_players", {})
    assert result.isError
    end = json.loads(next(tmp_path.glob("*-end.json")).read_text())
    assert end["is_error"] is True and "boom" in end["text"]


async def test_failing_spy_never_changes_result(tmp_path, monkeypatch):
    expected = image_result("built")
    monkeypatch.setattr(server_module, "get_handler", lambda name: AsyncMock(return_value=expected))
    spy = SpoolSpy(tmp_path)

    def broken(*_args):
        raise OSError("disk full")

    monkeypatch.setattr(spy, "_write_bytes", broken)
    server = MinecraftMCPServer("http://unused", spy=spy)
    assert await call(server, "get_players", {}) == expected


async def test_no_spy_dir_writes_nothing(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(server_module, "get_handler", lambda name: AsyncMock(return_value=image_result()))
    server = MinecraftMCPServer("http://unused")
    assert not server.spy.enabled
    assert not (await call(server, "get_players", {})).isError
    assert files(tmp_path) == []


def test_viewer_merges_events_and_serves_images(tmp_path):
    spy = SpoolSpy(tmp_path)
    done = spy.start("map_terrain", {})
    spy.end(done, "map_terrain", image_result(), 0.5)
    running = spy.start("build_starlark_structure", {"source": "x = 1"})

    client = TestClient(create_app(tmp_path))
    calls = client.get("/api/events").json()
    assert [c["call_id"] for c in calls] == [done, running]
    assert calls[0]["end"]["images"] == [f"{done}-0.png"]
    assert calls[1]["end"] is None and calls[1]["args"]["source"]["code"] == "x = 1"

    image = client.get(f"/files/{done}-0.png")
    assert image.status_code == 200 and image.content == PNG
    assert client.get("/").status_code == 200


@pytest.mark.parametrize("name", [f"x-end.json", "..%2Fsecret.png", "missing.png", ".%2E%2Fx.png"])
def test_viewer_rejects_non_image_and_outside_files(tmp_path, name):
    (tmp_path.parent / "secret.png").write_bytes(PNG)
    (tmp_path / "x-end.json").write_text("{}")
    assert TestClient(create_app(tmp_path)).get(f"/files/{name}").status_code == 404


def test_reader_new_events_reports_each_file_once(tmp_path):
    from minecraft_mcp.spy_viewer.app import SpoolReader

    spy = SpoolSpy(tmp_path)
    reader = SpoolReader(tmp_path)
    seen = set(reader.event_names())
    call_id = spy.start("get_players", {})
    assert [e["type"] for e in reader.new_events(seen)] == ["start"]
    spy.end(call_id, "get_players", image_result(), 0.0)
    assert [e["type"] for e in reader.new_events(seen)] == ["end"]
    assert reader.new_events(seen) == []


def smooth(spy, dry_run, result, **extra):
    args = {"x1": 0, "z1": 0, "x2": 9, "z2": 9, "method": "pad", "lock_id": "secret", **extra}
    if dry_run is not None:
        args["dry_run"] = dry_run
    call_id = spy.start("smooth_terrain", args)
    spy.end(call_id, "smooth_terrain", result, 0.1)
    return call_id, json.loads((spy.dir / f"{call_id}-end.json").read_text())


def text_result(text="smoothed", error=False):
    return CallToolResult(content=[TextContent(type="text", text=text)], isError=error)


def test_applied_smooth_reuses_matching_dry_run_images(tmp_path):
    spy = SpoolSpy(tmp_path)
    dry_id, dry = smooth(spy, True, image_result("dry run"), px_per_block=4)
    applied_id, applied = smooth(spy, None, text_result(), lock_id="other")
    assert applied["images"] == [f"{applied_id}-0.png"]
    assert applied["images_from"] == dry_id
    assert (tmp_path / f"{applied_id}-0.png").read_bytes() == PNG
    # Used once: a second applied run has nothing to reuse.
    _, again = smooth(spy, None, text_result())
    assert again["images"] == [] and again["images_from"] is None


@pytest.mark.parametrize("change", [{"x2": 10}, {"method": "gaussian"}])
def test_applied_smooth_with_different_args_gets_no_images(tmp_path, change):
    spy = SpoolSpy(tmp_path)
    smooth(spy, True, image_result("dry run"))
    _, applied = smooth(spy, None, text_result(), **change)
    assert applied["images"] == [] and applied["images_from"] is None


def test_failed_applied_smooth_keeps_dry_run_for_retry(tmp_path):
    spy = SpoolSpy(tmp_path)
    dry_id, _ = smooth(spy, True, image_result("dry run"))
    _, failed = smooth(spy, None, text_result("area locked", error=True))
    assert failed["images"] == []
    _, retried = smooth(spy, None, text_result())
    assert retried["images_from"] == dry_id


def test_stale_dry_run_is_not_reused(tmp_path, monkeypatch):
    spy = SpoolSpy(tmp_path)
    smooth(spy, True, image_result("dry run"))
    monkeypatch.setattr(spy_module, "DRY_RUN_MAX_AGE_S", -1)
    _, applied = smooth(spy, None, text_result())
    assert applied["images"] == []
