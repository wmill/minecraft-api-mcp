from __future__ import annotations

import gzip
import io
import os
import time

import nbtlib
from fastapi.testclient import TestClient
from nbtlib.tag import Compound, Int, List, String
from PIL import Image

from conftest import make_config
from starlark_service import cache, previews, regions
from starlark_service.app import create_app

HEADERS = {"Content-Type": "application/octet-stream",
           "X-Region-Origin": "100,60,-20", "X-Region-World": "minecraft:overworld"}


def region_nbt(size=(4, 3, 4), blocks=None, palette=("minecraft:air", "minecraft:stone", "minecraft:red_wool")) -> bytes:
    """A world-capture-shaped structure NBT: air is in the palette and in the block list."""
    if blocks is None:
        blocks = {}
        for x in range(size[0]):
            for y in range(size[1]):
                for z in range(size[2]):
                    blocks[(x, y, z)] = 1 if y == 0 else 0
        blocks[(1, 1, 1)] = 2
    root = Compound({
        "DataVersion": Int(4438),
        "size": List[Int]([Int(v) for v in size]),
        "palette": List[Compound]([Compound({"Name": String(name)}) for name in palette]),
        "blocks": List[Compound]([Compound({"pos": List[Int]([Int(v) for v in pos]), "state": Int(state)})
                                  for pos, state in blocks.items()]),
        "entities": List[Compound]([]),
    })
    out = io.BytesIO()
    with gzip.GzipFile(fileobj=out, mode="wb") as handle:
        nbtlib.File(root).write(handle)
    return out.getvalue()


def upload(client: TestClient, data: bytes, headers=None):
    return client.post("/renders", content=data, headers=HEADERS if headers is None else headers)


def test_upload_is_content_addressed_and_renders(config):
    client = TestClient(create_app(config))
    data = region_nbt()
    first = upload(client, data)
    assert first.status_code == 200, first.text
    body = first.json()
    assert body["artifact_id"].startswith("rgn_") and body["size"] == [4, 3, 4] and body["cached"] is False
    again = upload(client, data).json()
    assert again == {**body, "cached": True}
    # The same blocks elsewhere are a different region (origin is part of the id).
    moved = upload(client, data, {**HEADERS, "X-Region-Origin": "0,60,0"}).json()
    assert moved["artifact_id"] != body["artifact_id"]

    meta = client.get(f"/artifacts/{body['artifact_id']}").json()
    assert meta["kind"] == "region" and meta["origin"] == [100, 60, -20] and meta["world"] == "minecraft:overworld"
    assert client.get(f"/artifacts/{body['artifact_id']}/source").status_code == 404

    sheet = client.get(f"/artifacts/{body['artifact_id']}/images/sheet", params={"max_px": 128})
    assert sheet.status_code == 200, sheet.text
    assert sheet.headers["X-Artifact-Size"] == "4x3x4"
    Image.open(io.BytesIO(sheet.content)).verify()
    section = client.get(f"/artifacts/{body['artifact_id']}/images/section", params={"cut_y": 1})
    assert section.status_code == 200, section.text


def test_upload_validation(config):
    client = TestClient(create_app(make_config(config.cache_dir, max_nbt_bytes=4096, max_root_volume=1000)))
    assert upload(client, b"not gzip").status_code == 400
    assert upload(client, gzip.compress(b"\x00garbage")).status_code == 400
    assert upload(client, os.urandom(8192)).status_code == 413
    too_big = upload(client, region_nbt(size=(20, 20, 20), blocks={(0, 0, 0): 1}))
    assert too_big.status_code == 400 and "maximum is 1000" in too_big.json()["detail"]
    assert upload(client, region_nbt(), {**HEADERS, "X-Region-Origin": "1,2"}).status_code == 400
    assert upload(client, region_nbt(), {**HEADERS, "X-Region-World": "../etc"}).status_code == 400
    bomb = gzip.compress(b"\x00" * (regions.MAX_DECOMPRESSED_BYTES + 1))
    assert len(bomb) < 4096 * 32
    assert regions.region_id(b"x", None, None).startswith("rgn_")
    try:
        regions.inspect(bomb, 1000)
        raise AssertionError("expected InvalidRegion")
    except regions.InvalidRegion as exc:
        assert "decompresses past" in str(exc)


def test_regions_are_evicted_with_their_previews(config):
    client = TestClient(create_app(config))
    old = upload(client, region_nbt()).json()["artifact_id"]
    assert client.get(f"/artifacts/{old}/images/iso").status_code == 200
    assert previews.preview_dir(config.cache_dir, old).is_dir()
    stamp = time.time() - 3600
    os.utime(cache.nbt_path(config.cache_dir, old), (stamp, stamp))
    new = upload(client, region_nbt(size=(2, 2, 2), blocks={(0, 0, 0): 1}), {**HEADERS, "X-Region-Origin": "0,0,0"}).json()["artifact_id"]
    assert cache.stats(config.cache_dir)["artifacts"] == 2
    new_bytes = cache.nbt_path(config.cache_dir, new).stat().st_size + cache.meta_path(config.cache_dir, new).stat().st_size
    cache.evict(config.cache_dir, new_bytes)
    assert not cache.nbt_path(config.cache_dir, old).exists()
    assert not previews.preview_dir(config.cache_dir, old).exists()
    assert cache.nbt_path(config.cache_dir, new).exists()
