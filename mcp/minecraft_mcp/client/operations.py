"""Process-local edit tracking. Unknown outcomes are never automatically resubmitted."""

import asyncio
import time
import uuid
from urllib.parse import quote, urlsplit

import httpx
from mcp.types import CallToolResult, TextContent

POLL_SECONDS = 1.0
POLL_DEADLINE_SECONDS = 120.0


class PendingOperation(Exception):
    def __init__(self, operation_id: str, status_url: str):
        self.operation_id = operation_id
        self.status_url = status_url
        super().__init__(f"Operation {operation_id} is pending; use get_operation to check its outcome.")


def pending_result(pending: PendingOperation) -> CallToolResult:
    return CallToolResult(
        content=[TextContent(type="text", text=str(pending))],
        structuredContent={"status": "pending", "operation_id": pending.operation_id,
                           "status_url": pending.status_url,
                           "instructions": "Call get_operation with this operation_id. Do not resubmit the edit."},
    )


def tracked_path(url: str) -> bool:
    path = urlsplit(url).path
    return (path in {"/api/world/blocks/set", "/api/world/blocks/fill", "/api/world/structure/place",
                     "/api/world/terrain/smooth", "/api/world/entities/spawn", "/api/players/teleport",
                     "/api/world/effects/rain-fire"}
            or path.startswith(("/api/world/prefabs/", "/api/message/", "/api/world/markers", "/api/area-locks"))
            or (path.startswith("/api/builds/") and path.endswith(("/undo", "/redo"))))


class OperationClient:
    """Adds retries and polling to mutation requests while preserving the HTTP interface."""
    def __init__(self, client, base_url):
        self.client = client
        self.base_url = base_url.rstrip("/")

    async def __aenter__(self):
        await self.client.__aenter__()
        return self

    async def __aexit__(self, *args):
        return await self.client.__aexit__(*args)

    def __getattr__(self, name):
        return getattr(self.client, name)

    async def post(self, url, **kwargs):
        return await self._mutation("POST", url, **kwargs)

    async def patch(self, url, **kwargs):
        return await self._mutation("PATCH", url, **kwargs)

    async def delete(self, url, **kwargs):
        return await self._mutation("DELETE", url, **kwargs)

    async def _mutation(self, method, url, **kwargs):
        if not tracked_path(url):
            return await getattr(self.client, method.lower())(url, **kwargs)
        headers = dict(kwargs.pop("headers", {}))
        headers.setdefault("Idempotency-Key", str(uuid.uuid4()))
        # The same logical edit keeps the same key after a lost response.
        for attempt in range(2):
            try:
                response = await getattr(self.client, method.lower())(url, headers=headers, **kwargs)
                break
            except httpx.TransportError:
                if attempt:
                    raise
        if response.status_code != 202:
            return response
        pending = response.json()
        if "operation_id" not in pending:
            return response
        operation_id = pending["operation_id"]
        # Construct the URL locally; do not forward credentials to a server-supplied host.
        status_path = f"/api/operations/{quote(operation_id, safe='')}"
        deadline = time.monotonic() + POLL_DEADLINE_SECONDS
        while time.monotonic() < deadline:
            await asyncio.sleep(min(POLL_SECONDS, max(0, deadline - time.monotonic())))
            if time.monotonic() >= deadline:
                break
            try:
                poll = await self.client.get(self.base_url + status_path,
                                             timeout=max(0.001, deadline - time.monotonic()))
            except httpx.TransportError:
                continue
            if poll.status_code == 404:
                raise RuntimeError(f"Operation {operation_id} is unknown or expired; its outcome is unknown. Do not resubmit automatically.")
            poll.raise_for_status()
            result = poll.json()
            if "result_status" in result:
                return httpx.Response(result["result_status"], json=result["result"],
                                      headers={"X-Operation-Id": operation_id}, request=response.request)
        raise PendingOperation(operation_id, status_path)
