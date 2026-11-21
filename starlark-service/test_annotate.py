from __future__ import annotations

import numpy as np
from fastapi.testclient import TestClient
from PIL import Image

from starlark_service import annotate, previews
from starlark_service.app import create_app
from test_previews import TOWER_SOURCE, build, png
from test_regions import HEADERS, region_nbt, upload


def test_grid_step_keeps_lines_apart():
    assert annotate.grid_step(40) == 1
    assert annotate.grid_step(30) == 2
    assert annotate.grid_step(8) == 8
    assert annotate.grid_step(0.01) == annotate.GRID_STEPS[-1]


def test_ticks_are_multiples_including_negative_origins():
    assert annotate.ticks(0, 10, 4) == [0, 4, 8]
    assert annotate.ticks(-20, 10, 4) == [-20, -16, -12]
    assert annotate.ticks(-3, 6, 2) == [-2, 0, 2]
    assert annotate.ticks(3676, 45, 8) == [3680, 3688, 3696, 3704, 3712, 3720]


def test_boundary_pixels_follow_axis_direction():
    # Block 0..9 at 10 px/block: boundary 3 is 30 px from the left when ascending, 70 when descending.
    assert annotate.boundary_px(3, 0, 10, True, 10) == 30
    assert annotate.boundary_px(3, 0, 10, False, 10) == 70
    assert annotate.block_px(3, 0, 10, True, 10) == 35
    assert annotate.block_px(3, 0, 10, False, 10) == 65


def test_spot_heights_thin_out_on_small_cells():
    assert annotate.spot_every(8, 20) == 1
    assert annotate.spot_every(2, 24) == 2
    assert annotate.spot_every(1, 10) == 16


def test_frame_adds_margins_around_the_map():
    img = Image.new("RGB", (200, 100), (100, 100, 100))
    heights = np.full((10, 20), 3, dtype=np.int16)
    out = annotate.frame(img, "top", (20, 5, 10), origin=(-30, 64, 7), heights=heights)
    assert out.size == (200 + annotate.MARGIN_LEFT + annotate.MARGIN_RIGHT,
                        100 + annotate.MARGIN_TOP + annotate.MARGIN_BOTTOM)
    # The map itself is pasted unchanged apart from grid lines and labels.
    assert out.getpixel((annotate.MARGIN_LEFT + 5, annotate.MARGIN_TOP + 5)) == (100, 100, 100)
    for view in annotate.VIEW_AXES:
        annotate.frame(img, view, (20, 10, 20))  # every view's axes resolve


def test_single_views_are_framed_by_default_and_fit_max_px(config):
    client = TestClient(create_app(config))
    artifact = build(client, TOWER_SOURCE)
    framed = png(client.get(f"/artifacts/{artifact}/images/top", params={"max_px": 400}))
    bare = png(client.get(f"/artifacts/{artifact}/images/top", params={"max_px": 400, "annotate": False}))
    assert max(framed.size) <= 400 and framed.size != bare.size
    assert framed.getpixel((2, 2)) == annotate.FRAME_BG
    # The iso and the sheet are never framed.
    iso = png(client.get(f"/artifacts/{artifact}/images/iso", params={"max_px": 400}))
    assert iso.getpixel((0, 0)) == previews.IMAGE_BG
    section = png(client.get(f"/artifacts/{artifact}/images/section", params={"max_px": 400, "cut_y": 1}))
    assert section.getpixel((2, 2)) == annotate.FRAME_BG


def test_regions_are_labelled_in_world_coordinates(config, monkeypatch):
    seen = {}
    real = annotate.frame

    def spy(img, view, size, origin=None, heights=None, title=None):
        seen.update(view=view, size=size, origin=origin, heights=heights, title=title)
        return real(img, view, size, origin, heights, title)

    monkeypatch.setattr(annotate, "frame", spy)
    client = TestClient(create_app(config))
    artifact = upload(client, region_nbt()).json()["artifact_id"]
    png(client.get(f"/artifacts/{artifact}/images/top", params={"max_px": 300}))
    assert seen["origin"] == (100, 60, -20) and seen["size"] == (4, 3, 4)
    assert seen["heights"].shape == (4, 4) and seen["heights"][1, 1] == 1  # red wool on the stone floor
    png(client.get(f"/artifacts/{artifact}/images/section", params={"max_px": 300, "cut_x": 1}))
    assert seen["view"] == "section_x" and "x=101" in seen["title"]  # world coordinate of the cut
