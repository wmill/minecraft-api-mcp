import json
from pathlib import Path

from fastapi.testclient import TestClient

from schematic_service.app import create_app
from schematic_service.config import ServiceConfig


def test_tags_route_is_registered_before_schematic_id(tmp_path: Path):
    catalog_path = tmp_path / "catalog.json"
    nbt_dir = tmp_path / "nbt"
    images_dir = tmp_path / "images"
    nbt_dir.mkdir()
    images_dir.mkdir()
    (nbt_dir / "1.nbt").write_bytes(b"nbt")
    catalog_path.write_text(
        json.dumps(
            [
                {
                    "schematic_id": "1",
                    "title": "Stone Tower",
                    "keywords": "stone, tower",
                    "structure_type": "tower",
                    "style": "medieval",
                    "size_category": "small",
                }
            ]
        ),
        encoding="utf-8",
    )
    app = create_app(
        ServiceConfig(
            data_dir=tmp_path,
            catalog_path=catalog_path,
            nbt_dir=nbt_dir,
            images_dir=images_dir,
            elasticsearch_url="http://127.0.0.1:1",
            index_name="test_schematics",
        )
    )

    response = TestClient(app).get("/schematics/tags")

    assert response.status_code == 200
    assert response.json()["tags"] == [{"tag": "stone", "count": 1}, {"tag": "tower", "count": 1}]
