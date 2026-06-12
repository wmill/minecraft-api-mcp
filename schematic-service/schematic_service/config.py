"""Configuration for the optional schematic catalog service."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import dotenv_values


@dataclass(frozen=True)
class ServiceConfig:
    data_dir: Path
    catalog_path: Path
    nbt_dir: Path
    images_dir: Path
    elasticsearch_url: str
    index_name: str


def load_config() -> ServiceConfig:
    service_dir = Path(__file__).resolve().parents[1]
    repo_dir = Path(__file__).resolve().parents[2]
    env_file = service_dir / ".env"
    env = {**dotenv_values(env_file), **os.environ}

    data_dir = Path(env.get("SCHEMATIC_DATA_DIR", repo_dir / "schematic-service-data")).resolve()
    catalog_path = Path(env.get("SCHEMATIC_CATALOG_PATH", data_dir / "schematic_catalog_gemma3.json")).resolve()
    nbt_dir = Path(env.get("SCHEMATIC_NBT_DIR", data_dir / "Schematics-nbt")).resolve()
    images_dir = Path(env.get("SCHEMATIC_IMAGES_DIR", data_dir / "schematic-images")).resolve()

    return ServiceConfig(
        data_dir=data_dir,
        catalog_path=catalog_path,
        nbt_dir=nbt_dir,
        images_dir=images_dir,
        elasticsearch_url=env.get("ELASTICSEARCH_URL", "http://localhost:9200").rstrip("/"),
        index_name=env.get("SCHEMATIC_INDEX", "minecraft_schematics"),
    )
