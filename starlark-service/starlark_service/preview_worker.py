"""Subprocess entry: render one artifact's preview views with nbt-image-gen.

Usage: python -m starlark_service.preview_worker <artifact.nbt> <output_dir>
Writes iso/top/north/south/east/west PNGs plus nbt-image-gen's meta.json.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from nbt_image_gen.loader import load_structure
from nbt_image_gen.metadata import build_metadata
from nbt_image_gen.renderer import render_isometric, render_side, render_top

# Native render sizes stay near these bounds; the HTTP layer resizes to max_px anyway.
ORTHO_TARGET_PX = 1024
ISO_TARGET_PX = 2048


def _scale(target: int, extent: int, maximum: int) -> int:
    return max(1, min(maximum, target // max(1, extent)))


def main(nbt_path: str, output_dir: str) -> None:
    structure = load_structure(nbt_path)
    width, height, depth = structure.size
    out = Path(output_dir)
    out.mkdir(parents=True)

    ortho = _scale(ORTHO_TARGET_PX, max(width, height, depth), 8)
    render_top(structure, scale=ortho).save(out / "top.png")
    for direction in ("north", "south", "east", "west"):
        render_side(structure, direction, scale=ortho).save(out / f"{direction}.png")
    # Iso width is 2 * (width + depth) projection units.
    render_isometric(structure, scale=_scale(ISO_TARGET_PX, 2 * (width + depth), 6)).save(out / "iso.png")

    meta = build_metadata(structure)
    (out / "meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
