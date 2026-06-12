"""HTTP API for searching and serving converted schematic NBT files."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import FileResponse

from .catalog import load_catalog
from .config import ServiceConfig, load_config
from .search import SchematicSearchIndex, SearchUnavailable, local_search


def create_app(config: ServiceConfig | None = None) -> FastAPI:
    cfg = config or load_config()
    app = FastAPI(title="Minecraft Schematic Service")
    search_index = SchematicSearchIndex(cfg.elasticsearch_url, cfg.index_name)

    def catalog_docs() -> list[dict[str, Any]]:
        try:
            return load_catalog(cfg.catalog_path, cfg.nbt_dir, cfg.images_dir)
        except FileNotFoundError as exc:
            raise HTTPException(status_code=503, detail=f"catalog data unavailable: {exc}") from exc
        except ValueError as exc:
            raise HTTPException(status_code=500, detail=str(exc)) from exc

    @app.get("/health")
    async def health() -> dict[str, Any]:
        es_health = await search_index.health()
        catalog_exists = cfg.catalog_path.exists()
        nbt_dir_exists = cfg.nbt_dir.exists()
        return {
            "ok": catalog_exists and nbt_dir_exists,
            "catalog_exists": catalog_exists,
            "nbt_dir_exists": nbt_dir_exists,
            "elasticsearch": es_health,
        }

    @app.post("/index/rebuild")
    async def rebuild_index() -> dict[str, Any]:
        docs = catalog_docs()
        try:
            result = await search_index.rebuild(docs)
            return {"success": True, **result}
        except SearchUnavailable as exc:
            raise HTTPException(status_code=503, detail=f"elasticsearch unavailable: {exc}") from exc

    @app.get("/schematics/search")
    async def search_schematics(
        q: str = "",
        limit: int = Query(10, ge=1, le=50),
        structure_type: str | None = None,
        style: str | None = None,
        size_category: str | None = None,
        has_interior: bool | None = None,
        placeable: bool | None = True,
        fallback: bool = True,
    ) -> dict[str, Any]:
        filters = {
            "structure_type": structure_type,
            "style": style,
            "size_category": size_category,
            "has_interior": has_interior,
            "placeable": placeable,
        }
        try:
            results = await search_index.search(q, limit, filters)
            source = "elasticsearch"
        except SearchUnavailable as exc:
            if not fallback:
                raise HTTPException(status_code=503, detail=f"elasticsearch unavailable: {exc}") from exc
            results = local_search(catalog_docs(), q, limit, filters)
            source = "local"
        return {"results": results, "count": len(results), "source": source}

    @app.get("/schematics/{schematic_id}")
    async def get_schematic(schematic_id: str) -> dict[str, Any]:
        for doc in catalog_docs():
            if doc["schematic_id"] == schematic_id:
                return doc
        raise HTTPException(status_code=404, detail="schematic not found")

    @app.get("/schematics/{schematic_id}/nbt")
    async def get_schematic_nbt(schematic_id: str) -> FileResponse:
        path = safe_nbt_path(cfg.nbt_dir, schematic_id)
        if not path.exists():
            raise HTTPException(status_code=404, detail="converted NBT file not found")
        return FileResponse(path, media_type="application/octet-stream", filename=path.name)

    return app


def safe_nbt_path(nbt_dir: Path, schematic_id: str) -> Path:
    if not schematic_id.isdigit():
        raise HTTPException(status_code=400, detail="schematic_id must be numeric")
    path = (nbt_dir / f"{schematic_id}.nbt").resolve()
    root = nbt_dir.resolve()
    if root not in path.parents:
        raise HTTPException(status_code=400, detail="invalid schematic_id")
    return path


app = create_app()
