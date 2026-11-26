"""Read-only web viewer for the spy spool directory written by ``minecraft_mcp.spy``."""

from __future__ import annotations

import asyncio
import json
import re
from pathlib import Path
from typing import Any

from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import FileResponse, HTMLResponse, JSONResponse, Response, StreamingResponse
from starlette.routing import Route

EVENT_NAME = re.compile(r"^\d{13}-\d+-\d+-(start|end)\.json$")
IMAGE_NAME = re.compile(r"^[\w.-]+\.(png|jpg|webp|gif)$")
POLL_SECONDS = 0.4
KEEPALIVE_SECONDS = 15.0
INDEX_HTML = Path(__file__).with_name("index.html")


class SpoolReader:
    """Reads call events from the spool directory."""

    def __init__(self, directory: str | Path):
        self.dir = Path(directory)

    def event_names(self) -> list[str]:
        try:
            return sorted(e.name for e in self.dir.iterdir() if EVENT_NAME.match(e.name))
        except FileNotFoundError:
            return []

    def read(self, name: str) -> dict[str, Any] | None:
        try:
            return json.loads((self.dir / name).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None

    def recent(self, limit: int = 60) -> list[dict[str, Any]]:
        """The newest ``limit`` calls, oldest first, with their end event merged in."""
        names = self.event_names()
        starts = [n for n in names if n.endswith("-start.json")][-limit:]
        ends = set(n for n in names if n.endswith("-end.json"))
        calls = []
        for name in starts:
            start = self.read(name)
            if start is None:
                continue
            end_name = name.replace("-start.json", "-end.json")
            start["end"] = self.read(end_name) if end_name in ends else None
            calls.append(start)
        return calls

    def new_events(self, seen: set[str]) -> list[dict[str, Any]]:
        """Events whose files are not in ``seen`` (updated in place), in file order."""
        names = self.event_names()
        current = set(names)
        fresh = [n for n in names if n not in seen]
        seen.intersection_update(current)
        events = []
        for name in fresh:
            event = self.read(name)
            if event is not None:
                seen.add(name)
                events.append(event)
        return events

    def image_path(self, name: str) -> Path | None:
        if not IMAGE_NAME.match(name):
            return None
        root = self.dir.resolve()
        path = (root / name).resolve()
        if path.parent != root or not path.is_file():
            return None
        return path


def _sse(payload: dict[str, Any]) -> str:
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"


def create_app(directory: str | Path) -> Starlette:
    reader = SpoolReader(directory)

    async def index(request: Request) -> Response:
        return HTMLResponse(INDEX_HTML.read_text(encoding="utf-8"))

    async def events(request: Request) -> Response:
        try:
            limit = max(1, min(int(request.query_params.get("limit", "60")), 500))
        except ValueError:
            limit = 60
        return JSONResponse(reader.recent(limit))

    async def stream(request: Request) -> Response:
        # Set by the CLI to uvicorn's should_exit, so open streams end on ^C instead of
        # holding graceful shutdown open forever.
        should_stop = getattr(request.app.state, "should_stop", None) or (lambda: False)

        async def generate():
            seen = set(reader.event_names())
            yield _sse({"type": "snapshot", "calls": reader.recent()})
            idle = 0.0
            while not should_stop() and not await request.is_disconnected():
                batch = reader.new_events(seen)
                for event in batch:
                    yield _sse(event)
                if batch:
                    idle = 0.0
                else:
                    idle += POLL_SECONDS
                    if idle >= KEEPALIVE_SECONDS:
                        idle = 0.0
                        yield ": keepalive\n\n"
                await asyncio.sleep(POLL_SECONDS)

        return StreamingResponse(generate(), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache"})

    async def files(request: Request) -> Response:
        path = reader.image_path(request.path_params["name"])
        if path is None:
            return JSONResponse({"error": "not found"}, status_code=404)
        return FileResponse(path, headers={"Cache-Control": "max-age=3600"})

    return Starlette(routes=[
        Route("/", index),
        Route("/api/events", events),
        Route("/api/stream", stream),
        Route("/files/{name}", files),
    ])
