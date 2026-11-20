"""World regions uploaded for rendering.

The Minecraft mod captures a world cuboid as gzipped structure NBT; MCP posts it to
`POST /renders`, which stores it in the artifact cache under a content-addressed
`rgn_` id so the existing `/artifacts/{id}/images/...` routes (views, cutaways,
sections, floors) render it like a compiled build. An unchanged world region at the
same origin yields the same id, so its previews are reused.
"""

from __future__ import annotations

import hashlib
import io
import re
import zlib

import nbtlib
from nbt_image_gen.loader import _unwrap

KIND = "region"
GZIP_MAGIC = b"\x1f\x8b"
MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024
WORLD_PATTERN = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
ORIGIN_PATTERN = re.compile(r"^-?\d{1,10},-?\d{1,10},-?\d{1,10}$")


class InvalidRegion(ValueError):
    """The upload is not a usable structure NBT."""


def region_id(data: bytes, origin: list[int] | None, world: str | None) -> str:
    digest = hashlib.sha256(data)
    digest.update(f"\0{origin}\0{world}".encode("utf-8"))
    return "rgn_" + digest.hexdigest()[:16]


def parse_origin(value: str | None) -> list[int] | None:
    if value is None or value == "":
        return None
    if not ORIGIN_PATTERN.fullmatch(value):
        raise InvalidRegion("X-Region-Origin must be 'x,y,z' integers")
    return [int(part) for part in value.split(",")]


def parse_world(value: str | None) -> str | None:
    if value is None or value == "":
        return None
    if len(value) > 128 or not WORLD_PATTERN.fullmatch(value):
        raise InvalidRegion("X-Region-World must be a namespaced id such as minecraft:overworld")
    return value


def _decompress(data: bytes) -> bytes:
    if not data.startswith(GZIP_MAGIC):
        raise InvalidRegion("the body must be gzipped structure NBT")
    decompressor = zlib.decompressobj(16 + zlib.MAX_WBITS)
    try:
        raw = decompressor.decompress(data, MAX_DECOMPRESSED_BYTES + 1)
    except zlib.error as exc:
        raise InvalidRegion(f"corrupt gzip data: {exc}") from None
    if len(raw) > MAX_DECOMPRESSED_BYTES or decompressor.unconsumed_tail:
        raise InvalidRegion(f"the NBT decompresses past {MAX_DECOMPRESSED_BYTES} bytes")
    return raw


def inspect(data: bytes, max_volume: int) -> list[int]:
    """Validate an uploaded structure NBT and return its [x, y, z] size."""
    raw = _decompress(data)
    try:
        root = _unwrap(nbtlib.File.parse(io.BytesIO(raw)), "size")
        size = [int(v) for v in root["size"]]
        palette = root.get("palette")
        blocks = root.get("blocks")
    except Exception as exc:  # nbtlib raises a variety of errors on malformed input
        raise InvalidRegion(f"not a structure NBT: {exc}") from None
    if len(size) != 3 or any(v <= 0 for v in size):
        raise InvalidRegion("size must be three positive integers")
    volume = size[0] * size[1] * size[2]
    if volume > max_volume:
        raise InvalidRegion(f"the region is {volume} blocks; the maximum is {max_volume}")
    if palette is None or blocks is None:
        raise InvalidRegion("the NBT has no palette or blocks (multi-palette structures are not supported)")
    return size
