"""
Terrain shaping tool handlers: smooth_terrain.
"""

from ..client.operations import PendingOperation, pending_result

from typing import Any, Dict, List, Optional

from mcp.types import CallToolResult, ImageContent, TextContent

from ..client.minecraft_api import MinecraftAPIClient
from ..utils.formatting import format_error_response

SMOOTH_FIELDS = ("method", "sigma", "radius", "sigma_range", "iterations", "pad", "strength",
                 "feather", "max_change", "dry_run", "px_per_block", "contour_interval")


async def handle_smooth_terrain(
    api_client: MinecraftAPIClient,
    x1: int,
    z1: int,
    x2: int,
    z2: int,
    method: str,
    world: Optional[str] = None,
    **arguments
) -> CallToolResult:
    """Smooth (gaussian/median/bilateral) or pad a region's ground; dry_run returns map images."""
    payload: Dict[str, Any] = {"x1": x1, "z1": z1, "x2": x2, "z2": z2, "method": method}
    if world:
        payload["world"] = world
    for key in SMOOTH_FIELDS[1:]:
        if arguments.get(key) is not None:
            payload[key] = arguments[key]
    try:
        result = await api_client.smooth_terrain(payload)
    except PendingOperation as pending:
        return pending_result(pending)
    except Exception as e:
        return format_error_response(e, "smoothing terrain")

    if result.get("status_code") != 200 or not result.get("success"):
        return CallToolResult(
            content=[TextContent(type="text", text=format_smooth_error(result))],
            isError=True,
        )

    content: List[Any] = []
    for key in ("before_png", "after_png", "change_png"):
        if result.get(key):
            content.append(ImageContent(type="image", mimeType="image/png", data=result[key]))
    content.append(TextContent(type="text", text=format_smooth_summary(result)))
    return CallToolResult(content=content)


def format_smooth_error(result: Dict[str, Any]) -> str:
    code = result.get("code")
    text = f"❌ Terrain smoothing failed ({code}): {result.get('error', 'Unknown error')}" if code \
        else f"❌ Terrain smoothing failed: {result.get('error', 'Unknown error')}"
    if result.get("missing_chunks"):
        text += f"\n{len(result['missing_chunks'])} chunk(s) are not loaded; move a player nearby and retry."
    if code == "area_locked":
        text += "\nThe region overlaps another reservation; coordinate with its owner rather than shrinking around it silently."
    if code == "terrain_changed":
        text += "\nNothing was written. Run a dry run again to check the new ground before applying."
    return text


def format_smooth_summary(result: Dict[str, Any]) -> str:
    """The numbers behind the images and what was (or would be) written."""
    region = result.get("region") or {}
    size = result.get("size") or {}
    s = result.get("summary") or {}
    dry = bool(result.get("dry_run"))
    head = "**Terrain smoothing dry run** (nothing written)" if dry else "**Terrain smoothed**"
    lines = [
        f"{head}: {result.get('method')} over X {region.get('min_x')}..{region.get('max_x')}, "
        f"Z {region.get('min_z')}..{region.get('max_z')} ({size.get('x')}x{size.get('z')} columns), {result.get('world')}.",
        f"Changed {s.get('changed_columns')} of {s.get('columns')} columns: cut {s.get('cut_blocks')} blocks "
        f"(max {s.get('max_lowered')} down), fill {s.get('fill_blocks')} blocks (max {s.get('max_raised')} up).",
    ]
    ground = result.get("ground_y") or {}
    if ground:
        b, a = ground.get("before", {}), ground.get("after", {})
        lines.append(f"Ground Y (unmasked columns): before {b.get('min')}..{b.get('max')}, "
                     f"after {a.get('min')}..{a.get('max')}.")
    masked = s.get("masked_by_kind") or {}
    if s.get("masked_columns"):
        kinds = ", ".join(f"{kind} {count}" for kind, count in masked.items())
        lines.append(f"Kept unchanged: {s.get('masked_columns')} masked columns ({kinds}) "
                     f"and {s.get('protected_columns')} columns next to them.")
    if s.get("clamped_columns"):
        lines.append(f"⚠️ {s['clamped_columns']} columns hit max_change and stopped short of the target.")
    if "pad_columns" in s:
        lines.append(f"Pad: {s.get('pad_columns_at_target')} of {s.get('pad_columns')} footprint columns reach the target Y.")
    if dry:
        lines.append(f"Vegetation would be cleared in {s.get('vegetation_columns')} columns.")
        lock = result.get("lock_check") or {}
        lines.append("Area lock check: ok." if lock.get("ok") else
                     f"⚠️ Area lock check failed ({lock.get('code')}): {lock.get('error')}")
        builds = result.get("builds") or {}
        if builds.get("total"):
            lines.append(f"⚠️ Overlaps {builds['total']} recorded build(s); smoothing may cut into them.")
        if not result.get("undo_snapshot"):
            lines.append("⚠️ Applying would not be undoable (snapshots disabled or region too tall/large).")
        interval = result.get("contour_interval")
        lines.append(
            f"Images: before relief, after relief (same grey ramp, contours every {interval}), and a change map "
            f"(blue = raised, red = lowered, grey x = masked). North up, +X east; grid every "
            f"{result.get('grid_step')} blocks, 1 block = {result.get('px_per_block')} px. "
            "Heights are standing Y with vegetation ignored."
        )
        if result.get("labels") is False:
            lines.append("⚠️ The server could not draw text, so the images have no labels; use the numbers above.")
        lines.append("Call again without dry_run (same parameters) to apply.")
    else:
        lines.append(f"Cleared {result.get('vegetation_blocks_cleared')} vegetation blocks.")
        if result.get("build_id"):
            lines.append(f"Recorded as build {result['build_id']}.")
        if result.get("undo_available"):
            lines.append("Undo with undo_build(build_id); redo_build puts it back.")
        else:
            lines.append(f"⚠️ Undo unavailable: {result.get('undo_unavailable_reason')}.")
    return "\n".join(lines)
