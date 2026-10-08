import io
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from schematic_service import images
from schematic_service.app import create_app
from schematic_service.catalog import load_catalog
from schematic_service.config import ServiceConfig


def write_png(path: Path, size: tuple[int, int], color=(200, 30, 30, 255)) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGBA", size, color).save(path)


@pytest.fixture
def setup(tmp_path: Path):
    catalog_path = tmp_path / "catalog.json"
    nbt_dir, images_dir = tmp_path / "nbt", tmp_path / "images"
    nbt_dir.mkdir()
    (nbt_dir / "1.nbt").write_bytes(b"nbt")
    catalog_path.write_text(json.dumps([{"schematic_id": "1", "title": "Hut"}]), encoding="utf-8")
    write_png(images_dir / "1" / "iso.png", (900, 600))
    for view in ("top", "north", "south", "east", "west"):
        write_png(images_dir / "1" / f"{view}.png", (40, 30))
    # An unscreened render with no catalog entry or NBT, like failed conversions.
    write_png(images_dir / "2" / "iso.png", (50, 50))
    cfg = ServiceConfig(data_dir=tmp_path, catalog_path=catalog_path, nbt_dir=nbt_dir, images_dir=images_dir,
                        elasticsearch_url="http://127.0.0.1:1", index_name="test")
    images.render_view.cache_clear()
    images.render_sheet.cache_clear()
    return TestClient(create_app(cfg)), cfg


def png(response) -> Image.Image:
    assert response.status_code == 200, response.text
    assert response.headers["content-type"] == "image/png"
    return Image.open(io.BytesIO(response.content))


def test_view_is_downscaled_and_flattened(setup):
    client, _ = setup
    img = png(client.get("/schematics/1/images/iso", params={"max_px": 300}))
    assert img.size == (300, 200)
    assert img.mode == "RGB"
    assert png(client.get("/schematics/1/images/iso")).size == (768, 512)


def test_small_views_are_not_upscaled_and_max_px_is_clamped(setup):
    client, _ = setup
    assert png(client.get("/schematics/1/images/top", params={"max_px": 1000})).size == (40, 30)
    assert png(client.get("/schematics/1/images/iso", params={"max_px": 5})).size == (64, 43)


def test_sheet_has_all_views(setup):
    client, _ = setup
    img = png(client.get("/schematics/1/images/sheet", params={"max_px": 100}))
    assert img.size == (3 * 100 + 4 * images.PAD, 2 * (images.LABEL_H + 100) + 3 * images.PAD)


@pytest.mark.parametrize("path,status", [
    ("/schematics/2/images/iso", 404),     # images on disk but not catalogued
    ("/schematics/2/images/sheet", 404),
    ("/schematics/3/images/iso", 404),
    ("/schematics/abc/images/iso", 400),
    ("/schematics/1/images/side", 400),
])
def test_rejections(setup, path, status):
    client, _ = setup
    assert client.get(path).status_code == status


def test_traversal_never_serves_files(setup):
    client, _ = setup
    assert client.get("/schematics/1/images/..%2F..%2Fcatalog.json").status_code in (400, 404)
    with pytest.raises(ValueError):
        images.safe_image_path(Path("/data/images"), "1", "../../catalog")


def test_missing_view_is_404(setup):
    client, cfg = setup
    (cfg.images_dir / "1" / "west.png").unlink()
    assert client.get("/schematics/1/images/west").status_code == 404
    assert png(client.get("/schematics/1/images/sheet")).size[0] > 0


def test_oversized_source_is_refused(setup, monkeypatch):
    client, _ = setup
    monkeypatch.setattr(images, "MAX_SOURCE_PIXELS", 1000)
    assert client.get("/schematics/1/images/iso").status_code == 413


def test_catalog_doc_lists_views(setup):
    _, cfg = setup
    (doc,) = load_catalog(cfg.catalog_path, cfg.nbt_dir, cfg.images_dir)
    assert doc["views"] == list(images.VIEWS)
