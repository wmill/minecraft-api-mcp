from __future__ import annotations

from pathlib import Path

import pytest

from starlark_service.config import ServiceConfig

SERVICE_DIR = Path(__file__).resolve().parent

SIMPLE_SOURCE = (
    "def build():\n"
    '    return component(name="Cell", props={},\n'
    '                     body=place_block([0, 0, 0], block("minecraft:stone")))\n'
)


def make_config(cache_dir: Path, **overrides) -> ServiceConfig:
    values = dict(
        tool_dir=SERVICE_DIR / "starlark-to-nbt",
        cache_dir=cache_dir,
        build_timeout_s=15.0,
        build_memory_mb=2048,
        max_source_bytes=256 * 1024,
        max_root_volume=2_000_000,
        max_nbt_bytes=16 * 1024 * 1024,
        max_concurrent_builds=2,
        cache_max_bytes=1024 * 1024 * 1024,
    )
    values.update(overrides)
    return ServiceConfig(**values)


@pytest.fixture
def config(tmp_path):
    return make_config(tmp_path / "cache")
