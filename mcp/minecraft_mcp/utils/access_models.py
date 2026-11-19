"""Structured responses of the access check (POST /api/world/access/check)."""

from typing import Literal

from pydantic import BaseModel


class Point(BaseModel):
    x: int
    y: int
    z: int


class Nearest(BaseModel):
    position: Point
    distance: float


class StartResult(BaseModel):
    requested: Point
    valid: bool
    position: Point | None = None
    adjusted: bool | None = None
    reason: str | None = None
    suggestion: Point | None = None


class TargetResult(BaseModel):
    name: str
    requested: Point
    status: Literal["reachable", "unreachable", "unknown", "invalid_target"]
    position: Point | None = None
    adjusted: bool | None = None
    via: Point | None = None
    reason: str | None = None
    search_result: str | None = None
    route_length: int | None = None
    via_crossed: bool | None = None
    route_truncated: bool | None = None
    route: list[Point] | None = None
    nearest_reached: Nearest | None = None
    suggestion: Point | None = None


class Level(BaseModel):
    y: int
    standable: int
    reachable: int
    outdoor: int
    fraction: float


class UnreachedRegion(BaseModel):
    cells: int
    outdoor: bool
    bounds: dict[str, int]
    sample: Point


class AccessMap(BaseModel):
    region: dict[str, int] | None = None
    standable: int | None = None
    reachable: int | None = None
    complete: bool | None = None
    floors: list[Level] | None = None
    floors_truncated: bool | None = None
    unreached_region_count: int | None = None
    unreached_regions: list[UnreachedRegion] | None = None
    status: str | None = None
    reason: str | None = None


class SearchStats(BaseModel):
    box: dict[str, int]
    max_nodes: int
    expanded_nodes: int | None = None
    reached_positions: int | None = None
    node_limit_hit: bool | None = None
    touched_box_edge: bool | None = None
    touched_unloaded_chunks: bool | None = None


class AccessResult(BaseModel):
    success: bool
    world: str | None = None
    profile: str | None = None
    doors: str | None = None
    start: StartResult | None = None
    targets: list[TargetResult] | None = None
    map: AccessMap | None = None
    search: SearchStats | None = None
    limitations: str | None = None
    code: str | None = None
    error: str | None = None
    missing_chunks: list[dict[str, int]] | None = None
