"""Inspect and revise recorded NBT placements: provenance, drift against the world, in-place revision."""

from __future__ import annotations

import json
from typing import Any, Optional

import httpx
from mcp.types import CallToolResult, TextContent
from pydantic import ValidationError

from ..client.minecraft_api import MinecraftAPIClient
from ..client.operations import PendingOperation, pending_result
from ..utils.formatting import format_error_response
from ..utils.starlark_diagnostics import compact_diagnostics, diagnostic_text
from ..utils.starlark_models import Placement
from . import schematics, starlark

SOURCE_TEXT_LIMIT = 20_000


def _error(text: str, payload: dict[str, Any] | None = None) -> CallToolResult:
    return CallToolResult(content=[TextContent(type="text", text=text)], structuredContent=payload, isError=True)


def _http_error(exc: Exception, action: str) -> CallToolResult:
    if isinstance(exc, httpx.HTTPStatusError):
        try:
            body = exc.response.json()
        except ValueError:
            body = None
        if isinstance(body, dict) and body.get("error"):
            return _error(f"❌ {action} failed ({exc.response.status_code}): {body['error']}", body)
    return format_error_response(exc, action)


def _bounds(b: dict[str, Any]) -> str:
    return f"({b.get('min_x')}, {b.get('min_y')}, {b.get('min_z')}) to ({b.get('max_x')}, {b.get('max_y')}, {b.get('max_z')})"


def _active_child(info: dict[str, Any]) -> Optional[str]:
    active = [c["build_id"] for c in (info.get("lineage") or {}).get("children", [])
              if c.get("status") not in ("REVERTED", "SUPERSEDED")]
    return active[-1] if active else None


def _source_summary(source: dict[str, Any]) -> list[str]:
    kind = source.get("kind")
    lines = []
    if kind == "starlark":
        lines.append(f"Source: Starlark artifact {source.get('artifact_id')}, entry {source.get('entry', 'build')}.")
        lines.append(f"Props: {json.dumps(source.get('props') or {}, sort_keys=True)}"
                     + (f"; root_size {source['root_size']}" if source.get("root_size") else "") + ".")
        loads = (source.get("details") or {}).get("library_loads") or []
        if loads:
            lines.append("Loads library scripts: " + ", ".join(loads) + ".")
    elif kind == "schematic":
        title = (source.get("details") or {}).get("title")
        lines.append(f"Source: schematic {source.get('schematic_id')}" + (f" ({title})" if title else "") + ".")
    else:
        lines.append(f"Source: {kind}.")
    target = source.get("target")
    if target:
        lines.append(f"Requested position (walking plane) ({target['x']}, {target['y']}, {target['z']}); "
                     f"ground offset applied: {source.get('y_offset', 0)}.")
    return lines


def _revisable_reason(info: dict[str, Any]) -> Optional[str]:
    """Why revise_build would refuse this build, or None."""
    build, source = info["build"], info.get("source")
    status = build.get("status")
    if status == "SUPERSEDED":
        child = _active_child(info)
        return f"it was replaced by revision {child}; revise that build instead" if child else "it was replaced by a revision"
    if status == "REVERTED":
        return "it was undone; redo_build it first, or place a new structure"
    if status != "COMPLETED":
        return f"its status is {status}"
    if (info.get("placement") or {}).get("type") != "NBT_STRUCTURE":
        return "only NBT structure placements can be revised"
    if not source:
        return "no source was recorded with it (placed before provenance tracking, or a raw NBT upload)"
    if source.get("kind") not in ("starlark", "schematic"):
        return f"{source.get('kind')} placements cannot be recompiled"
    stored = info.get("stored") or {}
    if not stored.get("revision_base" if build.get("parent_build_id") else "undo_snapshot"):
        return "its saved terrain snapshot is missing (too large, or the save failed)"
    return None


async def handle_get_build_source(api_client: MinecraftAPIClient, build_id: str, include_source: bool = True,
                                  **arguments) -> CallToolResult:
    """Where a recorded placement came from, its lineage, and what can be done with it."""
    try:
        info = await api_client.get_build_source(build_id)
    except PendingOperation as pending:
        return pending_result(pending)
    except Exception as exc:
        return _http_error(exc, "Getting build source")
    build, placement = info["build"], info.get("placement") or {}
    source = info.get("source")
    origin, size = placement.get("origin") or {}, placement.get("size") or {}
    lines = [
        f"Build {build['id']} ({build['status']}): {build.get('name')} in {build.get('world')}.",
        f"Placed at origin ({origin.get('x')}, {origin.get('y')}, {origin.get('z')}), size "
        f"{size.get('x')}x{size.get('y')}x{size.get('z')}, rotation {placement.get('rotation')}; "
        f"covers {_bounds(placement.get('structure_bounds') or {})}.",
    ]
    lines += _source_summary(source) if source else ["Source: not recorded (placed before provenance tracking, or a raw NBT upload)."]
    lineage = info.get("lineage") or {}
    if lineage.get("parent_build_id"):
        lines.append(f"Revision of build {lineage['parent_build_id']} ({lineage.get('parent_status', 'unknown')}).")
    if lineage.get("children"):
        lines.append("Revisions of this build: " + ", ".join(
            f"{c['build_id']} ({c['status']})" for c in lineage["children"]) + ".")
    actions = []
    if info.get("undo_available"):
        actions.append("undo_build")
    if info.get("redo_available"):
        actions.append("redo_build")
    if (info.get("stored") or {}).get("placed_template"):
        actions.append("verify_build")
    reason = _revisable_reason(info)
    actions.append("revise_build" if reason is None else f"not revise_build ({reason})")
    lines.append("Available: " + ", ".join(actions) + ".")
    payload = dict(info)
    if source and source.get("source") is not None:
        text = source["source"]
        if include_source:
            shown = text if len(text) <= SOURCE_TEXT_LIMIT else text[:SOURCE_TEXT_LIMIT] + "\n# ... truncated; see structuredContent"
            lines.append(f"\nScript ({len(text.splitlines())} lines):\n```python\n{shown}\n```")
        else:
            payload["source"] = {k: v for k, v in source.items() if k != "source"}
    return CallToolResult(content=[TextContent(type="text", text="\n".join(lines))], structuredContent=payload)


async def handle_verify_build(api_client: MinecraftAPIClient, build_id: str, **arguments) -> CallToolResult:
    """Compare a placement's saved template with the blocks in the world now."""
    try:
        result = await api_client.verify_build(build_id)
    except PendingOperation as pending:
        return pending_result(pending)
    except Exception as exc:
        return _http_error(exc, "Verifying build")
    report = result.get("report") or {}
    lines = [
        f"Build {build_id}: {report.get('match_pct')}% of {report.get('template_blocks')} template cells match the world "
        f"({report.get('solid_match_pct')}% of {report.get('solid_template_blocks')} non-air blocks).",
        f"Missing (now air): {report.get('missing', 0)}; extra (template air now filled): {report.get('extra', 0)}; "
        f"changed to another block: {report.get('changed', 0)}.",
    ]
    if result.get("note"):
        lines.append(result["note"] + ".")
    top = report.get("top_differences") or []
    if top:
        lines.append("Top differences: " + ", ".join(f"{d['expected']} -> {d['found']} x{d['count']}" for d in top[:8]) + ".")
    samples = report.get("samples") or []
    if samples:
        lines.append("Examples: " + "; ".join(
            f"({s['x']}, {s['y']}, {s['z']}) {s['expected']} -> {s['found']}" for s in samples[:8]) + ".")
    if report.get("missing", 0) + report.get("extra", 0) + report.get("changed", 0):
        lines.append("A revision or undo overwrites these differences; edits made after placement are not kept.")
    lines.append("Compared by block id; block states (facing, open, ...) are ignored.")
    return CallToolResult(content=[TextContent(type="text", text="\n".join(lines))], structuredContent=result)


async def handle_revise_build(
    api_client: MinecraftAPIClient, build_id: str, source: Optional[str] = None,
    props: Optional[dict[str, Any]] = None, entry: Optional[str] = None, root_size: Optional[list[int]] = None,
    schematic_id: Optional[str] = None, rotation: Optional[str] = None, x: Optional[int] = None,
    y: Optional[int] = None, z: Optional[int] = None, dry_run: bool = False, force: bool = False,
    include_preview: bool = False, **arguments,
) -> CallToolResult:
    """Recompile/re-fetch a placement with changes and swap it in place in one locked step."""
    try:
        info = await api_client.get_build_source(build_id)
    except PendingOperation as pending:
        return pending_result(pending)
    except Exception as exc:
        return _http_error(exc, "Loading build")
    reason = _revisable_reason(info)
    if reason is not None:
        return _error(f"Build {build_id} cannot be revised: {reason}.", {"ok": False, "build_id": build_id, "reason": reason})

    recorded = info["source"]
    placement = info["placement"]
    origin = placement["origin"]
    target = recorded.get("target") or {"x": origin["x"], "y": origin["y"] - (recorded.get("y_offset") or 0), "z": origin["z"]}
    new_target = {"x": target["x"] if x is None else x, "y": target["y"] if y is None else y,
                  "z": target["z"] if z is None else z}
    new_rotation = rotation or placement.get("rotation", "NONE")
    world = info["build"].get("world") or "minecraft:overworld"
    details = recorded.get("details") or {}
    moved = new_target != target or new_rotation != placement.get("rotation")

    if recorded["kind"] == "schematic":
        if source is not None or props is not None or entry is not None or root_size is not None:
            return _error("This build is a schematic placement; only schematic_id, rotation and x/y/z can change.")
        new_id = str(schematic_id) if schematic_id is not None else recorded.get("schematic_id")
        if new_id == recorded.get("schematic_id") and not moved:
            return _error("Nothing to change: pass a different schematic_id, rotation or position.")
        return await schematics.place_schematic(
            api_client, new_id, new_target["x"], new_target["y"], new_target["z"], world, new_rotation,
            details.get("include_entities", True), True, details.get("apply_ground_offset", True), dry_run,
            replaces_build_id=build_id, force=force)

    if schematic_id is not None:
        return _error("This build is a Starlark placement; pass source/props/entry/root_size, not schematic_id.")
    old_source = recorded.get("source")
    new_source = source if source is not None else old_source
    if new_source is None:
        return _error(f"Build {build_id} recorded artifact {recorded.get('artifact_id')} but not its script; "
                      "pass the full source to revise it.")
    new_entry = entry or recorded.get("entry") or "build"
    new_props = {**(recorded.get("props") or {}), **(props or {})}
    new_root_size = root_size if root_size is not None else recorded.get("root_size")
    if (new_source == old_source and new_entry == (recorded.get("entry") or "build") and new_props == (recorded.get("props") or {})
            and new_root_size == recorded.get("root_size") and not moved):
        return _error("Nothing to change: pass a new source, props (merged into the recorded ones), entry, root_size, "
                      "rotation or position.")
    try:
        target_placement = Placement(x=new_target["x"], y=new_target["y"], z=new_target["z"], world=world,
                                     rotation=new_rotation, apply_y_offset=details.get("apply_y_offset", True),
                                     dry_run=dry_run)
    except ValidationError as exc:
        return _error(str(exc))
    client = starlark._starlark_client()
    try:
        result = await client.build(new_source, entry=new_entry, props=new_props, root_size=new_root_size)
    except PendingOperation as pending:
        return pending_result(pending)
    except Exception as exc:
        return starlark._service_error(exc, compilation_ok=False)
    if not result.get("ok"):
        compact = compact_diagnostics(result)
        return starlark._response(compact, "The revised script did not compile; the world was not changed.\n"
                                  + diagnostic_text(compact))
    placed = await starlark._place(api_client, client, result, target_placement, source=new_source,
                                   request={"entry": new_entry, "props": new_props, "root_size": new_root_size},
                                   replaces_build_id=build_id, force=force)
    if placed.structuredContent is None or placed.structuredContent.get("status") == "pending":
        return placed
    payload = {key: result[key] for key in starlark.BUILD_FIELDS if key in result}
    payload.update(compilation_ok=True, **placed.structuredContent)
    changed = [name for name, differs in (("source", new_source != old_source), ("props", new_props != (recorded.get("props") or {})),
                                          ("entry", new_entry != (recorded.get("entry") or "build")),
                                          ("root_size", new_root_size != recorded.get("root_size")),
                                          ("position/rotation", moved)) if differs]
    header = (f"Revision of build {build_id} ({', '.join(changed)} changed). " + starlark._build_summary(result))
    response = starlark._response(payload, header + "\n" + placed.content[0].text)
    if include_preview and payload.get("ok"):
        return await starlark._with_thumbnail(response, client, result["artifact_id"])
    return response
