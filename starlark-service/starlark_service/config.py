"""Configuration for the optional Starlark build service."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import dotenv_values


@dataclass(frozen=True)
class ServiceConfig:
    tool_dir: Path
    cache_dir: Path
    build_timeout_s: float
    build_memory_mb: int
    max_source_bytes: int
    max_root_volume: int
    max_nbt_bytes: int
    max_concurrent_builds: int
    cache_max_bytes: int

    @property
    def lib_dir(self) -> Path:
        return self.tool_dir / "lib"

    @property
    def catalog_path(self) -> Path:
        return self.tool_dir / "docs" / "component-catalog.md"

    @property
    def examples_dir(self) -> Path:
        return self.tool_dir / "examples"


def load_config() -> ServiceConfig:
    service_dir = Path(__file__).resolve().parents[1]
    repo_dir = Path(__file__).resolve().parents[2]
    env_file = service_dir / ".env"
    env = {**dotenv_values(env_file), **os.environ}

    return ServiceConfig(
        tool_dir=Path(env.get("STARLARK_TOOL_DIR", service_dir / "starlark-to-nbt")).resolve(),
        cache_dir=Path(env.get("STARLARK_CACHE_DIR", repo_dir / "starlark-service-data")).resolve(),
        build_timeout_s=float(env.get("STARLARK_BUILD_TIMEOUT_S", "15")),
        # The Rust starlark runtime reserves ~1GB of data mappings up front, so
        # this is a coarse runaway guard; the container memory limit is the
        # real boundary. Values below ~1024 abort every build.
        build_memory_mb=int(env.get("STARLARK_BUILD_MEMORY_MB", "2048")),
        max_source_bytes=int(env.get("STARLARK_MAX_SOURCE_BYTES", str(256 * 1024))),
        max_root_volume=int(env.get("STARLARK_MAX_ROOT_VOLUME", "2000000")),
        max_nbt_bytes=int(env.get("STARLARK_MAX_NBT_BYTES", str(16 * 1024 * 1024))),
        max_concurrent_builds=int(env.get("STARLARK_MAX_CONCURRENT_BUILDS", "2")),
        cache_max_bytes=int(env.get("STARLARK_CACHE_MAX_BYTES", str(1024 * 1024 * 1024))),
    )
