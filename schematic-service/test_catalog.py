from pathlib import Path

from schematic_service.catalog import normalize_catalog_row


def test_normalize_enriches_metadata_without_source(tmp_path: Path):
    nbt_dir = tmp_path / "Schematics-nbt"
    images_dir = tmp_path / "schematic-images"
    meta_dir = images_dir / "1"
    nbt_dir.mkdir()
    meta_dir.mkdir(parents=True)
    (nbt_dir / "1.nbt").write_bytes(b"nbt")
    (meta_dir / "meta.json").write_text(
        """
        {
          "source": "/private/source/1.schematic",
          "non_air_block_count": 12,
          "top_blocks": [{"name": "minecraft:stone", "count": 12, "fraction": 1.0}],
          "placement": {"has_base": true}
        }
        """,
        encoding="utf-8",
    )

    doc = normalize_catalog_row(
        {
            "schematic_id": "1",
            "title": "Stone Hut",
            "description": "A compact stone hut",
            "keywords": "stone hut",
        },
        nbt_dir,
        images_dir,
    )

    assert doc["placeable"] is True
    assert doc["nbt_filename"] == "1.nbt"
    assert doc["non_air_block_count"] == 12
    assert doc["image_metadata"]["top_blocks"][0]["name"] == "minecraft:stone"
    assert "source" not in doc["image_metadata"]
