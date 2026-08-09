"""Handlers for the optional Starlark build service."""

from __future__ import annotations

from typing import Any, Optional

import httpx
from mcp.types import CallToolResult, TextContent

from ..client.minecraft_api import MinecraftAPIClient
from ..client.starlark_service import StarlarkServiceClient
from ..config import STARLARK_SERVICE_URL
from ..utils.formatting import format_error_response, format_success_response


def _starlark_client() -> StarlarkServiceClient:
    return StarlarkServiceClient(STARLARK_SERVICE_URL)


def _unavailable(error: Exception) -> CallToolResult:
    return CallToolResult(
        content=[
            TextContent(
                type="text",
                text=(
                    "Starlark build service is unavailable. Start it with the optional "
                    f"starlark compose profile or set STARLARK_SERVICE_URL. Error: {error}"
                ),
            )
        ]
    )


def _format_diagnostics(result: dict[str, Any]) -> str:
    lines = [f"Build failed ({result.get('error_kind', 'build_error')}). Diagnostics:"]
    for index, diagnostic in enumerate(result.get("diagnostics") or [], start=1):
        location = ""
        if diagnostic.get("file") and diagnostic.get("line") is not None:
            location = f" {diagnostic['file']}:{diagnostic['line']}"
        path = diagnostic.get("component_path")
        if path and path != "<root>":
            location += f" at {path}"
        line = f"{index}. [{diagnostic.get('code')}]{location} — {diagnostic.get('message')}"
        if diagnostic.get("coordinates"):
            line += f" (coordinates {diagnostic['coordinates']})"
        lines.append(line)
    hint = result.get("hint")
    if hint:
        lines.append(f"Hint: {hint}")
    lines.append(
        "Note: build-rule diagnostics reference component paths, not script line numbers; "
        "only Starlark syntax/eval errors carry file:line."
    )
    return "\n".join(lines)


def _format_build_summary(result: dict[str, Any]) -> str:
    size = result.get("size") or ["?", "?", "?"]
    palette = ", ".join(
        f"{item['block']} ({item['count']})" for item in (result.get("palette") or [])[:5]
    )
    lines = [
        f"Build succeeded{' (cached)' if result.get('cached') else ''}: {result.get('artifact_id')}",
        f"Size: {size[0]}x{size[1]}x{size[2]} (width x height x length) | "
        f"Blocks: {result.get('block_count')} | Entities: {result.get('entity_count', 0)} | "
        f"y_offset: {result.get('y_offset', 0)}",
    ]
    if palette:
        lines.append(f"Top blocks: {palette}")
    lines.append(
        "Next: call place_starlark_structure with this artifact_id and world coordinates. "
        "The y_offset is applied automatically so the structure's ground level lands at your y."
    )
    return "\n".join(lines)


async def handle_build_starlark_structure(
    api_client: MinecraftAPIClient,
    source: str,
    entry: str = "build",
    props: Optional[dict[str, Any]] = None,
    root_size: Optional[list[int]] = None,
    **arguments,
) -> CallToolResult:
    try:
        result = await _starlark_client().build(source, entry=entry, props=props, root_size=root_size)
    except httpx.HTTPStatusError as exc:
        if exc.response.status_code in (400, 429):
            try:
                detail = exc.response.json().get("detail", str(exc))
            except ValueError:
                detail = str(exc)
            return format_success_response(f"Build request rejected: {detail}")
        return _unavailable(exc)
    except httpx.HTTPError as exc:
        return _unavailable(exc)
    except Exception as exc:
        return format_error_response(exc, "building starlark structure")

    if not result.get("ok"):
        return format_success_response(_format_diagnostics(result))
    return format_success_response(_format_build_summary(result))


async def handle_place_starlark_structure(
    api_client: MinecraftAPIClient,
    artifact_id: str,
    x: int,
    y: int,
    z: int,
    world: Optional[str] = None,
    rotation: str = "NONE",
    include_entities: bool = True,
    apply_y_offset: bool = True,
    **arguments,
) -> CallToolResult:
    client = _starlark_client()
    try:
        metadata = await client.get_artifact(artifact_id)
        nbt_bytes = await client.get_artifact_nbt(artifact_id)
    except httpx.HTTPStatusError as exc:
        if exc.response.status_code == 404:
            return format_success_response(
                f"Artifact {artifact_id} is not available (evicted or never built). "
                "Rebuild with build_starlark_structure — the same source yields the same id."
            )
        if exc.response.status_code == 400:
            return format_success_response(f"Invalid artifact id: {artifact_id}")
        return _unavailable(exc)
    except httpx.HTTPError as exc:
        return _unavailable(exc)
    except Exception as exc:
        return format_error_response(exc, "loading starlark artifact")

    place_y = y + metadata.get("y_offset", 0) if apply_y_offset else y
    try:
        result = await api_client.place_nbt_structure_bytes(
            nbt_bytes,
            f"{artifact_id}.nbt",
            x,
            place_y,
            z,
            world,
            rotation,
            include_entities,
            True,
        )
    except Exception as exc:
        return format_error_response(exc, "placing starlark structure")

    if not result.get("success"):
        return CallToolResult(
            content=[TextContent(type="text", text=f"Failed to place artifact {artifact_id}: {result}")]
        )

    size = metadata.get("size") or ["?", "?", "?"]
    msg = (
        f"Placed starlark structure {artifact_id} ({size[0]}x{size[1]}x{size[2]}) "
        f"at ({x}, {place_y}, {z}) with rotation {rotation}."
    )
    if apply_y_offset and metadata.get("y_offset"):
        msg += f"\nApplied y_offset {metadata['y_offset']} (requested y was {y})."
    if result.get("build_id"):
        msg += f"\nRecorded as build: {result['build_id']}"
    return format_success_response(msg)


async def handle_get_starlark_docs(
    api_client: MinecraftAPIClient,
    **arguments,
) -> CallToolResult:
    try:
        catalog = await _starlark_client().get_catalog()
    except httpx.HTTPError as exc:
        return _unavailable(exc)
    except Exception as exc:
        return format_error_response(exc, "getting starlark docs")
    return format_success_response(catalog)


async def handle_list_starlark_examples(
    api_client: MinecraftAPIClient,
    **arguments,
) -> CallToolResult:
    try:
        result = await _starlark_client().list_examples()
    except httpx.HTTPError as exc:
        return _unavailable(exc)
    except Exception as exc:
        return format_error_response(exc, "listing starlark examples")

    examples = result.get("examples") or []
    if not examples:
        return format_success_response("No starlark examples available.")
    lines = [f"{len(examples)} example script(s); fetch one with get_starlark_example:"]
    for example in examples:
        summary = f" — {example['summary']}" if example.get("summary") else ""
        lines.append(f"- {example.get('name')}{summary}")
    return format_success_response("\n".join(lines))


async def handle_get_starlark_example(
    api_client: MinecraftAPIClient,
    name: str,
    **arguments,
) -> CallToolResult:
    try:
        source = await _starlark_client().get_example(name)
    except httpx.HTTPStatusError as exc:
        if exc.response.status_code in (400, 404):
            return format_success_response(
                f"Example {name!r} was not found. Use list_starlark_examples for valid names."
            )
        return _unavailable(exc)
    except httpx.HTTPError as exc:
        return _unavailable(exc)
    except Exception as exc:
        return format_error_response(exc, "getting starlark example")
    return format_success_response(source)
