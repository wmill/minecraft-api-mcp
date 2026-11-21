"""Preview rasterizers on top of nbt-image-gen's voxel loader and block palette.

Adds what nbt-image-gen's flat renders lack for checking builds: material-edge
outlines on the iso view (so pale glass separates from any background), cutaways
that remove the part of the structure facing the camera, orthographic sections
with depth fading, per-storey floor detection, and a background colour chosen
per artifact to contrast with its visible blocks.

Stairs and slabs are drawn with their real shape: nbt-image-gen's loader keeps
only block names, so block states are read separately (`load_block_states`) and
any build containing them is rendered on a grid refined to half-block resolution
(`refine`), where each keeps only the octants Minecraft's collision shape fills.
That makes a stair's `facing` (the side of the tall back step), `half` and corner
`shape`, and a slab's `type` (bottom/top), visible in every view.

Grids are indexed [x, y, z] in structure-local coordinates, AIR where empty.
"""

from __future__ import annotations

from pathlib import Path

import nbtlib
import numpy as np
from PIL import Image, ImageDraw

from nbt_image_gen.loader import AIR, Structure, _unwrap
from nbt_image_gen.palette import color_for
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
# Ortho views and sections darken the half of a stair or slab cell that lies further from the viewer.
RECESS_SHADE = 0.7
TOP_RECESS_SHADE = 0.85  # gentler: top views also carry hillshade
# Ortho shading: top views get half-strength NW hillshade and a +-12% lighter-is-higher ramp;
# side views darken up to 40% over the first 6 blocks behind the nearest face.
HILLSHADE_STRENGTH = 0.5
HEIGHT_TINT = 0.12
DEPTH_SHADE_MAX = 0.4
DEPTH_SHADE_BLOCKS = 6
# Ortho outlines (brightness factors): material change, small step, step of STEP_STRONG_BLOCKS+ or silhouette.
EDGE_MATERIAL = 0.88
EDGE_FAINT = 0.8
EDGE_STRONG = 0.4
STEP_STRONG_BLOCKS = 2
EDGE_MIN_BLOCK_PX = 3

# Horizontal directions as (dx, dz), and Minecraft's rotateYCounterclockwise / rotateYClockwise.
HORIZONTAL = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}
CCW = {"north": "west", "west": "south", "south": "east", "east": "north"}
CW = {after: before for before, after in CCW.items()}

Shapes = dict[int, np.ndarray]


# Block ids nbt-image-gen's palette doesn't know yet (renamed in recent versions) -> an id it does.
PALETTE_ALIASES = {"minecraft:short_grass": "minecraft:grass"}


def palette_rgb(structure: Structure) -> np.ndarray:
    rgb = _build_palette_rgb(structure)
    for index, name in enumerate(structure.palette):
        if name in PALETTE_ALIASES:
            rgb[index] = color_for(PALETTE_ALIASES[name])
    return rgb


def load_block_states(path: Path | str) -> list[dict[str, str]]:
    """Block-state properties for each palette entry of a vanilla structure .nbt."""
    root = _unwrap(nbtlib.load(str(path)), "size")
    return [{str(k): str(v) for k, v in entry.get("Properties", {}).items()} for entry in root["palette"]]


def _quarters(direction: str) -> set[tuple[int, int]]:
    """The (x, z) quarters of a block on the given horizontal side."""
    dx, dz = HORIZONTAL[direction]
    return {(qx, qz) for qx in (0, 1) for qz in (0, 1)
            if (dx == 0 or qx == (dx > 0)) and (dz == 0 or qz == (dz > 0))}


def stair_octants(props: dict[str, str]) -> np.ndarray | None:
    """[x, y, z] 2x2x2 occupancy of a stair, matching StairsBlock's outline shape (1.21).

    A full half-slab (`half`), plus a step on the `facing` side: straight is that half,
    outer_left/right only its corner towards facing's counter-clockwise/clockwise
    neighbour, inner_left/right that half joined with the neighbour's half.
    """
    facing = props.get("facing", "north")
    if facing not in HORIZONTAL:
        return None
    shape = props.get("shape", "straight")
    step = _quarters(facing)
    if shape == "outer_left":
        step &= _quarters(CCW[facing])
    elif shape == "outer_right":
        step &= _quarters(CW[facing])
    elif shape == "inner_left":
        step |= _quarters(CCW[facing])
    elif shape == "inner_right":
        step |= _quarters(CW[facing])
    slab, raised = (1, 0) if props.get("half") == "top" else (0, 1)
    mask = np.zeros((2, 2, 2), dtype=bool)
    mask[:, slab, :] = True
    for qx, qz in step:
        mask[qx, raised, qz] = True
    return mask


def slab_octants(props: dict[str, str]) -> np.ndarray | None:
    """[x, y, z] 2x2x2 occupancy of a slab; None for `double`, which is a full block."""
    kind = props.get("type", "bottom")
    if kind not in ("bottom", "top"):
        return None
    mask = np.zeros((2, 2, 2), dtype=bool)
    mask[:, 1 if kind == "top" else 0, :] = True
    return mask


def block_shapes(structure: Structure, states: list[dict[str, str]]) -> Shapes:
    """Palette index -> octant mask for every palette entry drawn at sub-block detail."""
    shapes: Shapes = {}
    for index, (name, props) in enumerate(zip(structure.palette, states)):
        if name.endswith("_stairs"):
            mask = stair_octants(props)
        elif name.endswith("_slab"):
            mask = slab_octants(props)
        else:
            continue
        if mask is not None:
            shapes[index] = mask
    return shapes


def _shapes_in(grid: np.ndarray, shapes: Shapes | None) -> Shapes:
    if not shapes:
        return {}
    present = np.unique(grid)
    return {i: m for i, m in shapes.items() if i in present}


def refine(grid: np.ndarray, shapes: Shapes) -> np.ndarray:
    """Double the resolution: full blocks fill 2x2x2 octants, shaped blocks only their mask."""
    fine = grid.repeat(2, axis=0).repeat(2, axis=1).repeat(2, axis=2)
    for index, mask in shapes.items():
        fine[np.kron(grid == index, ~mask)] = AIR
    return fine


# Ortho view -> (scan axis, viewer on the high side), as nbt-image-gen's render_top/render_side.
_ORTHO_SCAN = {"top": (1, True), "north": (2, False), "south": (2, True), "east": (0, True), "west": (0, False)}


def _orient(image: np.ndarray, view: str) -> np.ndarray:
    """Turn a [a, b, ...] first-hit image into rows/cols with nbt-image-gen's view orientation."""
    t = image.transpose(1, 0, *range(2, image.ndim))
    if view == "top":
        return t                  # rows z (north first), cols x (west first)
    if view == "north":
        return t[::-1, ::-1]      # rows y (top first), cols x (east first)
    if view == "south":
        return t[::-1, :]         # rows y, cols x (west first)
    if view == "east":
        return image[::-1, ::-1]  # [y, z] -> rows y, cols z (north first)
    if view == "west":
        return image[::-1, :]     # rows y, cols z (south first)
    raise ValueError(f"unknown direction: {view}")


def hillshade(heights: np.ndarray, spacing: float = 1.0) -> np.ndarray:
    """Brightness factor per cell for a light from the north-west, 45 degrees up.

    `heights` is [x, z] in blocks, `spacing` the cell size in blocks. 1.0 on flat ground,
    above 1 on slopes facing the light, below 1 facing away (same formula and clamp as the
    mod's TerrainMapRenderer.hillshade, so both maps light terrain alike).
    """
    h = heights.astype(np.float64)
    dx = np.gradient(h, spacing, axis=0) if h.shape[0] > 1 else np.zeros_like(h)
    dz = np.gradient(h, spacing, axis=1) if h.shape[1] > 1 else np.zeros_like(h)
    lx, ly, lz = -0.5, np.sqrt(0.5), -0.5
    dot = (-dx * lx + ly - dz * lz) / np.sqrt(dx * dx + 1 + dz * dz)
    return np.clip(np.maximum(dot, 0) / ly, 0.45, 1.35)


def _edge_strength(key: np.ndarray, hit: np.ndarray, material: np.ndarray,
                   n_key: np.ndarray, n_hit: np.ndarray, n_material: np.ndarray,
                   faint_step: int, strong_step: int) -> np.ndarray:
    """Brightness factor for each cell's side facing one neighbour (1 = no line)."""
    step = np.abs(key - n_key)
    out = np.where(material != n_material, EDGE_MATERIAL, 1.0)
    out = np.where(step >= faint_step, EDGE_FAINT, out)
    out = np.where(step >= strong_step, EDGE_STRONG, out)
    out = np.where(~n_hit, EDGE_STRONG, out)  # silhouette against empty space or the image border
    return np.where(hit, out, 1.0)


def edge_shade(key: np.ndarray, hit: np.ndarray, material: np.ndarray, cell: int, width: int,
               faint_step: int, strong_step: int) -> np.ndarray:
    """Per-pixel brightness factor outlining cell boundaries in a [rows, cols] view upscaled by `cell`.

    `key` is the height (top view) or depth (side views) of each cell's visible surface.
    A boundary is drawn faintly where the material changes or the surface steps by at
    least `faint_step` (so a stair's own half-block step is left to its shading), and
    darkly at steps of `strong_step` or more and at silhouettes, on both sides of the
    boundary, `width` pixels each.
    """
    rows, cols = key.shape
    key = key.astype(np.int64)
    pk = np.pad(key, 1, mode="edge")
    ph = np.pad(hit, 1, constant_values=False)
    pm = np.pad(material.astype(np.int64), 1, constant_values=-1)
    sides = {
        "top": (slice(0, rows), slice(1, cols + 1)),
        "bottom": (slice(2, rows + 2), slice(1, cols + 1)),
        "left": (slice(1, rows + 1), slice(0, cols)),
        "right": (slice(1, rows + 1), slice(2, cols + 2)),
    }
    pos = np.arange(cell)
    strips = {"top": (pos < width)[:, None], "bottom": (pos >= cell - width)[:, None],
              "left": (pos < width)[None, :], "right": (pos >= cell - width)[None, :]}
    out = np.ones((rows * cell, cols * cell), dtype=np.float32)
    for side, (r, c) in sides.items():
        factor = _edge_strength(key, hit, material, pk[r, c], ph[r, c], pm[r, c], faint_step, strong_step)
        big = np.repeat(np.repeat(factor.astype(np.float32), cell, axis=0), cell, axis=1)
        mask = np.tile(np.broadcast_to(strips[side], (cell, cell)), (rows, cols))
        np.minimum(out, np.where(mask, big, 1.0), out=out)
    return out


def top_heights(grid: np.ndarray) -> np.ndarray:
    """[z, x] local Y of each column's topmost block, -1 where the column is empty."""
    solid = grid != AIR
    any_solid = solid.any(axis=1)
    top = grid.shape[1] - 1 - solid[:, ::-1, :].argmax(axis=1)
    return np.where(any_solid, top, -1).T.astype(np.int16)


def render_ortho(structure: Structure, view: str, scale: int = 8, shapes: Shapes | None = None) -> Image.Image:
    """Top or side view, shaded so shape reads without a perspective.

    Top: lighter = higher on a gentle ramp, NW hillshade from the per-column surface height,
    and outlines along height steps (dark at 2+ blocks: eaves, walls, ridges) and material
    changes. Sides: darker = further back, with outlines along depth steps, so recesses,
    porches and overhangs show.

    With stairs or slabs, the view is taken at half-block resolution: profiles show
    against whatever lies behind, and the half of a shaped cell further from the viewer
    (a stair's tread or a bottom slab seen from above, a recessed riser seen from the
    front) is darkened.
    """
    present = _shapes_in(structure.grid, shapes)
    k = 2 if present else 1
    fine = refine(structure.grid, present) if present else structure.grid
    axis, high_side = _ORTHO_SCAN[view]
    scan = np.flip(fine, axis=axis) if high_side else fine
    solid = scan != AIR
    first = solid.argmax(axis=axis)
    hit = solid.any(axis=axis)
    values = np.take_along_axis(scan, np.expand_dims(first, axis), axis=axis).squeeze(axis)
    # Index of the hit along the scan from the low side; with refinement the near half of a cell is odd from the high side.
    index = fine.shape[axis] - 1 - first if high_side else first
    if present:
        far = (index % 2 == 0) if high_side else (index % 2 == 1)
        recessed = hit & far & np.isin(values, list(present))
    else:
        recessed = np.zeros_like(hit)

    rgb = palette_rgb(structure).astype(np.float32)[np.where(hit, values, 0)]
    shade = np.where(recessed, TOP_RECESS_SHADE if view == "top" else RECESS_SHADE, 1.0)
    if view == "top":
        key = index  # surface height in cells
        if hit.any():
            # Light the block-level surface: a stair's own half step is shown by its recess shade instead.
            heights = (np.where(hit, index, index[hit].min()) // k).astype(np.float64)
            if k > 1:
                coarse = heights.reshape(heights.shape[0] // k, k, heights.shape[1] // k, k).max(axis=(1, 3))
                lit = hillshade(coarse).repeat(k, axis=0).repeat(k, axis=1)
            else:
                lit = hillshade(heights)
            shade = shade * (1 + HILLSHADE_STRENGTH * (lit - 1))
            low, high = float(heights[hit].min()), float(heights[hit].max())
            if high > low:
                shade = shade * (1 + HEIGHT_TINT * (2 * (heights - low) / (high - low) - 1))
    else:
        key = first  # depth from the viewer in cells
        if hit.any():
            depth = (first - first[hit].min()) / k
            shade = shade * (1 - DEPTH_SHADE_MAX * np.clip(depth / DEPTH_SHADE_BLOCKS, 0, 1))
    # Lit cells move toward white rather than scaling up, so dark materials (dark oak, deepslate)
    # still brighten visibly and pale ones don't clip; shaded cells darken proportionally.
    f = shade[..., None]
    rgb = np.clip(np.where(f > 1, rgb + (255 - rgb) * (f - 1), rgb * f), 0, 255)

    rgba = np.zeros(values.shape + (4,), dtype=np.float32)
    rgba[..., :3] = rgb
    rgba[..., 3] = np.where(hit, 255, 0)
    rgba = _orient(rgba, view)
    cell = max(1, scale // k)
    if cell > 1:
        rgba = rgba.repeat(cell, axis=0).repeat(cell, axis=1)
    if cell * k >= EDGE_MIN_BLOCK_PX:
        material = np.where(hit, values.astype(np.int64), -1)
        lines = edge_shade(_orient(key, view), _orient(hit, view), _orient(material, view), cell,
                           max(1, cell * k // 8), k, STEP_STRONG_BLOCKS * k)
        rgba[..., :3] *= lines[..., None]
    return Image.fromarray(np.ascontiguousarray(rgba.astype(np.uint8)), "RGBA")


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


def render_iso(structure: Structure, grid: np.ndarray, scale: int = 6, crop: bool = True,
               shapes: Shapes | None = None) -> Image.Image:
    """2:1 isometric from the south-east with material-edge outlines, on a transparent background.

    Same projection as nbt-image-gen: +X right-and-down, +Z left-and-down, +Y up.
    A face edge is outlined unless the neighbouring block across that edge is the same
    material and shows the same face, so flat runs of one block read as one shape while
    silhouettes, material changes and depth steps get a darker line.

    With `shapes` and any of those blocks present, the grid is drawn at half-block
    resolution so stairs show their step and slabs their height; the image size is unchanged.
    """
    rgb = palette_rgb(structure)
    present = _shapes_in(grid, shapes)
    unit: float = scale
    if present:
        grid = refine(grid, present)
        unit = scale / 2
    W, H, D = grid.shape
    solid = grid != AIR
    air = ~solid
    visible = {}
    for name, axis, step, _ in _FACES:
        visible[name] = solid & _shift(air, axis, step, True)
    drawn = visible["top"] | visible["south"] | visible["east"]

    img = Image.new("RGBA", (max(1, round(2 * (W + D) * unit)), max(1, round((W + D + 2 * H) * unit))), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    if not drawn.any():
        return img

    def project(px: int, py: int, pz: int) -> tuple[float, float]:
        return (((px - pz) * 2 + 2 * D) * unit, ((px + pz) - 2 * py + 2 * H) * unit)

    # Edge visibility per face and in-plane direction: (face, axis, step) -> mask of "draw this edge".
    edges = {}
    for name, axis, step, plane in _FACES:
        for edge_axis in plane:
            for edge_step in (-1, 1):
                same = (_neighbour(grid, edge_axis, edge_step) == grid) & _shift(visible[name], edge_axis, edge_step, False)
                edges[(name, edge_axis, edge_step)] = visible[name] & ~same

    xs, ys, zs = np.nonzero(drawn)
    # The camera is on the +X/+Y/+Z side. Paint from low to high on
    # every axis so nearer cubes cover farther ones, including within a layer.
    order = np.lexsort((xs, zs, ys))
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
                   scale: int = 8, shapes: Shapes | None = None) -> Image.Image:
    """Orthographic section looking at the cut face of the kept half.

    Blocks on the cut plane are full colour with outlines along material boundaries;
    blocks seen further back fade toward the background with depth. Stairs and slabs are
    cut at half-block resolution, their far half darkened (in a plan: the lower tread, or
    a bottom slab).
    """
    rgb = palette_rgb(structure).astype(np.float32)
    grid = structure.grid
    present = _shapes_in(grid, shapes)
    k = 2 if present else 1
    if present:
        grid = refine(grid, present)
        at = 2 * at + 1
    volume = _section_volume(grid, axis, at)
    rows, cols, _ = volume.shape
    solid = volume != AIR
    hit = solid.any(axis=2)
    depth = solid.argmax(axis=2)
    values = np.take_along_axis(volume, depth[..., None], axis=2)[..., 0]
    # Depth 0 is the near half of the cut cell, so odd depths are far halves.
    recessed = hit & (depth % 2 == 1) & np.isin(values, list(present)) if present else np.zeros_like(hit)
    colour = rgb[np.where(hit, values, 0)]
    colour[recessed] *= RECESS_SHADE
    fade = np.clip((depth // k) / SECTION_FADE_LAYERS, 0, 1)[..., None] * SECTION_FADE_MAX
    bg = np.array(background, dtype=np.float32)
    colour = colour * (1 - fade) + bg * fade
    colour[~hit] = bg
    cell = scale / k
    img = Image.fromarray(colour.astype(np.uint8), "RGB")
    img = img.resize((max(1, round(cols * cell)), max(1, round(rows * cell))), Image.NEAREST)

    draw = ImageDraw.Draw(img)
    cut = hit & (depth // k == 0)
    # Recessed halves count as their own material so a stair's step gets outlined.
    material = np.where(cut, values.astype(np.int32) * 2 + recessed, -1)
    width = max(1, scale // 6)
    for r, c in zip(*np.nonzero(cut)):
        x0, y0 = round(c * cell), round(r * cell)
        x1, y1 = round((c + 1) * cell) - 1, round((r + 1) * cell) - 1
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
