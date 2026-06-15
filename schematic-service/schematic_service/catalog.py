"""Catalog loading and normalization."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


PUBLIC_META_KEYS = {
    "size",
    "non_air_block_count",
    "non_air_bbox",
    "placement",
    "top_blocks",
}


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as file:
        return json.load(file)


def load_optional_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    data = load_json(path)
    return data if isinstance(data, dict) else {}


def public_meta(meta: dict[str, Any]) -> dict[str, Any]:
    return {key: meta[key] for key in PUBLIC_META_KEYS if key in meta}


def normalize_keyword_tags(keywords: Any) -> list[str]:
    if not isinstance(keywords, str):
        return []

    seen: set[str] = set()
    tags: list[str] = []
    for value in keywords.split(","):
        tag = " ".join(value.strip().lower().split())
        if tag and tag not in seen:
            seen.add(tag)
            tags.append(tag)
    return tags


def normalize_catalog_row(row: dict[str, Any], nbt_dir: Path, images_dir: Path) -> dict[str, Any]:
    schematic_id = str(row.get("schematic_id", "")).strip()
    if not schematic_id:
        raise ValueError("catalog row missing schematic_id")

    nbt_path = nbt_dir / f"{schematic_id}.nbt"
    meta = public_meta(load_optional_json(images_dir / schematic_id / "meta.json"))

    doc = {
        "schematic_id": schematic_id,
        "title": row.get("title") or f"Schematic {schematic_id}",
        "description": row.get("description") or "",
        "keywords": row.get("keywords") or "",
        "keyword_tags": normalize_keyword_tags(row.get("keywords")),
        "structure_type": row.get("structure_type") or "unknown",
        "style": row.get("style") or "unknown",
        "placement": row.get("placement") or {},
        "size_category": row.get("size_category") or "unknown",
        "has_interior": row.get("has_interior"),
        "confidence": row.get("confidence"),
        "size": row.get("size") or meta.get("size") or {},
        "non_air_block_count": row.get("non_air_block_count") or meta.get("non_air_block_count"),
        "placeable": nbt_path.exists(),
        "nbt_filename": nbt_path.name if nbt_path.exists() else None,
        "image_metadata": meta,
    }
    doc["search_text"] = " ".join(
        str(value)
        for value in [
            doc["title"],
            doc["description"],
            doc["keywords"],
            doc["structure_type"],
            doc["style"],
            doc["size_category"],
            doc["placement"].get("notes") if isinstance(doc["placement"], dict) else "",
        ]
        if value
    )
    return doc


def load_catalog(catalog_path: Path, nbt_dir: Path, images_dir: Path) -> list[dict[str, Any]]:
    data = load_json(catalog_path)
    if not isinstance(data, list):
        raise ValueError(f"catalog must be a JSON array: {catalog_path}")

    docs: list[dict[str, Any]] = []
    for row in data:
        if isinstance(row, dict):
            docs.append(normalize_catalog_row(row, nbt_dir, images_dir))
    return docs
