#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = ["nbtlib>=2.0.4", "numpy>=2.0.0"]
# ///
"""Detect how many bottom layers of a schematic are terrain-fill (dirt/sand/gravel/stone)
rather than the actual build, by scanning the outer rim of each horizontal layer from the
bottom up. Patches the result into the schematic's existing meta.json under "placement".

Standalone by design: reimplements NBT parsing locally rather than importing the external
nbt-image-gen tool, so this script has no dependency on that pipeline.

Usage:
    uv run schematic-service/scripts/detect_ground_level.py --ids 2,4,5,9,10,11,71
    uv run schematic-service/scripts/detect_ground_level.py --ids 2,4,5,9,10,11,71 --write
    uv run schematic-service/scripts/detect_ground_level.py --all --write --report out.json
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path
from typing import Any

import nbtlib
import numpy as np

AIR_NAMES = frozenset({"minecraft:air", "minecraft:cave_air", "minecraft:void_air"})
AIR = -1

# Natural terrain-fill blocks — deliberately excludes crafted materials (cobblestone,
# stone_bricks, etc.) so legitimate stone walls don't false-positive as "ground".
_TERRACOTTA_COLORS = (
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
)
_SANDSTONE_VARIANTS = ("", "cut_", "chiseled_", "smooth_")

EARTH_BLOCKS: frozenset[str] = frozenset(
    {
        "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:podzol", "minecraft:mycelium",
        "minecraft:grass_block", "minecraft:dirt_path", "minecraft:farmland", "minecraft:mud",
        "minecraft:muddy_mangrove_roots", "minecraft:sand", "minecraft:red_sand",
        "minecraft:gravel", "minecraft:clay", "minecraft:stone", "minecraft:andesite",
        "minecraft:diorite", "minecraft:granite", "minecraft:deepslate",
        "minecraft:terracotta",
    }
    | {f"minecraft:{prefix}sandstone" for prefix in _SANDSTONE_VARIANTS}
    | {f"minecraft:{prefix}red_sandstone" for prefix in _SANDSTONE_VARIANTS}
    | {f"minecraft:{color}_terracotta" for color in _TERRACOTTA_COLORS}
)


class Structure:
    __slots__ = ("size", "palette", "grid")

    def __init__(self, size: tuple[int, int, int], palette: list[str], grid: np.ndarray):
        self.size = size
        self.palette = palette
        self.grid = grid


def load_structure(path: Path) -> Structure:
    nbt_file = nbtlib.load(str(path))
    root = nbt_file if "size" in nbt_file else nbt_file[""]

    size_tag = root["size"]
    w, h, d = int(size_tag[0]), int(size_tag[1]), int(size_tag[2])

    palette = [str(entry["Name"]) for entry in root["palette"]]
    air_states = {i for i, name in enumerate(palette) if name in AIR_NAMES}

    grid = np.full((w, h, d), AIR, dtype=np.int16)
    for block in root["blocks"]:
        x, y, z = int(block["pos"][0]), int(block["pos"][1]), int(block["pos"][2])
        state = int(block["state"])
        if state in air_states:
            continue
        grid[x, y, z] = state

    return Structure(size=(w, h, d), palette=palette, grid=grid)


def non_air_bbox(grid: np.ndarray) -> dict[str, int] | None:
    coords = np.argwhere(grid != AIR)
    if coords.size == 0:
        return None
    mn = coords.min(axis=0)
    mx = coords.max(axis=0)
    return {
        "min_x": int(mn[0]), "min_y": int(mn[1]), "min_z": int(mn[2]),
        "max_x": int(mx[0]), "max_y": int(mx[1]), "max_z": int(mx[2]),
    }


def detect_ground_level(
    structure: Structure,
    min_rim_fraction: float,
    max_scan_fraction: float,
) -> dict[str, Any]:
    grid = structure.grid
    palette = structure.palette
    earth_states = {i for i, name in enumerate(palette) if name in EARTH_BLOCKS}

    bbox = non_air_bbox(grid)
    if bbox is None:
        return {"ground_level": 0, "ground_level_reason": "structure is empty", "ground_level_rim_fill": 0.0}

    x0, x1 = bbox["min_x"], bbox["max_x"]
    z0, z1 = bbox["min_z"], bbox["max_z"]

    is_rim = np.zeros((x1 - x0 + 1, z1 - z0 + 1), dtype=bool)
    is_rim[0, :] = True
    is_rim[-1, :] = True
    is_rim[:, 0] = True
    is_rim[:, -1] = True
    rim_count = int(is_rim.sum())

    h = structure.size[1]
    max_layer = max(0, min(h - 1, int(h * max_scan_fraction)))

    ground_level = 0
    last_fill = 0.0
    last_range = "none"
    for y in range(0, max_layer + 1):
        layer = grid[x0 : x1 + 1, y, z0 : z1 + 1]
        rim_cells = layer[is_rim]
        earth_hits = sum(1 for state in rim_cells if state in earth_states)
        fill = earth_hits / rim_count if rim_count else 0.0

        if fill >= min_rim_fraction:
            ground_level = y + 1
            last_fill = fill
            last_range = f"y=0..{y}"
        else:
            reason = (
                f"rim earth {last_fill:.0%} at {last_range}, drops to {fill:.0%} at y={y}"
                if ground_level > 0
                else f"rim earth only {fill:.0%} at y=0 (threshold {min_rim_fraction:.0%})"
            )
            return {
                "ground_level": ground_level,
                "ground_level_reason": reason,
                "ground_level_rim_fill": round(last_fill, 3),
            }

    reason = (
        f"rim earth {last_fill:.0%} through scanned range {last_range} (hit max-scan-fraction cap)"
        if ground_level > 0
        else "no earth rim detected"
    )
    return {
        "ground_level": ground_level,
        "ground_level_reason": reason,
        "ground_level_rim_fill": round(last_fill, 3),
    }


def process_one(
    schematic_id: str,
    nbt_dir: Path,
    images_dir: Path,
    min_rim_fraction: float,
    max_scan_fraction: float,
    write: bool,
) -> dict[str, Any] | None:
    nbt_path = nbt_dir / f"{schematic_id}.nbt"
    meta_path = images_dir / schematic_id / "meta.json"
    if not nbt_path.exists() or not meta_path.exists():
        return None

    structure = load_structure(nbt_path)
    result = detect_ground_level(structure, min_rim_fraction, max_scan_fraction)
    result["schematic_id"] = schematic_id
    result["size"] = list(structure.size)

    if write:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        placement = meta.get("placement")
        if not isinstance(placement, dict):
            placement = {}
        placement["ground_level"] = result["ground_level"]
        placement["ground_level_reason"] = result["ground_level_reason"]
        placement["ground_level_rim_fill"] = result["ground_level_rim_fill"]
        meta["placement"] = placement
        meta_path.write_text(json.dumps(meta, indent=2), encoding="utf-8")

    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ids", help="Comma-separated schematic ids to process")
    parser.add_argument("--all", action="store_true", help="Process every id with both an .nbt and meta.json")
    parser.add_argument("--sample", type=int, default=0, help="Randomly sample N ids in addition to --ids")
    parser.add_argument("--seed", type=int, default=0, help="Random seed for --sample")
    parser.add_argument("--write", action="store_true", help="Persist results into meta.json (default: dry run)")
    parser.add_argument("--report", type=Path, help="Write full JSON results to this path")
    parser.add_argument("--min-rim-fraction", type=float, default=0.5)
    parser.add_argument("--max-scan-fraction", type=float, default=0.5)
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=Path(__file__).resolve().parents[2] / "schematic-service-data",
        help="Path to schematic-service-data",
    )
    args = parser.parse_args()

    nbt_dir = args.data_dir / "Schematics-nbt"
    images_dir = args.data_dir / "schematic-images"
    if not nbt_dir.is_dir() or not images_dir.is_dir():
        print(f"error: expected {nbt_dir} and {images_dir} to exist", file=sys.stderr)
        raise SystemExit(1)

    ids: list[str] = []
    if args.ids:
        ids.extend(part.strip() for part in args.ids.split(",") if part.strip())
    if args.all:
        available = {p.stem for p in nbt_dir.glob("*.nbt")} & {p.name for p in images_dir.iterdir() if p.is_dir()}
        ids.extend(sorted(available, key=lambda s: (len(s), s)))
    if args.sample:
        available = {p.stem for p in nbt_dir.glob("*.nbt")} & {p.name for p in images_dir.iterdir() if p.is_dir()}
        pool = sorted(available - set(ids))
        random.Random(args.seed).shuffle(pool)
        ids.extend(pool[: args.sample])

    if not ids:
        print("error: pass --ids, --all, and/or --sample", file=sys.stderr)
        raise SystemExit(1)

    # de-dupe, preserve order
    seen: set[str] = set()
    ordered_ids = [i for i in ids if not (i in seen or seen.add(i))]

    results: list[dict[str, Any]] = []
    skipped: list[str] = []
    for schematic_id in ordered_ids:
        result = process_one(
            schematic_id, nbt_dir, images_dir,
            args.min_rim_fraction, args.max_scan_fraction, args.write,
        )
        if result is None:
            skipped.append(schematic_id)
            continue
        results.append(result)
        print(
            f"{schematic_id:>8}  ground_level={result['ground_level']:<4} "
            f"size={result['size']}  {result['ground_level_reason']}"
        )

    if skipped:
        print(f"\nskipped {len(skipped)} id(s) missing .nbt or meta.json: {', '.join(skipped[:20])}"
              + (" ..." if len(skipped) > 20 else ""), file=sys.stderr)

    print(f"\nprocessed {len(results)} schematic(s){' [written]' if args.write else ' [dry run]'}")

    if args.report:
        args.report.write_text(json.dumps(results, indent=2), encoding="utf-8")
        print(f"report written to {args.report}")


if __name__ == "__main__":
    main()
