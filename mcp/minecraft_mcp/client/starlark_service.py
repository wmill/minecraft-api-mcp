"""Client for the optional Starlark build service."""

from __future__ import annotations

from typing import Any, Optional

import httpx


class StarlarkServiceClient:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")

    async def build(
        self,
        source: str,
        entry: str = "build",
        props: Optional[dict[str, Any]] = None,
        root_size: Optional[list[int]] = None,
    ) -> dict[str, Any]:
        body: dict[str, Any] = {"source": source, "entry": entry, "props": props or {}}
        if root_size is not None:
            body["root_size"] = root_size
        # Must exceed the service's own build timeout so timeouts surface as
        # structured diagnostics, not client-side aborts.
        async with httpx.AsyncClient(timeout=60.0) as client:
            response = await client.post(f"{self.base_url}/build", json=body)
            response.raise_for_status()
            return response.json()

    async def get_artifact(self, artifact_id: str) -> dict[str, Any]:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.get(f"{self.base_url}/artifacts/{artifact_id}")
            response.raise_for_status()
            return response.json()

    async def get_artifact_nbt(self, artifact_id: str) -> bytes:
        async with httpx.AsyncClient(timeout=30.0) as client:
            response = await client.get(f"{self.base_url}/artifacts/{artifact_id}/nbt")
            response.raise_for_status()
            return response.content

    async def get_catalog(self) -> str:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.get(f"{self.base_url}/docs/catalog")
            response.raise_for_status()
            return response.text

    async def list_examples(self) -> dict[str, Any]:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.get(f"{self.base_url}/examples")
            response.raise_for_status()
            return response.json()

    async def get_example(self, name: str) -> str:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.get(f"{self.base_url}/examples/{name}")
            response.raise_for_status()
            return response.text
