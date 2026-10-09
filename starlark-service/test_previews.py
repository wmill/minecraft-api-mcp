from __future__ import annotations

import io
import os

from fastapi.testclient import TestClient
from PIL import Image

from conftest import SIMPLE_SOURCE, make_config
from starlark_service import cache, previews
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
