"""Subprocess build worker.

Reads one build request as JSON on stdin, runs the starlark-to-nbt pipeline
under a memory rlimit, writes the structure NBT to the requested output path,
and emits a result JSON on stdout. Exit code 0 covers both build success and
build failure (diagnostics); any other exit means the worker crashed (e.g. an
OOM kill from the rlimit) and the parent reports error_kind "crash".

The wall-clock timeout is enforced by the parent (sandbox.py), which kills
this process; RLIMIT_AS only works reliably on Linux, so the container is the
real memory boundary.
"""

from __future__ import annotations

import dataclasses
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any

from starlark_to_nbt.model import BuildError, Diagnostic, Point
from starlark_to_nbt.pipeline import build_source
from starlark_to_nbt.serialize import write_structure_nbt


def _apply_memory_limit(memory_mb: int) -> None:
    # RLIMIT_DATA rather than RLIMIT_AS: the Rust starlark runtime reserves
    # large virtual address ranges up front and SIGABRTs under an AS cap even
    # for tiny builds; DATA caps what is actually allocated.
    limit = memory_mb * 1024 * 1024
    try:
        import resource

        resource.setrlimit(resource.RLIMIT_DATA, (limit, limit))
    except (ImportError, OSError, ValueError):
        print("warning: could not apply memory limit (expected on macOS)", file=sys.stderr)


def _diagnostic_dict(diagnostic: Diagnostic) -> dict[str, Any]:
    value: dict[str, Any] = {
        "code": diagnostic.code,
        "message": diagnostic.message,
        "component_path": diagnostic.component_path,
        "file": diagnostic.source.file if diagnostic.source else None,
        "line": diagnostic.source.line if diagnostic.source else None,
        "region": diagnostic.region.to_dict() if diagnostic.region else None,
        "coordinates": diagnostic.coordinates.to_list() if diagnostic.coordinates else None,
        "details": _jsonable(diagnostic.details),
    }
    return value


def _jsonable(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(key): _jsonable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonable(item) for item in value]
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return _jsonable(dataclasses.asdict(value))
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return str(value)


def _failure(error_kind: str, diagnostics: list[dict[str, Any]]) -> dict[str, Any]:
    return {"ok": False, "error_kind": error_kind, "diagnostics": diagnostics}


def _limit_failure(message: str) -> dict[str, Any]:
    return _failure("resource_limit", [{
        "code": "resource_limit", "message": message, "component_path": "<root>",
        "file": None, "line": None, "region": None, "coordinates": None, "details": {},
    }])


def run(request: dict[str, Any]) -> dict[str, Any]:
    tool_dir = Path(request["tool_dir"])
    output_path = Path(request["output_path"])
    limits = request["limits"]
    root_size = request.get("root_size")

    _apply_memory_limit(limits["memory_mb"])
    try:
        result = build_source(
            request["source"],
            entry=request.get("entry", "build"),
            props=request.get("props") or {},
            root_size=Point(*root_size) if root_size else None,
            base_dir=tool_dir / "scripts",
            loader_root=tool_dir,
        )
    except BuildError as exc:
        kind = "starlark_error" if any(d.code == "starlark_error" for d in exc.diagnostics) else "build_error"
        return _failure(kind, [_diagnostic_dict(d) for d in exc.diagnostics])

    size = result.volume.bounds.size
    if size.x * size.y * size.z > limits["max_root_volume"]:
        return _limit_failure(
            f"root volume {size.x}x{size.y}x{size.z} exceeds the maximum of "
            f"{limits['max_root_volume']} blocks; build a smaller structure"
        )

    try:
        write_structure_nbt(result.volume, output_path)
    except BuildError as exc:
        return _failure("build_error", [_diagnostic_dict(d) for d in exc.diagnostics])
    nbt_bytes = output_path.stat().st_size
    if nbt_bytes > limits["max_nbt_bytes"]:
        output_path.unlink(missing_ok=True)
        return _limit_failure(
            f"structure NBT is {nbt_bytes} bytes, exceeding the maximum of "
            f"{limits['max_nbt_bytes']}; build a smaller or less varied structure"
        )

    counts = Counter(voxel.block.block_type for voxel in result.volume.voxels.values())
    return {
        "ok": True,
        "size": size.to_list(),
        "block_count": len(result.volume.voxels),
        "entity_count": len(result.volume.entities),
        "ground_level": result.metadata.ground_level,
        "y_offset": result.metadata.y_offset,
        "nbt_bytes": nbt_bytes,
        "palette": [
            {"block": block, "count": count}
            for block, count in sorted(counts.items(), key=lambda item: (-item[1], item[0]))[:20]
        ],
    }


def main() -> None:
    request = json.loads(sys.stdin.read())
    print(json.dumps(run(request)))


if __name__ == "__main__":
    main()
