"""Render a region of the live world (or a placed build in context) with the Starlark preview renderer.

The mod captures the cuboid as structure NBT (`GET /api/world/region/nbt`), the starlark service stores it
under a content-addressed `rgn_` id (`POST /renders`), and the usual artifact image route renders it.
Cuts are given in world coordinates here and converted to the region's local coordinates.
"""

from __future__ import annotations

from typing import Any

import httpx
from mcp.types import CallToolResult, TextContent

from ..client.minecraft_api import MinecraftAPIClient
from .starlark import ORIENTATION_NOTE, _detail, _image, _starlark_client

AXES = ("x", "y", "z")

CAPTURE_HINTS = {
    "chunks_not_loaded": "Teleport a player near the region (or narrow it) so its chunks are loaded, then retry.",
    "region_too_large": "Render a build by build_id, lower margin/below/above, or give a smaller cuboid.",
    "build_not_found": "Check the build id with query_builds_by_location or get_build_status.",
    "build_has_no_bounds": "Give the region as x1,y1,z1,x2,y2,z2 instead.",
    "builds_unavailable": "Build history (PostgreSQL) is unavailable; give the region as x1,y1,z1,x2,y2,z2.",
    "capture_timeout": "The server is busy or the region is large; retry or render a smaller region.",
}

WORLD_CUT_NOTE = ("The cutaway iso removes everything above the cut y (or east of x / south of z) so the cut "
                  "faces the camera; the section looks straight at the cut plane: blocks on the plane are full "
                  "colour with black outlines where the material changes, blocks further back fade out.")


def _error(code: str, message: str, hint: str | None = None) -> CallToolResult:
    text = f"render_region failed ({code}): {message}" + (f"\n{hint}" if hint else "")
    return CallToolResult(content=[TextContent(type="text", text=text)], isError=True)


def _capture_error(exc: httpx.HTTPStatusError) -> CallToolResult:
    body: Any = {}
    try:
        body = exc.response.json()
        code, message = str(body.get("code", "http_error")), str(body.get("error", exc))
    except (ValueError, AttributeError):
        body = {}
        code, message = "http_error", f"Minecraft API HTTP error {exc.response.status_code}"
    if code == "chunks_not_loaded" and isinstance(body.get("missing_chunks"), list):
        chunks = body["missing_chunks"][:8]
        message += " (chunks " + ", ".join(f"{c.get('x')},{c.get('z')}" for c in chunks) + \
            (", ..." if len(body["missing_chunks"]) > 8 else "") + ")"
    return _error(code, message, CAPTURE_HINTS.get(code))


def _triple(value: str) -> list[int]:
    parts = [int(part) for part in value.split(",")]
    if len(parts) != 3:
        raise ValueError(value)
    return parts


def _span(origin: list[int], size: list[int], axis: int) -> str:
    return f"{origin[axis]}..{origin[axis] + size[axis] - 1}"


async def handle_render_region(api_client: MinecraftAPIClient, build_id: str | None = None,
                               x1: int | None = None, y1: int | None = None, z1: int | None = None,
                               x2: int | None = None, y2: int | None = None, z2: int | None = None,
                               world: str | None = None, margin: int | None = None, below: int | None = None,
                               above: int | None = None, view: str = "sheet", max_px: int | None = None,
                               cut_x: int | None = None, cut_y: int | None = None, cut_z: int | None = None,
                               **arguments: Any) -> CallToolResult:
    corners = {"x1": x1, "y1": y1, "z1": z1, "x2": x2, "y2": y2, "z2": z2}
    given = [name for name, value in corners.items() if value is not None]
    if build_id and given:
        return _error("invalid_request", "Give either build_id or x1,y1,z1,x2,y2,z2, not both.")
    if not build_id and len(given) != len(corners):
        return _error("invalid_request", "Give build_id, or all of x1,y1,z1,x2,y2,z2.")
    if not build_id and any(v is not None for v in (margin, below, above)):
        return _error("invalid_request", "margin, below and above only apply with build_id.")
    cuts = [(axis, at) for axis, at in zip(AXES, (cut_x, cut_y, cut_z)) if at is not None]
    if len(cuts) > 1:
        return _error("invalid_request", "Give at most one of cut_x, cut_y, cut_z.")
    if view == "section" and not cuts:
        return _error("invalid_request", "view='section' needs cut_x, cut_y or cut_z.")

    params: dict[str, Any] = {"world": world}
    if build_id:
        params.update(build_id=build_id, margin=margin, below=below, above=above)
    else:
        params.update(corners)

    try:
        data, info = await api_client.capture_region(params)
    except httpx.HTTPStatusError as exc:
        return _capture_error(exc)
    except httpx.HTTPError as exc:
        return _error("unavailable", f"Minecraft API unavailable: {exc}")
    try:
        origin, size = _triple(info["x-region-origin"]), _triple(info["x-region-size"])
    except (KeyError, ValueError):
        return _error("invalid_response", "The Minecraft API did not report the captured region's origin and size.")
    region_world = info.get("x-region-world", world or "minecraft:overworld")

    local_cut: dict[str, int] = {}
    if cuts:
        axis, at = cuts[0]
        index = AXES.index(axis)
        local = at - origin[index]
        if not 0 <= local < size[index]:
            return _error("invalid_request",
                          f"cut_{axis}={at} is outside the captured region ({axis} {_span(origin, size, index)}).")
        local_cut = {f"cut_{axis}": local}

    client = _starlark_client()
    try:
        uploaded = await client.upload_render(data, info.get("x-region-origin"), region_world)
        image, preview_info = await client.get_artifact_preview(uploaded["artifact_id"], view, max_px,
                                                                local_cut or None)
    except httpx.HTTPStatusError as exc:
        status = exc.response.status_code
        if status == 413:
            return _error("region_too_large", _detail(exc), CAPTURE_HINTS["region_too_large"])
        if status == 503:
            return _error("render_unavailable", _detail(exc),
                          "Rendering failed or timed out; try a smaller region or a single view.")
        if status in (400, 422):
            return _error("invalid_request", _detail(exc))
        return _error("render_failed", _detail(exc))
    except httpx.HTTPError as exc:
        return _error("render_service_unavailable",
                      f"The region renderer (starlark-service) is unreachable: {exc}",
                      "Start the starlark compose profile or check STARLARK_SERVICE_URL.")

    extent = (f"x {_span(origin, size, 0)}, y {_span(origin, size, 1)}, z {_span(origin, size, 2)} "
              f"({size[0]}x{size[1]}x{size[2]} blocks)")
    subject = f"build {build_id} in context" if build_id else "region"
    lines = [f"Live render of {subject} in {region_world}: {extent}. Region id {uploaded['artifact_id']}."]
    if info.get("x-build-removed") == "true":
        lines.append(f"Build {build_id} has been undone or superseded; this shows what is in the world now.")
    if cuts:
        axis, at = cuts[0]
        lines.append(f"{view} at world {axis}={at} (local {axis}={local_cut[f'cut_{axis}']}). {WORLD_CUT_NOTE}")
    elif view == "floors":
        levels = []
        for entry in preview_info.get("x-preview-floors", "").split(","):
            if ":" in entry:
                floor, cut = entry.split(":", 1)
                levels.append(f"floor y={int(floor) + origin[1]} (cut y={int(cut) + origin[1]})")
        lines.append("Floor plans (world Y): " + (", ".join(levels) or "none detected") + ". Each plan looks down "
                     "from two blocks above the floor, north up. Use cut_y with view='iso' for a 3D cutaway.")
    else:
        lines.append(f"{view} view. {ORIENTATION_NOTE} Colours are flat per block type. "
                     "For interiors use view='floors' or cut_y/cut_x/cut_z (world coordinates).")
    return CallToolResult(content=[_image(image), TextContent(type="text", text="\n".join(lines))])
