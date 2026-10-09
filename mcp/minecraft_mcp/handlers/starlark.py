"""Handlers for compilation, focused documentation, and optional world placement."""

from __future__ import annotations

import base64
from typing import Any

import httpx
from mcp.types import CallToolResult, ImageContent, TextContent
from pydantic import ValidationError

from ..client.minecraft_api import MinecraftAPIClient
from ..client.starlark_service import StarlarkServiceClient
from ..config import STARLARK_SERVICE_URL
from ..utils.formatting import area_lock_error, format_dry_run, format_success_response, undo_hint
from ..utils.starlark_diagnostics import compact_diagnostics, diagnostic_text
from ..utils.starlark_models import Placement, StarlarkResult

THUMBNAIL_PX = 384
ORIENTATION_NOTE = ("Top view has north up and east right; side views are elevations seen from that side "
                    "(south = the +Z face, the default front); iso is viewed from the south-east.")

BUILD_FIELDS = ("artifact_id", "size", "block_count", "entity_count", "ground_level",
                "y_offset", "cached", "build_ms", "nbt_bytes", "palette")


def _starlark_client() -> StarlarkServiceClient:
    return StarlarkServiceClient(STARLARK_SERVICE_URL)


def _response(payload: dict[str, Any], text: str) -> CallToolResult:
    value = StarlarkResult.model_validate(payload).model_dump(exclude_none=True)
    return CallToolResult(content=[TextContent(type="text", text=text)],
                          structuredContent=value, isError=not value["ok"])


def _failure(kind: str, message: str, hint: str | None = None, **extra) -> CallToolResult:
    return _response({"ok": False, "error_kind": kind, "message": message, "hint": hint, **extra},
                     message + (f"\n{hint}" if hint else ""))


def _detail(exc: httpx.HTTPStatusError) -> str:
    try:
        body = exc.response.json()
        detail = body.get("detail", body.get("error", str(exc)))
        if isinstance(detail, list):
            return "; ".join(".".join(str(p) for p in item.get("loc", [])) + ": " + item.get("msg", str(item))
                             for item in detail)
        return str(detail)
    except (ValueError, AttributeError, TypeError):
        return str(exc)


def _service_error(exc: Exception, **extra) -> CallToolResult:
    if isinstance(exc, httpx.HTTPStatusError):
        status = exc.response.status_code
        if status in (400, 422):
            return _failure("invalid_request", _detail(exc), **extra)
        if status == 429:
            return _failure("busy", _detail(exc), "Retry compilation shortly.", **extra)
        if status == 404:
            return _failure("not_found", _detail(exc), **extra)
    if isinstance(exc, httpx.HTTPError):
        return _failure("unavailable", f"Starlark service unavailable: {exc}",
                        "Start the starlark compose profile or check STARLARK_SERVICE_URL.", **extra)
    return _failure("internal_error", str(exc), **extra)


def _build_summary(result: dict[str, Any]) -> str:
    size = "x".join(str(n) for n in result["size"])
    return (f"Compiled {result['artifact_id']}{' (cached)' if result.get('cached') else ''}: "
            f"{size}, {result['block_count']} blocks, {result.get('entity_count', 0)} entities, "
            f"ground_level={result.get('ground_level', 0)}, {result.get('build_ms', 0)} ms.")


async def _place(api_client: MinecraftAPIClient, client: StarlarkServiceClient,
                 metadata: dict[str, Any], placement: Placement) -> CallToolResult:
    artifact_id = metadata["artifact_id"]
    requested = {key: getattr(placement, key) for key in ("x", "y", "z")}
    position = {**requested, "y": placement.y + (metadata.get("y_offset", 0) if placement.apply_y_offset else 0)}
    outcome = {"status": "failed", "requested_position": requested, "position": position,
               "world": placement.world, "rotation": placement.rotation}
    try:
        nbt = await client.get_artifact_nbt(artifact_id)
    except Exception as exc:
        return _service_error(exc, artifact_id=artifact_id, placement=outcome)
    try:
        result = await api_client.place_nbt_structure_bytes(
            nbt, f"{artifact_id}.nbt", position["x"], position["y"], position["z"],
            placement.world, placement.rotation, placement.include_entities, True, placement.dry_run)
    except Exception as exc:
        # A timeout/disconnect or server error may occur after the world write.
        conflict = area_lock_error(exc)
        if conflict is not None:
            return _failure(conflict["code"], conflict["error"],
                            "Keep the original build location. Explicitly renew, resize, or coordinate the reservation before retrying; never omit lock_id to bypass the error.",
                            artifact_id=artifact_id, placement=outcome, lock_error=conflict)
        rejected = (isinstance(exc, httpx.HTTPStatusError)
                    and 400 <= exc.response.status_code < 500 and exc.response.status_code != 408)
        outcome["status"] = "failed" if rejected else "unknown"
        message = _detail(exc) if isinstance(exc, httpx.HTTPStatusError) else str(exc)
        hint = ("Correct the request and retry place_starlark_structure with this artifact ID." if rejected else
                "Placement outcome is unknown. Inspect the world before retrying; entities could be duplicated.")
        return _failure("placement_failed" if rejected else "placement_unknown", message, hint,
                        artifact_id=artifact_id, placement=outcome)
    if not result.get("success"):
        return _failure("placement_failed", f"Placement failed: {result}",
                        "Inspect the world before retrying place_starlark_structure; partial placement may have occurred.",
                        artifact_id=artifact_id, placement=outcome)
    if placement.dry_run:
        outcome.update(status="dry_run", **{key: result.get(key) for key in
                                            ("overwrite", "lock_check", "reservations", "builds")})
        return _response({"ok": True, "artifact_id": artifact_id, "placement": outcome},
                         f"Dry run of {artifact_id} at ({position['x']}, {position['y']}, {position['z']}).\n"
                         + format_dry_run(result))
    outcome.update(status="placed", build_id=result.get("build_id"),
                   undo_available=result.get("undo_available"),
                   undo_unavailable_reason=result.get("undo_unavailable_reason"))
    return _response({"ok": True, "artifact_id": artifact_id, "placement": outcome},
                     f"Placed {artifact_id} at ({position['x']}, {position['y']}, {position['z']}) "
                     f"in {placement.world}, rotation {placement.rotation}."
                     + (f" Build ID: {result['build_id']}" if result.get("build_id") else "")
                     + (f" {undo_hint(result)}" if undo_hint(result) else ""))


def _image(data: bytes) -> ImageContent:
    return ImageContent(type="image", mimeType="image/png", data=base64.b64encode(data).decode("ascii"))


async def _with_thumbnail(result: CallToolResult, client: StarlarkServiceClient, artifact_id: str) -> CallToolResult:
    """Attach a small iso preview; best-effort, so a render failure never fails the build."""
    try:
        data = await client.get_artifact_image(artifact_id, "iso", THUMBNAIL_PX)
    except Exception as exc:
        result.content.append(TextContent(type="text", text=f"(Preview unavailable: {exc})"))
        return result
    result.content.append(_image(data))
    result.content.append(TextContent(type="text", text="Iso preview from the south-east; "
                                      "get_starlark_preview(view=\"sheet\") shows all six views."))
    return result


async def handle_build_starlark_structure(
    api_client: MinecraftAPIClient, source: str, entry: str = "build",
    props: dict[str, Any] | None = None, root_size: list[int] | None = None,
    placement: dict[str, Any] | None = None, include_preview: bool = False, **arguments,
) -> CallToolResult:
    try:
        target = Placement.model_validate(placement) if placement is not None else None
    except ValidationError as exc:
        return _failure("invalid_request", str(exc))
    client = _starlark_client()
    try:
        result = await client.build(source, entry=entry, props=props, root_size=root_size)
    except Exception as exc:
        return _service_error(exc, compilation_ok=False)
    if not result.get("ok"):
        compact = compact_diagnostics(result)
        return _response(compact, diagnostic_text(compact))
    payload = {key: result[key] for key in BUILD_FIELDS if key in result}
    payload.update(ok=True, compilation_ok=True)
    summary = _build_summary(result)
    if target is not None:
        placed = await _place(api_client, client, result, target)
        payload.update(placed.structuredContent)
        response = _response(payload, summary + "\n" + placed.content[0].text)
    else:
        response = _response(payload, summary + "\nNext: check it with get_starlark_preview, then place_starlark_structure "
                             "with this artifact ID and world coordinates; ground offset is automatic."
                             " If the result is worth reusing, save_starlark_script it to the shared library.")
    if include_preview:
        return await _with_thumbnail(response, client, result["artifact_id"])
    return response


async def handle_place_starlark_structure(
    api_client: MinecraftAPIClient, artifact_id: str, x: int, y: int, z: int,
    world: str | None = None, rotation: str = "NONE", include_entities: bool = True,
    apply_y_offset: bool = True, dry_run: bool = False, **arguments,
) -> CallToolResult:
    try:
        target = Placement(x=x, y=y, z=z, world=world or "minecraft:overworld", rotation=rotation,
                           include_entities=include_entities, apply_y_offset=apply_y_offset, dry_run=dry_run)
    except ValidationError as exc:
        return _failure("invalid_request", str(exc), artifact_id=artifact_id)
    client = _starlark_client()
    try:
        metadata = await client.get_artifact(artifact_id)
    except httpx.HTTPStatusError as exc:
        if exc.response.status_code == 404:
            return _failure("not_found", f"Artifact {artifact_id} is unavailable (evicted or never built).",
                            "Recompile the original source, entry, props, and root_size, then use the returned ID.", artifact_id=artifact_id)
        return _service_error(exc, artifact_id=artifact_id)
    except Exception as exc:
        return _service_error(exc, artifact_id=artifact_id)
    return await _place(api_client, client, {**metadata, "artifact_id": artifact_id}, target)


CUT_NOTE = ("Cuts use the artifact's local coordinates (0 = its west/bottom/north edge, before placement "
            "rotation and y_offset). The cutaway iso removes everything above y (or east of x / south of z) "
            "so the cut faces the camera; the section looks straight at the cut plane: blocks on the plane are "
            "full colour with black outlines where the material changes, blocks further back fade out.")


def _preview_text(artifact_id: str, view: str, cut: dict[str, int], info: dict[str, str]) -> str:
    size = info.get("x-artifact-size")
    sized = f" (size {size}, x by y by z)" if size else ""
    if cut:
        (key, at), = cut.items()
        return f"Artifact {artifact_id}{sized} {view} at {key}={at}. {CUT_NOTE}"
    if view == "floors":
        levels = [entry.split(":") for entry in info.get("x-preview-floors", "").split(",") if entry]
        listed = ", ".join(f"floor y={floor} (cut y={cut_y})" for floor, cut_y in levels) or "none"
        return (f"Artifact {artifact_id}{sized} floor plans: {listed}. Each plan looks down from two blocks above "
                f"the floor, north up. {CUT_NOTE} Use cut_y with view='iso' for a 3D cutaway of one storey.")
    return (f"Artifact {artifact_id}{sized} {view} preview. {ORIENTATION_NOTE} "
            "To check interiors, use view='floors' or a cut_y/cut_x/cut_z.")


async def handle_get_starlark_preview(api_client: MinecraftAPIClient, artifact_id: str, view: str = "sheet",
                                      max_px: int | None = None, cut_x: int | None = None,
                                      cut_y: int | None = None, cut_z: int | None = None,
                                      **arguments) -> CallToolResult:
    cut = {key: value for key, value in (("cut_x", cut_x), ("cut_y", cut_y), ("cut_z", cut_z)) if value is not None}
    try:
        data, info = await _starlark_client().get_artifact_preview(artifact_id, view, max_px, cut or None)
    except httpx.HTTPStatusError as exc:
        status = exc.response.status_code
        if status == 404:
            return _failure("not_found", f"Artifact {artifact_id} is unavailable (evicted or never built).",
                            "Recompile the original source, entry, props, and root_size, then use the returned ID.")
        if status == 503:
            return _failure("preview_unavailable", _detail(exc),
                            "The artifact is still placeable; inspect it in the world with a dry run instead.")
        return _service_error(exc)
    except Exception as exc:
        return _service_error(exc)
    return CallToolResult(content=[
        _image(data),
        TextContent(type="text", text=_preview_text(artifact_id, view, cut, info)),
    ])


async def handle_get_starlark_docs(api_client: MinecraftAPIClient, topic: str = "quickstart",
                                  component: str | None = None, **arguments) -> CallToolResult:
    try:
        return format_success_response(await _starlark_client().get_catalog(topic, component))
    except Exception as exc:
        return _service_error(exc)


async def handle_list_starlark_examples(api_client: MinecraftAPIClient, **arguments) -> CallToolResult:
    try:
        result = await _starlark_client().list_examples()
    except Exception as exc:
        return _service_error(exc)
    examples = result.get("examples") or []
    lines = [f"{len(examples)} example script(s); fetch one with get_starlark_example:"]
    for example in examples:
        lines.append(f"- {example['name']}" + (f" - {example['summary']}" if example.get("summary") else ""))
    return format_success_response("\n".join(lines))


async def handle_get_starlark_example(api_client: MinecraftAPIClient, name: str, **arguments) -> CallToolResult:
    try:
        return format_success_response(await _starlark_client().get_example(name))
    except httpx.HTTPStatusError as exc:
        if exc.response.status_code in (400, 404):
            return _failure("not_found", f"Example {name!r} was not found. Use list_starlark_examples for valid names.")
        return _service_error(exc)
    except Exception as exc:
        return _service_error(exc)


def _params_text(params: list[dict[str, Any]]) -> str:
    return ", ".join(p["name"] + (f"={p['default']!r}" if "default" in p else "") for p in params)


def _load_line(load_path: str, exports: list[str]) -> str:
    return f'load("{load_path}", ' + ", ".join(f'"{name}"' for name in exports) + ")"


def _size_text(entry: dict[str, Any]) -> str:
    # With exported components the stored size is just the demo build(); component size follows its arguments.
    size = "x".join(str(n) for n in entry["size"])
    return f"demo build() {size}" if entry.get("exports") else size


def _signatures_text(entry: dict[str, Any]) -> str:
    signatures = entry.get("signatures") or {}
    return ", ".join(signatures.get(name, name) for name in entry["exports"])


def _library_uses(loads: list[str]) -> list[str]:
    uses = []
    for load in loads:
        path, _, symbols = load.partition(":")
        if path.startswith("library/"):
            _, name, version = path.removesuffix(".star").split("/", 2)
            uses.append(f"{name}@{version.lstrip('v')} ({symbols})")
    return uses


async def handle_save_starlark_script(
    api_client: MinecraftAPIClient, name: str, artifact_id: str | None = None, source: str | None = None,
    title: str | None = None, description: str | None = None, tags: list[str] | None = None,
    author: str | None = None, notes: str | None = None, parent: str | None = None,
    entry: str | None = None, props: dict[str, Any] | None = None, root_size: list[int] | None = None,
    **arguments,
) -> CallToolResult:
    body = {"name": name, "artifact_id": artifact_id, "source": source, "title": title,
            "description": description, "tags": tags, "author": author, "notes": notes,
            "parent": parent, "entry": entry, "props": props, "root_size": root_size}
    try:
        result = await _starlark_client().save_library({k: v for k, v in body.items() if v is not None})
    except Exception as exc:
        return _service_error(exc)
    if not result.get("ok"):
        compact = compact_diagnostics(result)
        return _response(compact, "Not saved: the script must build first.\n" + diagnostic_text(compact))
    ref = f"{result['name']}@{result['version']}"
    lines = [f"Saved {ref}." if result["created"] else f"{ref} already holds this exact source; nothing new saved.",
             f"Size {_size_text(result)}, artifact {result['artifact_id']}."]
    if result["params"]:
        lines.append(f"Entry params: {_params_text(result['params'])}.")
    if result["exports"]:
        lines.append(f"Exports {_signatures_text(result)}; other scripts reuse them with: "
                     + _load_line(result["load_path"], result["exports"]))
    else:
        lines.append(
            f"No UpperCamel component exported, so others can only fork it (get_starlark_script(\"{result['name']}\")), "
            "not load() it. Consider saving a new version that moves the body into a parametrized component "
            "build() calls, e.g. def MageTower(height=12): return component(name=\"MageTower\", min_size=[...], ...) "
            "and def build(): return MageTower()."
        )
    return format_success_response("\n".join(lines))


async def handle_search_starlark_library(
    api_client: MinecraftAPIClient, query: str = "", tag: str | None = None, author: str | None = None,
    max_size: list[int | None] | None = None, limit: int = 10, **arguments,
) -> CallToolResult:
    max_x, max_y, max_z = (list(max_size or []) + [None, None, None])[:3]
    try:
        result = await _starlark_client().search_library(q=query, tag=tag, author=author, max_x=max_x,
                                                         max_y=max_y, max_z=max_z, limit=limit)
    except Exception as exc:
        return _service_error(exc)
    entries = result.get("results") or []
    if not entries:
        return format_success_response("No saved scripts match. Write a new one and save it with save_starlark_script.")
    lines = [f"{len(entries)} saved script(s); get_starlark_script(name) returns source and details:"]
    for entry in entries:
        line = f"- {entry['name']}@{entry['latest']} — {entry['title']} ({_size_text(entry)}"
        line += f"; tags {', '.join(entry['tags'])})" if entry["tags"] else ")"
        line += f": {entry['description']}"
        if entry["exports"]:
            line += f"\n  exports {_signatures_text(entry)} via {entry['load_path']}"
        lines.append(line)
    return format_success_response("\n".join(lines))


async def handle_get_starlark_script(api_client: MinecraftAPIClient, name: str, version: int | None = None,
                                     **arguments) -> CallToolResult:
    client = _starlark_client()
    try:
        meta = await client.get_library_entry(name)
        resolved, source = await client.get_library_source(name, version)
    except httpx.HTTPStatusError as exc:
        if exc.response.status_code in (400, 404):
            return _failure("not_found", _detail(exc), "Use search_starlark_library to find saved scripts.")
        return _service_error(exc)
    except Exception as exc:
        return _service_error(exc)
    record = next(v for v in meta["versions"] if v["version"] == resolved)
    ref = f"{name}@{resolved}"
    lines = [f"# {ref} — {meta['title']}" + (f" (latest is v{meta['latest']})" if resolved != meta["latest"] else ""),
             meta["description"],
             f"Size {_size_text(record)}, {record['block_count']} blocks, "
             f"ground_level={record['ground_level']}; entry {record['entry']}({_params_text(record['params'])})"
             + (f" built with props {record['props']}" if record["props"] else "") + "."]
    if meta["tags"]:
        lines.append("Tags: " + ", ".join(meta["tags"]))
    lineage = [f"author {record['author']}" if record.get("author") else "",
               f"forked from {record['parent']}" if record.get("parent") else "",
               f"notes: {record['notes']}" if record.get("notes") else ""]
    if any(lineage):
        lines.append("; ".join(part for part in lineage if part))
    if record["exports"]:
        lines.append(f"Exports {_signatures_text(record)}; reuse: "
                     + _load_line(f"../library/{name}/v{resolved}.star", record["exports"]))
    uses = _library_uses(record["loads"])
    if uses:
        lines.append("Uses library: " + ", ".join(uses))
    dependents = meta.get("used_by") or []
    if dependents:
        lines.append("Used by: " + ", ".join(f"{d['name']}@{d['version']} (loads v{d['loads_version']})"
                                             for d in dependents)
                     + ". Loads are pinned, so new versions never change these until they are re-saved.")
    lines.append(f"Fork: edit and build the source below, then save_starlark_script with parent=\"{ref}\" "
                 f"(same name for a new version, or a new name).")
    lines.append(f"\n```python\n{source.rstrip()}\n```")
    return format_success_response("\n".join(lines))
