"""Preview images for built artifacts, rendered with the vendored nbt-image-gen.

Artifacts are immutable, so each artifact's views are rendered once, on first
request, into a `<artifact_id>.previews/` directory beside its NBT and evicted
with it. Cutaways, sections and floor plans depend on request parameters, so they
are rendered on demand into the same directory (and evicted with it). Rendering
runs in a subprocess under a wall-clock timeout so a huge structure cannot stall
the event loop. Downscaling and the contact sheet mirror
schematic-service/images.py so both preview tools look alike.
"""

from __future__ import annotations

import asyncio
import io
import json
import shutil
import sys
import uuid
from contextlib import asynccontextmanager
from functools import lru_cache
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from . import cache

VIEWS = ("iso", "top", "north", "south", "east", "west")
SHEET = "sheet"
SECTION = "section"
FLOORS = "floors"
CUT_VIEWS = (SHEET, "iso", SECTION)
AXES = ("x", "y", "z")
# Bumped when base renders change; older preview directories are re-rendered on next request.
PREVIEW_VERSION = 2
MIN_PX, MAX_PX = 64, 1024
DEFAULT_VIEW_PX = 768
DEFAULT_TILE_PX = 256
# A cut sheet has two tiles (cutaway iso and section), so each can be larger for the same cost.
DEFAULT_CUT_TILE_PX = 384

# Same orientation as the schematic previews: top has north up/east right; side views are
# elevations seen from that side; iso is viewed from the south-east.
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
# Renders are flattened onto slate, not white, so white wool/quartz/snow stay visible. Each
# artifact may instead get a contrasting background chosen at render time (meta.json "background").
IMAGE_BG = (165, 178, 195)
LABEL_BG = (230, 230, 230)
BORDER = (90, 90, 90)


class PreviewFailed(Exception):
    pass


def preview_dir(cache_dir: Path, identifier: str) -> Path:
    return cache.nbt_path(cache_dir, identifier).with_suffix(".previews")


def clamp_px(value: int | None, default: int) -> int:
    return default if value is None else max(MIN_PX, min(MAX_PX, value))


class Renderer:
    """Renders each artifact's views at most once, with bounded concurrency."""

    def __init__(self, cache_dir: Path, timeout_s: float, max_concurrent: int):
        self._cache_dir = cache_dir
        self._timeout_s = timeout_s
        self._slots = asyncio.Semaphore(max_concurrent)
        self._locks: dict[str, asyncio.Lock] = {}

    async def ensure(self, identifier: str) -> Path:
        """Return the artifact's preview directory, rendering it if needed."""
        target = preview_dir(self._cache_dir, identifier)
        if _current(target):
            return target
        async with self._locked(identifier):
            if not _current(target):
                shutil.rmtree(target, ignore_errors=True)
                tmp = target.with_name(f".{uuid.uuid4().hex}.previews.tmp")
                try:
                    await self._run(cache.nbt_path(self._cache_dir, identifier), tmp)
                except PreviewFailed:
                    shutil.rmtree(tmp, ignore_errors=True)
                    raise
                try:
                    tmp.rename(target)
                except OSError:
                    # A concurrent render (another process) won the race; its output is identical.
                    shutil.rmtree(tmp, ignore_errors=True)
        return target

    async def ensure_variant(self, identifier: str, spec: str, filename: str) -> Path:
        """Render one on-demand variant (see preview_worker) into the preview directory."""
        directory = await self.ensure(identifier)
        target = directory / filename
        if target.exists():
            return target
        async with self._locked(f"{identifier}/{spec}"):
            if not target.exists():
                try:
                    await self._run(cache.nbt_path(self._cache_dir, identifier), directory, spec)
                finally:
                    for leftover in directory.glob(".*.tmp"):  # from a killed worker
                        leftover.unlink(missing_ok=True)
        return target

    @asynccontextmanager
    async def _locked(self, key: str):
        lock = self._locks.setdefault(key, asyncio.Lock())
        try:
            async with lock:
                async with self._slots:
                    yield
        finally:
            if not lock.locked():
                self._locks.pop(key, None)

    async def _run(self, *args: object) -> None:
        process = await asyncio.create_subprocess_exec(
            sys.executable, "-m", "starlark_service.preview_worker", *map(str, args),
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE,
        )
        try:
            _, stderr = await asyncio.wait_for(process.communicate(), timeout=self._timeout_s)
        except TimeoutError:
            process.kill()
            await process.wait()
            raise PreviewFailed(f"preview rendering exceeded {self._timeout_s:g}s") from None
        if process.returncode != 0:
            lines = stderr.decode("utf-8", errors="replace").strip().splitlines()
            raise PreviewFailed(lines[-1] if lines else "preview worker exited abnormally")


def read_meta(directory: Path) -> dict:
    return json.loads((directory / "meta.json").read_text(encoding="utf-8"))


def _current(directory: Path) -> bool:
    try:
        return read_meta(directory).get("preview_version") == PREVIEW_VERSION
    except (OSError, ValueError):
        return False


def background(directory: Path) -> tuple[int, int, int]:
    rgb = read_meta(directory).get("background", {}).get("rgb")
    return tuple(rgb) if rgb else IMAGE_BG


def artifact_size(directory: Path) -> tuple[int, int, int]:
    size = read_meta(directory)["size"]
    return size["width"], size["height"], size["depth"]


def read_floors(directory: Path) -> list[dict]:
    return json.loads((directory / "floors.json").read_text(encoding="utf-8"))


def cut_label(view: str, axis: str, at: int) -> str:
    if view == "iso":
        return f"CUTAWAY {axis}<={at} (from SE)"
    return {"y": f"SECTION y={at} (plan, N up)", "z": f"SECTION z={at} (from S)",
            "x": f"SECTION x={at} (from E)"}[axis]


def _open(path: Path, bg: tuple[int, int, int]) -> Image.Image:
    with Image.open(path) as img:
        img.load()
        rgba = img.convert("RGBA")
    flat = Image.new("RGB", rgba.size, bg)
    flat.paste(rgba, (0, 0), rgba)
    return flat


def _png(img: Image.Image) -> bytes:
    buffer = io.BytesIO()
    img.save(buffer, format="PNG", optimize=True)
    return buffer.getvalue()


@lru_cache(maxsize=256)
def render_view(path: Path, max_px: int, bg: tuple[int, int, int] = IMAGE_BG) -> bytes:
    """Fit into max_px on the background: downscale smoothly, upscale small renders crisply (artifacts are immutable)."""
    img = _open(path, bg)
    scale = min(max_px / img.width, max_px / img.height)
    if scale < 1.0:
        img = img.resize((max(1, round(img.width * scale)), max(1, round(img.height * scale))), Image.LANCZOS)
    elif scale >= 2.0:
        factor = int(scale)
        img = img.resize((img.width * factor, img.height * factor), Image.NEAREST)
    return _png(img)


def _font():
    try:
        return ImageFont.load_default(size=14)
    except TypeError:
        return ImageFont.load_default()


@lru_cache(maxsize=128)
def render_tiles(tiles: tuple[tuple[str, Path], ...], tile_px: int, bg: tuple[int, int, int],
                 cols: int = 3) -> bytes:
    """Labelled grid of images, each fit into a tile_px square."""
    cols = max(1, min(cols, len(tiles)))
    rows = -(-len(tiles) // cols)
    width = cols * tile_px + (cols + 1) * PAD
    height = rows * (LABEL_H + tile_px) + (rows + 1) * PAD
    canvas = Image.new("RGB", (width, height), BG)
    draw = ImageDraw.Draw(canvas)
    font = _font()
    for index, (label, path) in enumerate(tiles):
        row, col = divmod(index, cols)
        x0 = PAD + col * (tile_px + PAD)
        y0 = PAD + row * (LABEL_H + tile_px + PAD)
        draw.rectangle([x0, y0, x0 + tile_px - 1, y0 + LABEL_H - 1], fill=LABEL_BG)
        draw.text((x0 + 4, y0 + 3), label, fill=(20, 20, 20), font=font)
        top = y0 + LABEL_H
        draw.rectangle([x0, top, x0 + tile_px - 1, top + tile_px - 1], fill=bg, outline=BORDER)
        img = _open(path, bg)
        scale = min((tile_px - 4) / img.width, (tile_px - 4) / img.height)
        size = (max(1, round(img.width * scale)), max(1, round(img.height * scale)))
        # Upscale small orthographic views crisply; downscale smoothly.
        img = img.resize(size, Image.NEAREST if scale >= 1 else Image.LANCZOS)
        canvas.paste(img, (x0 + (tile_px - size[0]) // 2, top + (tile_px - size[1]) // 2))
    return _png(canvas)


def render_sheet(directory: Path, tile_px: int) -> bytes:
    """Labelled 3x2 grid of all base views."""
    tiles = tuple((SHEET_LABELS[view], directory / f"{view}.png") for view in VIEWS)
    return render_tiles(tiles, tile_px, background(directory))
