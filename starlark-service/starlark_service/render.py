"""Preview rasterizers on top of nbt-image-gen's voxel loader and block palette.

Adds what nbt-image-gen's flat renders lack for checking builds: material-edge
outlines on the iso view (so pale glass separates from any background), cutaways
that remove the part of the structure facing the camera, orthographic sections
with depth fading, per-storey floor detection, and a background colour chosen
per artifact to contrast with its visible blocks.

Grids are indexed [x, y, z] in structure-local coordinates, AIR where empty.
"""

from __future__ import annotations

import numpy as np
from PIL import Image, ImageDraw

from nbt_image_gen.loader import AIR, Structure
from nbt_image_gen.renderer import _build_palette_rgb

AXES = ("x", "y", "z")

# Candidate backgrounds, most pleasant first. The extra penalty keeps garish magenta as a last resort.
BACKGROUNDS = (
    ("slate", (165, 178, 195), 0.0),
    ("charcoal", (40, 42, 48), 0.05),
    ("sand", (222, 206, 170), 0.08),
    ("magenta", (255, 0, 255), 0.30),
)
CONFUSABLE_DISTANCE = 60.0

FACE_SHADE = {"top": 1.0, "south": 0.85, "east": 0.65}
EDGE_SHADE = 0.6
SECTION_FADE_LAYERS = 6
SECTION_FADE_MAX = 0.75
MAX_FLOORS = 9


def palette_rgb(structure: Structure) -> np.ndarray:
    return _build_palette_rgb(structure)


def cutaway(grid: np.ndarray, axis: str, at: int) -> np.ndarray:
    """Remove everything past `at` on the side facing the iso camera (+x, +y or +z)."""
    out = grid.copy()
    index = [slice(None)] * 3
    index[AXES.index(axis)] = slice(at + 1, None)
    out[tuple(index)] = AIR
    return out


def _open(grid: np.ndarray) -> np.ndarray:
    return grid == AIR


def _shift(mask: np.ndarray, axis: int, step: int, fill: bool) -> np.ndarray:
    """out[p] = mask[p + step along axis], `fill` beyond the edge."""
    out = np.full_like(mask, fill)
    src = [slice(None)] * 3
    dst = [slice(None)] * 3
    if step > 0:
        src[axis], dst[axis] = slice(step, None), slice(None, -step)
    else:
        src[axis], dst[axis] = slice(None, step), slice(-step, None)
    out[tuple(dst)] = mask[tuple(src)]
    return out


def _neighbour(grid: np.ndarray, axis: int, step: int) -> np.ndarray:
    out = np.full_like(grid, AIR)
    src = [slice(None)] * 3
    dst = [slice(None)] * 3
    if step > 0:
        src[axis], dst[axis] = slice(step, None), slice(None, -step)
    else:
        src[axis], dst[axis] = slice(None, step), slice(-step, None)
    out[tuple(dst)] = grid[tuple(src)]
    return out


# Visible iso faces: (name, normal axis, normal step, the two in-plane axes).
_FACES = (("top", 1, 1, (0, 2)), ("south", 2, 1, (0, 1)), ("east", 0, 1, (1, 2)))


def render_iso(structure: Structure, grid: np.ndarray, scale: int = 6, crop: bool = True) -> Image.Image:
    """2:1 isometric from the south-east with material-edge outlines, on a transparent background.

    Same projection as nbt-image-gen: +X right-and-down, +Z left-and-down, +Y up.
    A face edge is outlined unless the neighbouring block across that edge is the same
    material and shows the same face, so flat runs of one block read as one shape while
    silhouettes, material changes and depth steps get a darker line.
    """
    rgb = palette_rgb(structure)
    W, H, D = grid.shape
    solid = grid != AIR
    air = ~solid
    visible = {}
    for name, axis, step, _ in _FACES:
        visible[name] = solid & _shift(air, axis, step, True)
    drawn = visible["top"] | visible["south"] | visible["east"]

    img = Image.new("RGBA", (max(1, 2 * (W + D) * scale), max(1, (W + D + 2 * H) * scale)), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    if not drawn.any():
        return img

    def project(px: int, py: int, pz: int) -> tuple[int, int]:
        return (((px - pz) * 2 + 2 * D) * scale, ((px + pz) - 2 * py + 2 * H) * scale)

    # Edge visibility per face and in-plane direction: (face, axis, step) -> mask of "draw this edge".
    edges = {}
    for name, axis, step, plane in _FACES:
        for edge_axis in plane:
            for edge_step in (-1, 1):
                same = (_neighbour(grid, edge_axis, edge_step) == grid) & _shift(visible[name], edge_axis, edge_step, False)
                edges[(name, edge_axis, edge_step)] = visible[name] & ~same

    xs, ys, zs = np.nonzero(drawn)
    order = np.lexsort((xs, -zs, ys))  # painter's order: y ascending, then z descending, then x ascending
    for i in order:
        x, y, z = int(xs[i]), int(ys[i]), int(zs[i])
        r, g, b = (int(v) for v in rgb[grid[x, y, z]])
        for name, axis, _, plane in _FACES:
            if not visible[name][x, y, z]:
                continue
            k = FACE_SHADE[name]
            if name == "top":
                corners = [(x, y + 1, z), (x + 1, y + 1, z), (x + 1, y + 1, z + 1), (x, y + 1, z + 1)]
            elif name == "south":
                corners = [(x, y, z + 1), (x + 1, y, z + 1), (x + 1, y + 1, z + 1), (x, y + 1, z + 1)]
            else:
                corners = [(x + 1, y, z), (x + 1, y, z + 1), (x + 1, y + 1, z + 1), (x + 1, y + 1, z)]
            points = [project(*c) for c in corners]
            draw.polygon(points, fill=(int(r * k), int(g * k), int(b * k), 255))
            line = (int(r * k * EDGE_SHADE), int(g * k * EDGE_SHADE), int(b * k * EDGE_SHADE), 255)
            # Each corner list walks the face boundary; pick out the edge lying on each side.
            for edge_axis in plane:
                for edge_step in (-1, 1):
                    if not edges[(name, edge_axis, edge_step)][x, y, z]:
                        continue
                    wanted = (x, y, z)[edge_axis] + (1 if edge_step > 0 else 0)
                    side = [project(*c) for c in corners if c[edge_axis] == wanted]
                    draw.line(side, fill=line, width=max(1, scale // 6))

    if crop:
        box = img.getbbox()
        if box:
            pad = 2 * scale
            img = img.crop((max(0, box[0] - pad), max(0, box[1] - pad),
                            min(img.width, box[2] + pad), min(img.height, box[3] + pad)))
    return img


def _section_volume(grid: np.ndarray, axis: str, at: int) -> np.ndarray:
    """Reorient the kept half as [row, col, depth] with depth 0 at the cut plane.

    y: plan looking down, north up, east right.
    z: elevation looking north from the south side, up is up, east right.
    x: elevation looking west from the east side, up is up, north right.
    """
    if axis == "y":
        kept = grid[:, : at + 1, :]                       # [x, y, z]
        return kept[:, ::-1, :].transpose(2, 0, 1)        # [z, x, depth]
    if axis == "z":
        kept = grid[:, :, : at + 1]
        return kept[:, ::-1, ::-1].transpose(1, 0, 2)     # [y (top first), x, depth]
    kept = grid[: at + 1, :, :]
    return kept[::-1, ::-1, ::-1].transpose(1, 2, 0)      # [y (top first), z (north right), depth]


def render_section(structure: Structure, axis: str, at: int, background: tuple[int, int, int],
                   scale: int = 8) -> Image.Image:
    """Orthographic section looking at the cut face of the kept half.

    Blocks on the cut plane are full colour with outlines along material boundaries;
    blocks seen further back fade toward the background with depth.
    """
    rgb = palette_rgb(structure).astype(np.float32)
    volume = _section_volume(structure.grid, axis, at)
    rows, cols, _ = volume.shape
    solid = volume != AIR
    hit = solid.any(axis=2)
    depth = solid.argmax(axis=2)
    values = np.take_along_axis(volume, depth[..., None], axis=2)[..., 0]
    colour = rgb[np.where(hit, values, 0)]
    fade = np.clip(depth / SECTION_FADE_LAYERS, 0, 1)[..., None] * SECTION_FADE_MAX
    bg = np.array(background, dtype=np.float32)
    colour = colour * (1 - fade) + bg * fade
    colour[~hit] = bg
    img = Image.fromarray(colour.astype(np.uint8), "RGB")
    img = img.resize((max(1, cols * scale), max(1, rows * scale)), Image.NEAREST)

    draw = ImageDraw.Draw(img)
    cut = hit & (depth == 0)
    material = np.where(cut, values, AIR - 1)
    width = max(1, scale // 6)
    for r, c in zip(*np.nonzero(cut)):
        x0, y0, x1, y1 = c * scale, r * scale, (c + 1) * scale - 1, (r + 1) * scale - 1
        here = material[r, c]
        if r == 0 or material[r - 1, c] != here:
            draw.line([(x0, y0), (x1, y0)], fill=(0, 0, 0), width=width)
        if r == rows - 1 or material[r + 1, c] != here:
            draw.line([(x0, y1), (x1, y1)], fill=(0, 0, 0), width=width)
        if c == 0 or material[r, c - 1] != here:
            draw.line([(x0, y0), (x0, y1)], fill=(0, 0, 0), width=width)
        if c == cols - 1 or material[r, c + 1] != here:
            draw.line([(x1, y0), (x1, y1)], fill=(0, 0, 0), width=width)
    return img


def detect_floors(grid: np.ndarray, limit: int = MAX_FLOORS) -> list[int]:
    """Y levels people stand on indoors: solid, two air blocks above, and a roof somewhere over that.

    Picks the levels with the most such cells (at least 3 apart), lowest first. A build with
    no enclosed space falls back to evenly spaced levels.
    """
    H = grid.shape[1]
    solid = grid != AIR
    if H < 3 or not solid.any():
        return []
    # roofed[:, y] = any solid block at y + 3 or higher in the column.
    suffix = np.logical_or.accumulate(solid[:, ::-1, :], axis=1)[:, ::-1, :]
    roofed = np.zeros_like(solid)
    roofed[:, : H - 3, :] = suffix[:, 3:, :]
    standable = np.zeros_like(solid)
    standable[:, : H - 2, :] = solid[:, : H - 2, :] & ~solid[:, 1 : H - 1, :] & ~solid[:, 2:, :]
    counts = (standable & roofed).sum(axis=(0, 2))
    best = int(counts.max())
    floors: list[int] = []
    if best >= 4:
        threshold = max(4, best * 0.15)
        for y in np.argsort(-counts, kind="stable"):
            y = int(y)
            if counts[y] < threshold or len(floors) >= limit:
                break
            if all(abs(y - other) >= 3 for other in floors):
                floors.append(y)
    if not floors:
        step = max(3, H // 4)
        floors = list(range(0, H - 1, step))[:limit]
    return sorted(floors)


def floor_cut(floor: int, height: int) -> int:
    """Cut two blocks above the floor: through doors, windows and furniture tops."""
    return min(height - 1, floor + 2)


def choose_background(images: list[Image.Image]) -> tuple[str, tuple[int, int, int]]:
    """Pick the candidate background least confusable with the renders' silhouettes.

    Only opaque pixels touching transparency meet the background, so those are what
    must contrast with it; interior faces never do.
    """
    edge_colours = []
    for img in images:
        rgba = np.asarray(img.convert("RGBA"))
        opaque = rgba[..., 3] > 0
        clear = ~opaque
        touching = np.zeros_like(opaque)
        touching[1:, :] |= clear[:-1, :]
        touching[:-1, :] |= clear[1:, :]
        touching[:, 1:] |= clear[:, :-1]
        touching[:, :-1] |= clear[:, 1:]
        touching[0, :] = touching[-1, :] = True
        touching[:, 0] = touching[:, -1] = True
        edge_colours.append(rgba[opaque & touching][:, :3])
    colours = np.concatenate(edge_colours).astype(np.float32) if edge_colours else np.zeros((0, 3), np.float32)
    best = None
    for name, colour, penalty in BACKGROUNDS:
        if len(colours):
            distance = np.linalg.norm(colours - np.array(colour, dtype=np.float32), axis=1)
            score = float((distance < CONFUSABLE_DISTANCE).mean()) + penalty
        else:
            score = penalty
        if best is None or score < best[0]:
            best = (score, name, colour)
    return best[1], best[2]
