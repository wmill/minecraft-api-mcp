"""Downscaled preview images for catalogued schematics.

The images directory is raw render-tool output: it also holds renders of schematics that
never converted to NBT (some over 500 MP). Callers must gate on the catalog before
resolving a path here; the pixel guard is only a backstop.
"""

from __future__ import annotations

import io
import struct
from functools import lru_cache
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

VIEWS = ("iso", "top", "north", "south", "east", "west")
SHEET = "sheet"
MAX_SOURCE_PIXELS = 16_000_000
MIN_PX, MAX_PX = 64, 1024
DEFAULT_VIEW_PX = 768
DEFAULT_TILE_PX = 256

# Verified against NBT: top.png has north up/east right; side views are elevations seen
# from that side; iso is viewed from the south-east.
SHEET_LABELS = {
    "iso": "ISO (from SE)",
    "top": "TOP (north up)",
    "north": "NORTH (-Z side)",
    "south": "SOUTH (+Z side)",
    "east": "EAST (+X side)",
    "west": "WEST (-X side)",
}
LABEL_H = 22
PAD = 6
BG = (255, 255, 255)
LABEL_BG = (230, 230, 230)
BORDER = (90, 90, 90)


class ImageTooLarge(ValueError):
    pass


def safe_image_path(images_dir: Path, schematic_id: str, view: str) -> Path:
    if not schematic_id.isdigit():
        raise ValueError("schematic_id must be numeric")
    if view not in VIEWS:
        raise ValueError(f"view must be one of {', '.join(VIEWS + (SHEET,))}")
    root = images_dir.resolve()
    path = (root / schematic_id / f"{view}.png").resolve()
    if root not in path.parents:
        raise ValueError("invalid schematic_id")
    return path


def available_views(images_dir: Path, schematic_id: str) -> list[str]:
    try:
        names = {p.name for p in (images_dir / schematic_id).iterdir()}
    except OSError:
        return []
    return [view for view in VIEWS if f"{view}.png" in names]


def clamp_px(value: int | None, default: int) -> int:
    return default if value is None else max(MIN_PX, min(MAX_PX, value))


def png_size(path: Path) -> tuple[int, int]:
    with path.open("rb") as handle:
        header = handle.read(24)
    if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"not a PNG: {path.name}")
    return struct.unpack(">II", header[16:24])


def _open(path: Path) -> Image.Image:
    width, height = png_size(path)
    if width * height > MAX_SOURCE_PIXELS:
        raise ImageTooLarge(f"{path.parent.name}/{path.name} is {width}x{height}; too large to preview")
    with Image.open(path) as img:
        img.load()
        if img.mode in ("RGBA", "LA", "P"):
            img = img.convert("RGBA")
            flat = Image.new("RGB", img.size, BG)
            flat.paste(img, (0, 0), img)
            return flat
        return img.convert("RGB")


def _fit(img: Image.Image, max_px: int) -> Image.Image:
    scale = min(max_px / img.width, max_px / img.height, 1.0)
    if scale >= 1.0:
        return img
    size = (max(1, round(img.width * scale)), max(1, round(img.height * scale)))
    return img.resize(size, Image.LANCZOS)


def _png(img: Image.Image) -> bytes:
    buffer = io.BytesIO()
    img.save(buffer, format="PNG", optimize=True)
    return buffer.getvalue()


@lru_cache(maxsize=256)
def render_view(path: Path, max_px: int, mtime_ns: int) -> bytes:
    """Downscale (never upscale) to fit max_px, flattened onto white. mtime_ns keys the cache."""
    return _png(_fit(_open(path), max_px))


def _font():
    try:
        return ImageFont.load_default(size=14)
    except TypeError:
        return ImageFont.load_default()


@lru_cache(maxsize=128)
def render_sheet(paths: tuple[tuple[str, Path], ...], tile_px: int, mtime_ns: int) -> bytes:
    """Labelled 3x2 grid of the given views, each fit into a tile_px square."""
    cols, rows = 3, (len(paths) + 2) // 3
    width = cols * tile_px + (cols + 1) * PAD
    height = rows * (LABEL_H + tile_px) + (rows + 1) * PAD
    canvas = Image.new("RGB", (width, height), BG)
    draw = ImageDraw.Draw(canvas)
    font = _font()
    for index, (view, path) in enumerate(paths):
        row, col = divmod(index, cols)
        x0 = PAD + col * (tile_px + PAD)
        y0 = PAD + row * (LABEL_H + tile_px + PAD)
        draw.rectangle([x0, y0, x0 + tile_px - 1, y0 + LABEL_H - 1], fill=LABEL_BG)
        draw.text((x0 + 4, y0 + 3), SHEET_LABELS[view], fill=(20, 20, 20), font=font)
        top = y0 + LABEL_H
        draw.rectangle([x0, top, x0 + tile_px - 1, top + tile_px - 1], outline=BORDER)
        img = _open(path)
        scale = min((tile_px - 4) / img.width, (tile_px - 4) / img.height)
        size = (max(1, round(img.width * scale)), max(1, round(img.height * scale)))
        # Upscale small orthographic views crisply; downscale smoothly.
        img = img.resize(size, Image.NEAREST if scale >= 1 else Image.LANCZOS)
        canvas.paste(img, (x0 + (tile_px - size[0]) // 2, top + (tile_px - size[1]) // 2))
    return _png(canvas)
