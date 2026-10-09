"""Subprocess entry: render one artifact's preview images.

Usage:
  python -m starlark_service.preview_worker <artifact.nbt> <output_dir>
      Base views: iso/top/north/south/east/west PNGs plus meta.json (nbt-image-gen's
      metadata with the chosen background added).
  python -m starlark_service.preview_worker <artifact.nbt> <preview_dir> <spec>
      One on-demand variant into an existing preview directory, where spec is
      `iso:<axis>:<at>` (cutaway), `section:<axis>:<at>`, or `floors`.
Variant files are written to a temporary name and renamed, so a killed worker never
leaves a partial image under a real name.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

from nbt_image_gen.loader import load_structure
from nbt_image_gen.metadata import build_metadata
from nbt_image_gen.renderer import render_side, render_top

from . import render
from .previews import PREVIEW_VERSION

# Native render sizes stay near these bounds; the HTTP layer resizes to max_px anyway.
ORTHO_TARGET_PX = 1024
ISO_TARGET_PX = 2048


def _scale(target: int, extent: int, maximum: int) -> int:
    return max(1, min(maximum, target // max(1, extent)))


def _iso_scale(structure) -> int:
    width, _, depth = structure.size
    # Iso width is 2 * (width + depth) projection units.
    return _scale(ISO_TARGET_PX, 2 * (width + depth), 6)


def _ortho_scale(structure) -> int:
    return _scale(ORTHO_TARGET_PX, max(structure.size), 8)


def _save(img, path: Path) -> None:
    tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    img.save(tmp, format="PNG")
    os.replace(tmp, path)


def render_base(structure, out: Path) -> None:
    out.mkdir(parents=True)
    ortho = _ortho_scale(structure)
    views = {"top": render_top(structure, scale=ortho)}
    for direction in ("north", "south", "east", "west"):
        views[direction] = render_side(structure, direction, scale=ortho)
    views["iso"] = render.render_iso(structure, structure.grid, scale=_iso_scale(structure))
    for view, img in views.items():
        img.save(out / f"{view}.png")

    meta = build_metadata(structure)
    name, colour = render.choose_background(list(views.values()))
    meta["background"] = {"name": name, "rgb": list(colour)}
    meta["preview_version"] = PREVIEW_VERSION
    (out / "meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")


def _section(structure, out: Path, axis: str, at: int, background) -> None:
    target = out / f"section_{axis}{at}.png"
    if not target.exists():
        _save(render.render_section(structure, axis, at, background, scale=_ortho_scale(structure)), target)


def render_variant(structure, out: Path, spec: str) -> None:
    meta = json.loads((out / "meta.json").read_text(encoding="utf-8"))
    background = tuple(meta["background"]["rgb"])
    kind, _, rest = spec.partition(":")
    if kind == "floors":
        height = structure.size[1]
        floors = [{"floor": y, "cut": render.floor_cut(y, height)} for y in render.detect_floors(structure.grid)]
        for entry in floors:
            _section(structure, out, "y", entry["cut"], background)
        tmp = out / f".floors.json.{os.getpid()}.tmp"
        tmp.write_text(json.dumps(floors), encoding="utf-8")
        os.replace(tmp, out / "floors.json")
        return
    axis, _, at_text = rest.partition(":")
    at = int(at_text)
    if axis not in render.AXES or not 0 <= at < structure.size[render.AXES.index(axis)]:
        raise ValueError(f"cut {axis}={at} is outside the artifact")
    if kind == "iso":
        grid = render.cutaway(structure.grid, axis, at)
        _save(render.render_iso(structure, grid, scale=_iso_scale(structure)), out / f"iso_{axis}{at}.png")
    elif kind == "section":
        _section(structure, out, axis, at, background)
    else:
        raise ValueError(f"unknown variant: {spec}")


def main(argv: list[str]) -> None:
    structure = load_structure(argv[0])
    if len(argv) == 2:
        render_base(structure, Path(argv[1]))
    else:
        render_variant(structure, Path(argv[1]), argv[2])


if __name__ == "__main__":
    main(sys.argv[1:])
