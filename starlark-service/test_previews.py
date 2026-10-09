from __future__ import annotations

import io
import os

import numpy as np
from fastapi.testclient import TestClient
from nbt_image_gen.loader import AIR
from PIL import Image

from conftest import SIMPLE_SOURCE, make_config
from starlark_service import cache, previews, render
from starlark_service.app import create_app

# A stone block with one red marker jutting out of its north (-Z) face.
TOWER_SOURCE = (
    "def build():\n"
    '    return component(name="Tower", props={}, min_size=[3, 5, 3], body=group([\n'
    '        fill_region([0, 0, 1], [3, 5, 3], block("minecraft:stone_bricks")),\n'
    '        place_block([1, 2, 0], block("minecraft:red_wool")),\n'
    "    ]))\n"
)


def build(client: TestClient, source: str = TOWER_SOURCE) -> str:
    body = {"source": source} if source == TOWER_SOURCE else {"source": source, "root_size": [1, 1, 1]}
    result = client.post("/build", json=body).json()
    assert result["ok"] is True
    return result["artifact_id"]


def png(response) -> Image.Image:
    assert response.status_code == 200, response.text
    assert response.headers["content-type"] == "image/png"
    return Image.open(io.BytesIO(response.content))


def test_single_views_render_and_fit_max_px(config):
    client = TestClient(create_app(config))
    artifact = build(client)
    for view in previews.VIEWS:
        img = png(client.get(f"/artifacts/{artifact}/images/{view}", params={"max_px": 128}))
        assert max(img.size) <= 128
        assert img.mode == "RGB"
        if view == "iso":  # empty corner is slate, not white, so white blocks stay visible
            assert img.getpixel((0, 0)) == previews.IMAGE_BG
    # Rendered once, cached beside the artifact with nbt-image-gen's metadata.
    directory = previews.preview_dir(config.cache_dir, artifact)
    assert sorted(p.name for p in directory.iterdir()) == sorted([f"{v}.png" for v in previews.VIEWS] + ["meta.json"])


def test_side_views_follow_schematic_orientation(config):
    # The marker juts from the north face: seen from the north, hidden from the south, at the top of the plan.
    client = TestClient(create_app(config))
    artifact = build(client)
    north = png(client.get(f"/artifacts/{artifact}/images/north", params={"max_px": 64}))
    south = png(client.get(f"/artifacts/{artifact}/images/south", params={"max_px": 64}))

    def has_red(img):
        data = img.convert("RGB").tobytes()
        return any(data[i] > 150 and data[i + 1] < 80 and data[i + 2] < 80 for i in range(0, len(data), 3))

    assert has_red(north)
    assert not has_red(south)
    top = png(client.get(f"/artifacts/{artifact}/images/top", params={"max_px": 64}))
    w, h = top.size
    assert has_red(top.crop((0, 0, w, h // 2)))  # north is up in the top view
    assert not has_red(top.crop((0, h // 2, w, h)))


def test_sheet_is_a_labelled_grid(config):
    client = TestClient(create_app(config))
    artifact = build(client)
    sheet = png(client.get(f"/artifacts/{artifact}/images/sheet", params={"max_px": 96}))
    assert sheet.size == (3 * 96 + 4 * previews.PAD, 2 * (previews.LABEL_H + 96) + 3 * previews.PAD)


def test_invalid_view_and_unknown_artifact(config):
    client = TestClient(create_app(config))
    artifact = build(client)
    assert client.get(f"/artifacts/{artifact}/images/bottom").status_code == 400
    assert client.get("/artifacts/not-an-id/images/iso").status_code == 400
    assert client.get("/artifacts/slk_0000000000000000/images/iso").status_code == 404


def test_render_failure_is_503_and_not_cached(config):
    client = TestClient(create_app(config))
    artifact = build(client)
    cache.nbt_path(config.cache_dir, artifact).write_bytes(b"not nbt")
    response = client.get(f"/artifacts/{artifact}/images/iso")
    assert response.status_code == 503
    assert "preview unavailable" in response.json()["detail"]
    assert not previews.preview_dir(config.cache_dir, artifact).exists()
    assert not list(config.cache_dir.glob("*/.*.previews.tmp"))


def test_render_timeout_is_503(tmp_path):
    config = make_config(tmp_path / "cache", preview_timeout_s=0.001)
    client = TestClient(create_app(config))
    artifact = build(client)
    assert client.get(f"/artifacts/{artifact}/images/iso").status_code == 503


def test_eviction_and_stats_include_previews(config):
    client = TestClient(create_app(config))
    old = build(client, SIMPLE_SOURCE.replace("stone", "dirt"))
    png(client.get(f"/artifacts/{old}/images/iso"))
    old_previews = previews.preview_dir(config.cache_dir, old)
    preview_bytes = sum(p.stat().st_size for p in old_previews.iterdir())
    assert preview_bytes > 0
    before = cache.stats(config.cache_dir)["bytes"]
    assert before >= preview_bytes

    new = build(client)
    os.utime(cache.nbt_path(config.cache_dir, old), (1, 1))
    new_size = cache.stats(config.cache_dir)["bytes"] - before
    cache.evict(config.cache_dir, new_size)
    assert not cache.nbt_path(config.cache_dir, old).exists()
    assert not old_previews.exists()
    assert cache.nbt_path(config.cache_dir, new).exists()


# A closed two-storey stone box: floor slabs at y=0 and y=4, roof at y=8, and a red block on
# the ground floor that no exterior view can see.
ROOM_SOURCE = (
    "def build():\n"
    '    stone = block("minecraft:stone_bricks")\n'
    '    parts = [place_block([3, 1, 3], block("minecraft:red_wool"))]\n'
    "    for y in [0, 4, 8]:\n"
    "        parts.append(fill_region([0, y, 0], [7, y + 1, 7], stone))\n"
    "    for y in [1, 5]:\n"
    "        parts.append(fill_region([0, y, 0], [7, y + 3, 1], stone))\n"
    "        parts.append(fill_region([0, y, 6], [7, y + 3, 7], stone))\n"
    "        parts.append(fill_region([0, y, 1], [1, y + 3, 6], stone))\n"
    "        parts.append(fill_region([6, y, 1], [7, y + 3, 6], stone))\n"
    '    return component(name="Room", props={}, min_size=[7, 9, 7], body=group(parts))\n'
)


def has_red(img: Image.Image) -> bool:
    data = img.convert("RGB").tobytes()
    return any(data[i] > 150 and data[i + 1] < 80 and data[i + 2] < 80 for i in range(0, len(data), 3))


def build_room(client: TestClient) -> str:
    result = client.post("/build", json={"source": ROOM_SOURCE}).json()
    assert result["ok"] is True, result
    return result["artifact_id"]


def test_cuts_reveal_the_interior(config):
    client = TestClient(create_app(config))
    artifact = build_room(client)
    url = f"/artifacts/{artifact}/images"
    assert not has_red(png(client.get(f"{url}/iso")))
    response = client.get(f"{url}/iso", params={"cut_y": 2})
    assert response.headers["x-artifact-size"] == "7x9x7"
    assert has_red(png(response))
    assert has_red(png(client.get(f"{url}/section", params={"cut_y": 1})))  # through the marker
    assert has_red(png(client.get(f"{url}/section", params={"cut_z": 3})))
    assert has_red(png(client.get(f"{url}/iso", params={"cut_x": 3})))
    # Cut through the upper storey: it is empty and its floor slab hides the marker.
    assert not has_red(png(client.get(f"{url}/section", params={"cut_y": 6})))
    # Variants are cached beside the base views.
    names = {p.name for p in previews.preview_dir(config.cache_dir, artifact).iterdir()}
    assert {"iso_y2.png", "section_y1.png", "section_z3.png", "iso_x3.png", "section_y6.png"} <= names


def test_cut_sheet_has_cutaway_and_section(config):
    client = TestClient(create_app(config))
    artifact = build_room(client)
    sheet = png(client.get(f"/artifacts/{artifact}/images/sheet", params={"cut_y": 2, "max_px": 96}))
    assert sheet.size == (2 * 96 + 3 * previews.PAD, previews.LABEL_H + 96 + 2 * previews.PAD)
    assert has_red(sheet)


def test_floors_sheet_lists_detected_storeys(config):
    client = TestClient(create_app(config))
    artifact = build_room(client)
    response = client.get(f"/artifacts/{artifact}/images/floors", params={"max_px": 96})
    assert response.headers["x-preview-floors"] == "0:2,4:6"
    sheet = png(response)
    assert sheet.size == (2 * 96 + 3 * previews.PAD, previews.LABEL_H + 96 + 2 * previews.PAD)
    assert has_red(sheet)


def test_invalid_cuts_are_rejected(config):
    client = TestClient(create_app(config))
    artifact = build_room(client)
    url = f"/artifacts/{artifact}/images"
    assert client.get(f"{url}/iso", params={"cut_x": 1, "cut_y": 1}).status_code == 400
    assert client.get(f"{url}/top", params={"cut_y": 1}).status_code == 400
    assert client.get(f"{url}/section").status_code == 400
    assert client.get(f"{url}/floors", params={"cut_y": 1}).status_code == 400
    out_of_range = client.get(f"{url}/iso", params={"cut_y": 9})
    assert out_of_range.status_code == 400
    assert "y 0..8" in out_of_range.json()["detail"]


def test_outdated_preview_directory_is_rerendered(config):
    client = TestClient(create_app(config))
    artifact = build(client)
    png(client.get(f"/artifacts/{artifact}/images/iso"))
    directory = previews.preview_dir(config.cache_dir, artifact)
    (directory / "meta.json").write_text('{"size": {"width": 3, "height": 5, "depth": 3}}')
    (directory / "stale.png").write_bytes(b"")
    png(client.get(f"/artifacts/{artifact}/images/iso"))
    assert previews.read_meta(directory)["preview_version"] == previews.PREVIEW_VERSION
    assert not (directory / "stale.png").exists()


def test_detect_floors_and_cut_height():
    grid = np.full((5, 12, 5), AIR, dtype=np.int16)
    grid[:, 0, :] = grid[:, 5, :] = grid[:, 11, :] = 0  # ground, mid floor, roof
    assert render.detect_floors(grid) == [0, 5]
    assert render.floor_cut(5, 12) == 7
    assert render.floor_cut(10, 12) == 11
    # No roofs anywhere: fall back to evenly spaced levels rather than nothing.
    open_grid = np.full((5, 12, 5), AIR, dtype=np.int16)
    open_grid[:, 0, :] = 0
    assert render.detect_floors(open_grid) == [0, 3, 6, 9]


def test_background_avoids_silhouette_colours():
    def blob(colour):
        img = Image.new("RGBA", (20, 20), (0, 0, 0, 0))
        img.paste(Image.new("RGBA", (10, 10), colour + (255,)), (5, 5))
        return img
    assert render.choose_background([blob((120, 80, 40))])[0] == "slate"
    # Pale blue glass blends into slate, so it moves to another background.
    assert render.choose_background([blob((175, 188, 205))])[0] != "slate"
