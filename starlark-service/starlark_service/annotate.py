"""Map frame for single orthographic previews: title, coordinate grid, spot heights, scale bar.

Borrowed from the mod's TerrainMapRenderer so LLMs can read positions and heights off a
preview as they do off `map_terrain`: grid lines at block boundaries labelled with
coordinates (world coordinates for captured regions, which know their origin, local
otherwise), spot heights in the top view, an orientation note and a scale bar. Heights
come from text labels, not shading, because vision models can't judge absolute
brightness.

Applied at serve time after the view is sized to the requested `max_px`, so labels
stay crisp; the map is shrunk so the framed image still fits `max_px`.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from PIL import Image, ImageDraw, ImageFont

AXES = ("x", "y", "z")
GRID_STEPS = (1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024)
MIN_GRID_PX = 40
MIN_SPOT_PX = 90  # spot heights sit at the centre of every Nth grid cell, at least this far apart
MARGIN_LEFT = 46
MARGIN_TOP = 56
MARGIN_RIGHT = 14
MARGIN_BOTTOM = 30

FRAME_BG = (247, 247, 245)
INK = (32, 32, 32)
GRID = (30, 60, 150, 110)
LABEL_BOX = (255, 255, 255, 225)


@dataclass(frozen=True)
class Axis:
    name: str        # "x", "y" or "z"
    ascending: bool  # coordinates grow rightward (horizontal) or downward (vertical)


# Image axes of each view, matching render._orient and render._section_volume.
# Horizontal: x grows east, so it ascends when east is on the right. Vertical: y is up, z grows south.
VIEW_AXES = {
    "top": (Axis("x", True), Axis("z", True)),
    "north": (Axis("x", False), Axis("y", False)),
    "south": (Axis("x", True), Axis("y", False)),
    "east": (Axis("z", False), Axis("y", False)),
    "west": (Axis("z", True), Axis("y", False)),
    "section_x": (Axis("z", False), Axis("y", False)),
    "section_y": (Axis("x", True), Axis("z", True)),
    "section_z": (Axis("x", True), Axis("y", False)),
}

TITLES = {
    "top": "TOP (north up)",
    "north": "NORTH side (looking south)",
    "south": "SOUTH side (looking north)",
    "east": "EAST side (looking west)",
    "west": "WEST side (looking east)",
}
# What lies at the left and right edges of each elevation.
SIDE_EDGES = {"north": ("E", "W"), "south": ("W", "E"), "east": ("S", "N"), "west": ("N", "S"),
              "section_x": ("S", "N"), "section_z": ("W", "E")}


def font(size: int):
    try:
        return ImageFont.load_default(size=size)
    except TypeError:
        return ImageFont.load_default()


def grid_step(px_per_block: float) -> int:
    """Smallest step from GRID_STEPS whose lines are at least MIN_GRID_PX apart."""
    for step in GRID_STEPS:
        if step * px_per_block >= MIN_GRID_PX:
            return step
    return GRID_STEPS[-1]


def spot_every(step: int, px_per_block: float) -> int:
    """How many grid cells apart spot heights go, so they stay at least MIN_SPOT_PX apart."""
    every = 1
    while step * every * px_per_block < MIN_SPOT_PX:
        every *= 2
    return every


def ticks(low: int, extent: int, step: int) -> list[int]:
    """Block-boundary coordinates in [low, low + extent] that are multiples of step (negatives included)."""
    first = -(-low // step) * step
    return list(range(first, low + extent + 1, step))


def boundary_px(coordinate: int, low: int, extent: int, ascending: bool, ppb: float) -> float:
    """Pixel offset of the boundary at `coordinate` (the low edge of block `coordinate`)."""
    return (coordinate - low) * ppb if ascending else (low + extent - coordinate) * ppb


def block_px(coordinate: int, low: int, extent: int, ascending: bool, ppb: float) -> float:
    """Pixel offset of the centre of block `coordinate`."""
    return boundary_px(coordinate, low, extent, ascending, ppb) + (0.5 if ascending else -0.5) * ppb


def fit_size(max_px: int) -> tuple[int, int]:
    """Room left for the map inside a framed image no larger than max_px on either side."""
    return max(16, max_px - MARGIN_LEFT - MARGIN_RIGHT), max(16, max_px - MARGIN_TOP - MARGIN_BOTTOM)


def frame(img: Image.Image, view: str, size: tuple[int, int, int], origin: tuple[int, int, int] | None = None,
          heights: np.ndarray | None = None, title: str | None = None) -> Image.Image:
    """Draw the map frame around an RGB view whose pixels exactly cover the artifact's extent.

    `view` is a key of VIEW_AXES; `size` is (x, y, z) in blocks; `origin` the world position of
    local (0, 0, 0), or None for local coordinates; `heights` the [z, x] top-block local Y per
    column (-1 empty), used for spot heights on the top view.
    """
    h_axis, v_axis = VIEW_AXES[view]
    lows = tuple(origin) if origin else (0, 0, 0)
    h_index, v_index = AXES.index(h_axis.name), AXES.index(v_axis.name)
    h_low, h_extent = lows[h_index], size[h_index]
    v_low, v_extent = lows[v_index], size[v_index]
    ppb_h, ppb_v = img.width / h_extent, img.height / v_extent
    step = grid_step(min(ppb_h, ppb_v))

    out = Image.new("RGB", (img.width + MARGIN_LEFT + MARGIN_RIGHT, img.height + MARGIN_TOP + MARGIN_BOTTOM), FRAME_BG)
    out.paste(img, (MARGIN_LEFT, MARGIN_TOP))
    overlay = Image.new("RGBA", out.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    small, medium = font(11), font(13)
    left, top, right, bottom = MARGIN_LEFT, MARGIN_TOP, MARGIN_LEFT + img.width, MARGIN_TOP + img.height

    # Grid lines and tick labels. Vertical lines are labelled above the map, horizontal ones to its left.
    for value in ticks(h_low, h_extent, step):
        x = left + boundary_px(value, h_low, h_extent, h_axis.ascending, ppb_h)
        draw.line([(x, top), (x, bottom)], fill=GRID, width=1)
        draw.line([(x, top - 4), (x, top)], fill=INK + (255,), width=1)
        _centred(draw, str(value), x, top - 16, small)
    for value in ticks(v_low, v_extent, step):
        y = top + boundary_px(value, v_low, v_extent, v_axis.ascending, ppb_v)
        draw.line([(left, y), (right, y)], fill=GRID, width=1)
        draw.line([(left - 4, y), (left, y)], fill=INK + (255,), width=1)
        width = draw.textlength(str(value), font=small)
        draw.text((left - 6 - width, y - 7), str(value), fill=INK, font=small)
    # Which way each axis grows, in the corners next to its tick labels (the bundled font is ASCII-only).
    draw.text((4, top - 16), f"{h_axis.name} {'->' if h_axis.ascending else '<-'}", fill=INK, font=small)
    draw.text((4, bottom + 9), f"{v_axis.name} {'v' if v_axis.ascending else '^'}", fill=INK, font=small)

    if view == "top" and heights is not None:
        _spot_heights(draw, heights, lows, size, step, ppb_h, ppb_v, left, top, small)

    # Title, orientation and scale bar.
    coords = "world" if origin else "local"
    label = title or TITLES.get(view, view)
    draw.text((6, 4), f"{label}   {size[0]}x{size[1]}x{size[2]} ({coords} coords)", fill=INK, font=medium)
    if view in ("top", "section_y"):
        note = "north up, east right"
        if heights is not None and view == "top":
            note += "   spot = top block y"
    else:
        l_edge, r_edge = SIDE_EDGES[view]
        note = f"left {l_edge} | right {r_edge}   y up"
    draw.text((6, 21), note, fill=INK, font=small)
    bar = step * ppb_h
    y = bottom + 16
    draw.line([(left, y), (left + bar, y)], fill=INK + (255,), width=2)
    for x in (left, left + bar):
        draw.line([(x, y - 4), (x, y + 4)], fill=INK + (255,), width=1)
    draw.text((left + bar + 6, y - 7), f"{step} block{'s' if step != 1 else ''}", fill=INK, font=small)

    out = out.convert("RGBA")
    out.alpha_composite(overlay)
    return out.convert("RGB")


def _centred(draw: ImageDraw.ImageDraw, text: str, x: float, y: float, fnt) -> None:
    width = draw.textlength(text, font=fnt)
    draw.text((x - width / 2, y), text, fill=INK, font=fnt)


def _spot_heights(draw, heights: np.ndarray, lows, size, step, ppb_h, ppb_v, left, top, fnt) -> None:
    """Top-block y at each grid cell's centre column, boxed, where the cell fits the label."""
    x_low, y_low, z_low = lows
    step *= spot_every(step, min(ppb_h, ppb_v))
    cell_px = step * min(ppb_h, ppb_v)
    for gx in ticks(x_low, size[0], step):
        for gz in ticks(z_low, size[2], step):
            bx, bz = gx + step // 2, gz + step // 2
            if not (x_low <= bx < x_low + size[0] and z_low <= bz < z_low + size[2]):
                continue
            local = heights[bz - z_low, bx - x_low]
            if local < 0:
                continue
            text = str(int(local) + y_low)
            width = draw.textlength(text, font=fnt)
            if width + 8 > cell_px or cell_px < 22:
                continue
            cx = left + block_px(bx, x_low, size[0], True, ppb_h)
            cy = top + block_px(bz, z_low, size[2], True, ppb_v)
            draw.rectangle([cx - width / 2 - 2, cy - 7, cx + width / 2 + 2, cy + 7], fill=LABEL_BOX,
                           outline=(60, 60, 60, 200))
            draw.text((cx - width / 2, cy - 7), text, fill=INK, font=fnt)
