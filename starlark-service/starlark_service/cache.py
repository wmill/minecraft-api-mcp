"""Content-addressed artifact cache for built structure NBT.

Builds are deterministic (byte-for-byte identical output for identical
source/entry/props against the same component library), so artifacts are keyed
by a hash of the request plus a fingerprint of the vendored lib/ directory and compiler source.
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
from pathlib import Path
from typing import Any

ARTIFACT_ID_PATTERN = re.compile(r"^slk_[0-9a-f]{16}$")


def lib_fingerprint(tool_dir: Path) -> str:
    """Hash of the component library and compiler source; busts the cache when either changes.

    The compiler is included because builtins and lowering live there (e.g. math functions),
    so a submodule bump can change output for an unchanged script and lib/.
    """
    digest = hashlib.sha256()
    for root, pattern in ((tool_dir / "lib", "*.star"), (tool_dir / "src" / "starlark_to_nbt", "**/*.py")):
        if root.is_dir():
            for path in sorted(root.glob(pattern)):
                digest.update(path.relative_to(tool_dir).as_posix().encode("utf-8"))
                digest.update(path.read_bytes())
    return digest.hexdigest()[:16]


def artifact_id(source: str, entry: str, props: dict[str, Any],
                root_size: list[int] | None, fingerprint: str) -> str:
    key = json.dumps(
        {"source": source, "entry": entry, "props": props, "root_size": root_size, "lib": fingerprint},
        sort_keys=True, separators=(",", ":"),
    )
    return "slk_" + hashlib.sha256(key.encode("utf-8")).hexdigest()[:16]


def nbt_path(cache_dir: Path, identifier: str) -> Path:
    return cache_dir / identifier[4:6] / f"{identifier}.nbt"


def meta_path(cache_dir: Path, identifier: str) -> Path:
    return cache_dir / identifier[4:6] / f"{identifier}.json"


def source_path(cache_dir: Path, identifier: str) -> Path:
    return cache_dir / identifier[4:6] / f"{identifier}.star"


def load_metadata(cache_dir: Path, identifier: str) -> dict[str, Any] | None:
    nbt = nbt_path(cache_dir, identifier)
    meta = meta_path(cache_dir, identifier)
    if not (nbt.exists() and meta.exists()):
        return None
    return json.loads(meta.read_text(encoding="utf-8"))


def store_metadata(cache_dir: Path, identifier: str, metadata: dict[str, Any]) -> None:
    path = meta_path(cache_dir, identifier)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def store_source(cache_dir: Path, identifier: str, source: str) -> None:
    """Persist the Starlark source alongside the artifact for inspection."""
    path = source_path(cache_dir, identifier)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")


def _previews_bytes(path: Path) -> int:
    """Size of an artifact's rendered preview directory (see previews.py), if any."""
    previews = path.with_suffix(".previews")
    return sum(p.stat().st_size for p in previews.iterdir()) if previews.is_dir() else 0


def stats(cache_dir: Path) -> dict[str, int]:
    artifacts = 0
    total = 0
    for path in cache_dir.glob("*/slk_*.nbt"):
        artifacts += 1
        total += path.stat().st_size + _previews_bytes(path)
        for companion in (path.with_suffix(".json"), path.with_suffix(".star")):
            if companion.exists():
                total += companion.stat().st_size
    return {"artifacts": artifacts, "bytes": total}


def evict(cache_dir: Path, max_bytes: int) -> None:
    """Delete oldest artifacts (by NBT mtime) and their companions and previews until under the byte cap."""
    entries = []
    total = 0
    for path in cache_dir.glob("*/slk_*.nbt"):
        companions = [path.with_suffix(".json"), path.with_suffix(".star")]
        size = (path.stat().st_size + sum(c.stat().st_size for c in companions if c.exists())
                + _previews_bytes(path))
        entries.append((path.stat().st_mtime, path, companions, size))
        total += size
    entries.sort()
    for _, path, companions, size in entries:
        if total <= max_bytes:
            break
        path.unlink(missing_ok=True)
        for companion in companions:
            companion.unlink(missing_ok=True)
        shutil.rmtree(path.with_suffix(".previews"), ignore_errors=True)
        total -= size


def touch(cache_dir: Path, identifier: str) -> None:
    """Refresh mtime on a cache hit so LRU eviction keeps hot artifacts."""
    path = nbt_path(cache_dir, identifier)
    if path.exists():
        path.touch()
