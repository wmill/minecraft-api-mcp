"""Client for the optional schematic catalog service."""

from __future__ import annotations

from typing import Any, Optional

import httpx


class SchematicServiceClient:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")

    async def get_schematic_tags(self, limit: int = 20) -> dict[str, Any]:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.get(
                f"{self.base_url}/schematics/tags",
                params={"limit": limit, "placeable": True},
            )
            response.raise_for_status()
            return response.json()

    async def search_schematics(
        self,
        query: str,
        limit: int = 10,
        structure_type: Optional[str] = None,
        style: Optional[str] = None,
        size_category: Optional[str] = None,
        has_interior: Optional[bool] = None,
    ) -> dict[str, Any]:
        params: dict[str, Any] = {
            "q": query,
            "limit": limit,
            "placeable": True,
        }
        if structure_type:
            params["structure_type"] = structure_type
        if style:
            params["style"] = style
        if size_category:
            params["size_category"] = size_category
        if has_interior is not None:
            params["has_interior"] = has_interior

        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.get(f"{self.base_url}/schematics/search", params=params)
            response.raise_for_status()
            return response.json()

    async def get_schematic(self, schematic_id: str) -> dict[str, Any]:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.get(f"{self.base_url}/schematics/{schematic_id}")
            response.raise_for_status()
            return response.json()

    async def get_schematic_nbt(self, schematic_id: str) -> bytes:
        async with httpx.AsyncClient(timeout=30.0) as client:
            response = await client.get(f"{self.base_url}/schematics/{schematic_id}/nbt")
            response.raise_for_status()
            return response.content
