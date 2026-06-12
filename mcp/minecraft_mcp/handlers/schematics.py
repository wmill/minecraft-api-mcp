"""Handlers for the optional schematic catalog service."""

from __future__ import annotations

from typing import Optional

import httpx
from mcp.types import CallToolResult, TextContent

from ..client.minecraft_api import MinecraftAPIClient
from ..client.schematic_service import SchematicServiceClient
from ..config import SCHEMATIC_SERVICE_URL
from ..utils.formatting import format_error_response, format_success_response


def _schematic_client() -> SchematicServiceClient:
    return SchematicServiceClient(SCHEMATIC_SERVICE_URL)


def _unavailable(error: Exception) -> CallToolResult:
    return CallToolResult(
        content=[
            TextContent(
                type="text",
                text=(
                    "Schematic service is unavailable. Start it with the optional "
                    f"schematics compose profile or set SCHEMATIC_SERVICE_URL. Error: {error}"
                ),
            )
        ]
    )


async def handle_search_schematics(
    api_client: MinecraftAPIClient,
    query: str,
    limit: int = 10,
    structure_type: Optional[str] = None,
    style: Optional[str] = None,
    size_category: Optional[str] = None,
    has_interior: Optional[bool] = None,
    **arguments,
) -> CallToolResult:
    try:
        result = await _schematic_client().search_schematics(
            query=query,
            limit=limit,
            structure_type=structure_type,
            style=style,
            size_category=size_category,
            has_interior=has_interior,
        )
    except httpx.HTTPError as exc:
        return _unavailable(exc)
    except Exception as exc:
        return format_error_response(exc, "searching schematics")

    rows = result.get("results", [])
    if not rows:
        return format_success_response("No matching placeable schematics found.")

    lines = [f"Found {len(rows)} placeable schematic(s) via {result.get('source', 'service')}:"]
    for row in rows:
        size = row.get("size") or {}
        size_text = "x".join(str(size.get(axis, "?")) for axis in ("width", "height", "depth"))
        lines.append(
            "- {id}: {title} [{kind}, {style}, {size}] blocks={blocks}".format(
                id=row.get("schematic_id"),
                title=row.get("title", "Untitled"),
                kind=row.get("structure_type", "unknown"),
                style=row.get("style", "unknown"),
                size=size_text,
                blocks=row.get("non_air_block_count", "?"),
            )
        )
    return format_success_response("\n".join(lines))


async def handle_get_schematic(
    api_client: MinecraftAPIClient,
    schematic_id: str,
    **arguments,
) -> CallToolResult:
    try:
        row = await _schematic_client().get_schematic(str(schematic_id))
    except httpx.HTTPStatusError as exc:
        if exc.response.status_code == 404:
            return format_success_response(f"Schematic {schematic_id} was not found.")
        return _unavailable(exc)
    except httpx.HTTPError as exc:
        return _unavailable(exc)
    except Exception as exc:
        return format_error_response(exc, "getting schematic metadata")

    size = row.get("size") or {}
    top_blocks = (row.get("image_metadata") or {}).get("top_blocks") or []
    top_block_text = ", ".join(
        f"{block.get('name')} ({block.get('fraction', 0):.0%})" for block in top_blocks[:5]
    )
    lines = [
        f"{row.get('schematic_id')}: {row.get('title')}",
        row.get("description", ""),
        f"Type: {row.get('structure_type')} | Style: {row.get('style')} | Size: {size}",
        f"Placeable: {row.get('placeable')} | Blocks: {row.get('non_air_block_count')}",
    ]
    if top_block_text:
        lines.append(f"Top blocks: {top_block_text}")
    return format_success_response("\n".join(line for line in lines if line))


async def handle_place_schematic(
    api_client: MinecraftAPIClient,
    schematic_id: str,
    x: int,
    y: int,
    z: int,
    world: Optional[str] = None,
    rotation: str = "NONE",
    include_entities: bool = True,
    replace_blocks: bool = True,
    **arguments,
) -> CallToolResult:
    client = _schematic_client()
    try:
        metadata = await client.get_schematic(str(schematic_id))
        nbt_bytes = await client.get_schematic_nbt(str(schematic_id))
    except httpx.HTTPStatusError as exc:
        if exc.response.status_code == 404:
            return format_success_response(f"Schematic {schematic_id} is not available as converted NBT.")
        return _unavailable(exc)
    except httpx.HTTPError as exc:
        return _unavailable(exc)
    except Exception as exc:
        return format_error_response(exc, "loading schematic NBT")

    try:
        result = await api_client.place_nbt_structure_bytes(
            nbt_bytes,
            f"{schematic_id}.nbt",
            x,
            y,
            z,
            world,
            rotation,
            include_entities,
            replace_blocks,
        )
    except Exception as exc:
        return format_error_response(exc, "placing schematic")

    if not result.get("success"):
        return CallToolResult(
            content=[TextContent(type="text", text=f"Failed to place schematic {schematic_id}: {result}")]
        )

    title = metadata.get("title", f"Schematic {schematic_id}")
    return format_success_response(
        f"Placed schematic {schematic_id} ({title}) at ({x}, {y}, {z}) with rotation {rotation}."
    )
