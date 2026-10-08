"""HTTP API for building Starlark scripts into placeable structure NBT."""

from __future__ import annotations

import asyncio
import re
import time
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, PlainTextResponse
from pydantic import BaseModel, Field

from . import cache, library
from .config import ServiceConfig, load_config
from .docs import UnknownDocs, catalog_view
from .sandbox import BuildQueueFull, Sandbox

EXAMPLE_NAME = re.compile(r"^[a-z0-9_-]+$")


class BuildRequest(BaseModel):
    source: str
    entry: str = "build"
    props: dict[str, Any] = Field(default_factory=dict)
    root_size: list[int] | None = None


class LibrarySaveRequest(BaseModel):
    name: str
    source: str | None = None
    artifact_id: str | None = None
    entry: str | None = None
    props: dict[str, Any] | None = None
    root_size: list[int] | None = None
    title: str | None = None
    description: str | None = None
    tags: list[str] | None = None
    author: str | None = None
    notes: str | None = None
    parent: str | None = None


def create_app(config: ServiceConfig | None = None) -> FastAPI:
    cfg = config or load_config()
    app = FastAPI(title="Minecraft Starlark Build Service")
    sandbox = Sandbox(cfg)
    fingerprint = cache.lib_fingerprint(cfg.tool_dir)
    library_lock = asyncio.Lock()

    @app.get("/health")
    async def health() -> dict[str, Any]:
        lib_dir_exists = cfg.lib_dir.is_dir()
        cfg.cache_dir.mkdir(parents=True, exist_ok=True)
        return {
            "ok": lib_dir_exists,
            "lib_dir_exists": lib_dir_exists,
            "lib_fingerprint": fingerprint,
            "cache": cache.stats(cfg.cache_dir),
            "library_entries": len(library.list_meta(cfg.library_dir)),
        }

    async def run_build(request: BuildRequest) -> dict[str, Any]:
        if len(request.source.encode("utf-8")) > cfg.max_source_bytes:
            raise HTTPException(status_code=400,
                                detail=f"source exceeds {cfg.max_source_bytes} bytes")
        if request.root_size is not None and len(request.root_size) != 3:
            raise HTTPException(status_code=400, detail="root_size must be three integers")

        identifier = cache.artifact_id(request.source, request.entry, request.props,
                                       request.root_size, fingerprint)
        cached = cache.load_metadata(cfg.cache_dir, identifier)
        if cached is not None:
            cache.touch(cfg.cache_dir, identifier)
            return {**cached, "cached": True}

        final_path = cache.nbt_path(cfg.cache_dir, identifier)
        final_path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = final_path.with_name(f".{uuid.uuid4().hex}.nbt.tmp")
        started = time.monotonic()
        try:
            result = await sandbox.build(request.source, request.entry, request.props,
                                         request.root_size, tmp_path)
        except BuildQueueFull as exc:
            raise HTTPException(status_code=429, detail=str(exc)) from exc

        if not result["ok"]:
            tmp_path.unlink(missing_ok=True)
            return result

        tmp_path.replace(final_path)
        metadata = {
            "ok": True,
            "artifact_id": identifier,
            "build_ms": int((time.monotonic() - started) * 1000),
            "entry": request.entry,
            "props": request.props,
            "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            **{key: result[key] for key in
               ("size", "block_count", "entity_count", "ground_level", "y_offset",
                "nbt_bytes", "palette")},
        }
        cache.store_source(cfg.cache_dir, identifier, request.source)
        cache.store_metadata(cfg.cache_dir, identifier, metadata)
        cache.evict(cfg.cache_dir, cfg.cache_max_bytes)
        return {**metadata, "cached": False}

    @app.post("/build")
    async def build(request: BuildRequest) -> dict[str, Any]:
        return await run_build(request)

    @app.post("/library")
    async def save_library(request: LibrarySaveRequest) -> dict[str, Any]:
        with library_errors():
            library.validate_name(request.name)
        if (request.source is None) == (request.artifact_id is None):
            raise HTTPException(status_code=400, detail="provide exactly one of source or artifact_id")
        source, entry, props = request.source, request.entry, request.props
        if request.artifact_id is not None:
            # Saving by artifact id reuses the source, entry, and props it was built with.
            identifier = valid_artifact_id(request.artifact_id)
            path = cache.source_path(cfg.cache_dir, identifier)
            built = cache.load_metadata(cfg.cache_dir, identifier)
            if not path.exists() or built is None:
                raise HTTPException(status_code=404, detail="artifact not found (evicted or never built); save by source instead")
            source = path.read_text(encoding="utf-8")
            entry = entry or built["entry"]
            props = built["props"] if props is None else props

        # Only scripts that build are saved, so every library version is placeable and loadable.
        result = await run_build(BuildRequest(source=source, entry=entry or "build",
                                              props=props or {}, root_size=request.root_size))
        if not result["ok"]:
            return result
        async with library_lock:
            with library_errors():
                meta, record, created = library.save_version(
                    cfg.library_dir, request.name, source, result,
                    title=request.title, description=request.description, tags=request.tags,
                    author=request.author, notes=request.notes, parent=request.parent,
                    root_size=request.root_size,
                )
        return {
            "ok": True,
            "created": created,
            "name": meta["name"],
            "version": record["version"],
            "load_path": library.load_path(meta["name"], record["version"]),
            "artifact_id": record["artifact_id"],
            "exports": record["exports"],
            "params": record["params"],
            "size": record["size"],
        }

    @app.get("/library/search")
    async def search_library(q: str = "", tag: str | None = None, author: str | None = None,
                             max_x: int | None = None, max_y: int | None = None, max_z: int | None = None,
                             limit: int = 10) -> dict[str, Any]:
        results = library.search(library.list_meta(cfg.library_dir), q, tag=tag, author=author,
                                 max_size=(max_x, max_y, max_z), limit=limit)
        return {"results": results, "count": len(results)}

    @app.get("/library/{name}")
    async def get_library_entry(name: str) -> dict[str, Any]:
        with library_errors():
            meta = library.load_meta(cfg.library_dir, name)
        return {**meta, "load_path": library.load_path(meta["name"], meta["latest"])}

    @app.get("/library/{name}/source")
    async def get_library_source(name: str, version: int | None = None) -> PlainTextResponse:
        with library_errors():
            resolved, source = library.read_source(cfg.library_dir, name, version)
        return PlainTextResponse(source, media_type="text/plain",
                                 headers={"X-Library-Version": str(resolved)})

    @app.get("/artifacts/{artifact_id}")
    async def get_artifact(artifact_id: str) -> dict[str, Any]:
        metadata = cache.load_metadata(cfg.cache_dir, valid_artifact_id(artifact_id))
        if metadata is None:
            raise HTTPException(status_code=404, detail="artifact not found (evicted or never built); rebuilding the same source yields the same id")
        return metadata

    @app.get("/artifacts/{artifact_id}/nbt")
    async def get_artifact_nbt(artifact_id: str) -> FileResponse:
        path = cache.nbt_path(cfg.cache_dir, valid_artifact_id(artifact_id))
        if not path.exists():
            raise HTTPException(status_code=404, detail="artifact not found (evicted or never built); rebuilding the same source yields the same id")
        cache.touch(cfg.cache_dir, artifact_id)
        return FileResponse(path, media_type="application/octet-stream", filename=path.name)

    @app.get("/docs/catalog")
    async def get_catalog(topic: str = "quickstart", component: str | None = None) -> PlainTextResponse:
        try:
            content = catalog_view(cfg.tool_dir, topic, component)
        except UnknownDocs as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except (OSError, SyntaxError, KeyError) as exc:
            raise HTTPException(status_code=503, detail="component catalog unavailable") from exc
        return PlainTextResponse(content, media_type="text/markdown")

    @app.get("/examples")
    async def list_examples() -> dict[str, Any]:
        if not cfg.examples_dir.is_dir():
            raise HTTPException(status_code=503, detail="examples unavailable")
        examples = [
            {"name": path.stem, "summary": _summary(path)}
            for path in sorted(cfg.examples_dir.glob("*.star"))
        ]
        return {"examples": examples, "count": len(examples)}

    @app.get("/examples/{name}")
    async def get_example(name: str) -> PlainTextResponse:
        if not EXAMPLE_NAME.fullmatch(name):
            raise HTTPException(status_code=400, detail="invalid example name")
        path = cfg.examples_dir / f"{name}.star"
        if not path.exists():
            raise HTTPException(status_code=404, detail="example not found")
        return PlainTextResponse(path.read_text(encoding="utf-8"), media_type="text/plain")

    return app


@contextmanager
def library_errors() -> Iterator[None]:
    try:
        yield
    except library.LibraryError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except library.LibraryNotFound as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


def valid_artifact_id(artifact_id: str) -> str:
    if not cache.ARTIFACT_ID_PATTERN.fullmatch(artifact_id):
        raise HTTPException(status_code=400, detail="invalid artifact_id")
    return artifact_id


def _summary(path: Path) -> str:
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("#"):
            return stripped.lstrip("# ").strip()
        if stripped:
            break
    return ""


app = create_app()
