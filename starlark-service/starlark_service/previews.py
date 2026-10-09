"""Preview images for built artifacts, rendered with the vendored nbt-image-gen.

Artifacts are immutable, so each artifact's views are rendered once, on first
request, into a `<artifact_id>.previews/` directory beside its NBT and evicted
with it. Rendering runs in a subprocess under a wall-clock timeout so a huge
structure cannot stall the event loop. Downscaling and the contact sheet mirror
schematic-service/images.py so both preview tools look alike.
"""

from __future__ import annotations

import asyncio
import io
import shutil
import sys
import uuid
from functools import lru_cache
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from . import cache

VIEWS = ("iso", "top", "north", "south", "east", "west")
SHEET = "sheet"
MIN_PX, MAX_PX = 64, 1024
DEFAULT_VIEW_PX = 768
DEFAULT_TILE_PX = 256

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
# Renders are flattened onto slate, not white, so white wool/quartz/snow stay visible.
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
        if target.is_dir():
            return target
        lock = self._locks.setdefault(identifier, asyncio.Lock())
        try:
            async with lock:
                if not target.is_dir():
                    async with self._slots:
                        await self._render(identifier, target)
        finally:
            if not lock.locked():
                self._locks.pop(identifier, None)
        return target

    async def _render(self, identifier: str, target: Path) -> None:
        nbt = cache.nbt_path(self._cache_dir, identifier)
        tmp = target.with_name(f".{uuid.uuid4().hex}.previews.tmp")
        process = await asyncio.create_subprocess_exec(
            sys.executable, "-m", "starlark_service.preview_worker", str(nbt), str(tmp),
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE,
        )
        try:
            _, stderr = await asyncio.wait_for(process.communicate(), timeout=self._timeout_s)
        except TimeoutError:
            process.kill()
            await process.wait()
            shutil.rmtree(tmp, ignore_errors=True)
            raise PreviewFailed(f"preview rendering exceeded {self._timeout_s:g}s") from None
        if process.returncode != 0:
            shutil.rmtree(tmp, ignore_errors=True)
            lines = stderr.decode("utf-8", errors="replace").strip().splitlines()
            raise PreviewFailed(lines[-1] if lines else "preview worker exited abnormally")
        try:
            tmp.rename(target)
        except OSError:
            # A concurrent render (another process) won the race; its output is identical.
            shutil.rmtree(tmp, ignore_errors=True)


def _open(path: Path) -> Image.Image:
    with Image.open(path) as img:
        img.load()
        rgba = img.convert("RGBA")
    flat = Image.new("RGB", rgba.size, IMAGE_BG)
    flat.paste(rgba, (0, 0), rgba)
    return flat


def _png(img: Image.Image) -> bytes:
    buffer = io.BytesIO()
    img.save(buffer, format="PNG", optimize=True)
    return buffer.getvalue()


@lru_cache(maxsize=256)
def render_view(path: Path, max_px: int) -> bytes:
    """Fit into max_px on slate: downscale smoothly, upscale small renders crisply (artifacts are immutable)."""
    img = _open(path)
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
def render_sheet(directory: Path, tile_px: int) -> bytes:
    """Labelled 3x2 grid of all views, each fit into a tile_px square."""
    cols, rows = 3, 2
    width = cols * tile_px + (cols + 1) * PAD
    height = rows * (LABEL_H + tile_px) + (rows + 1) * PAD
    canvas = Image.new("RGB", (width, height), BG)
    draw = ImageDraw.Draw(canvas)
    font = _font()
    for index, view in enumerate(VIEWS):
        row, col = divmod(index, cols)
        x0 = PAD + col * (tile_px + PAD)
        y0 = PAD + row * (LABEL_H + tile_px + PAD)
        draw.rectangle([x0, y0, x0 + tile_px - 1, y0 + LABEL_H - 1], fill=LABEL_BG)
        draw.text((x0 + 4, y0 + 3), SHEET_LABELS[view], fill=(20, 20, 20), font=font)
        top = y0 + LABEL_H
        draw.rectangle([x0, top, x0 + tile_px - 1, top + tile_px - 1], fill=IMAGE_BG, outline=BORDER)
        img = _open(directory / f"{view}.png")
        scale = min((tile_px - 4) / img.width, (tile_px - 4) / img.height)
        size = (max(1, round(img.width * scale)), max(1, round(img.height * scale)))
        # Upscale small orthographic views crisply; downscale smoothly.
        img = img.resize(size, Image.NEAREST if scale >= 1 else Image.LANCZOS)
        canvas.paste(img, (x0 + (tile_px - size[0]) // 2, top + (tile_px - size[1]) // 2))
    return _png(canvas)
