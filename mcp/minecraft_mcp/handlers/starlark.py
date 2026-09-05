"""Handlers for compilation, focused documentation, and optional world placement."""

from __future__ import annotations

from typing import Any

import httpx
from mcp.types import CallToolResult, TextContent
from pydantic import ValidationError

from ..client.minecraft_api import MinecraftAPIClient
from ..client.starlark_service import StarlarkServiceClient
from ..config import STARLARK_SERVICE_URL
from ..utils.formatting import format_success_response
from ..utils.starlark_diagnostics import compact_diagnostics, diagnostic_text
from ..utils.starlark_models import Placement, StarlarkResult

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
            placement.world, placement.rotation, placement.include_entities, True)
    except Exception as exc:
        # A timeout/disconnect or server error may occur after the world write.
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
    outcome.update(status="placed", build_id=result.get("build_id"))
    return _response({"ok": True, "artifact_id": artifact_id, "placement": outcome},
                     f"Placed {artifact_id} at ({position['x']}, {position['y']}, {position['z']}) "
                     f"in {placement.world}, rotation {placement.rotation}."
                     + (f" Build ID: {result['build_id']}" if result.get("build_id") else ""))


async def handle_build_starlark_structure(
    api_client: MinecraftAPIClient, source: str, entry: str = "build",
    props: dict[str, Any] | None = None, root_size: list[int] | None = None,
    placement: dict[str, Any] | None = None, **arguments,
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
        return _response(payload, summary + "\n" + placed.content[0].text)
    return _response(payload, summary + "\nNext: place_starlark_structure with this artifact ID and world coordinates; ground offset is automatic.")


async def handle_place_starlark_structure(
    api_client: MinecraftAPIClient, artifact_id: str, x: int, y: int, z: int,
    world: str | None = None, rotation: str = "NONE", include_entities: bool = True,
    apply_y_offset: bool = True, **arguments,
) -> CallToolResult:
    try:
        target = Placement(x=x, y=y, z=z, world=world or "minecraft:overworld", rotation=rotation,
                           include_entities=include_entities, apply_y_offset=apply_y_offset)
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
