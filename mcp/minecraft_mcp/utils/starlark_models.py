"""Shared Starlark input/output contracts for handlers and tool schemas."""

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class Placement(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    x: int
    y: int = Field(description="Desired world walking plane; ground offset is applied automatically.")
    z: int
    world: str = "minecraft:overworld"
    rotation: Literal["NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"] = "NONE"
    include_entities: bool = True
    apply_y_offset: bool = True
    dry_run: bool = Field(default=False, description="Report what placement would overwrite without changing the world.")


class DiagnosticGroup(BaseModel):
    code: str
    message: str
    component_path: str | None = None
    file: str | None = None
    line: int | None = None
    region: dict[str, Any] | None = None
    details: dict[str, Any] = Field(default_factory=dict)
    count: int
    coordinate_samples: list[list[int]] = Field(default_factory=list)
    hint: str | None = None


class PlacementResult(BaseModel):
    status: Literal["placed", "failed", "unknown", "dry_run"]
    requested_position: dict[str, int]
    position: dict[str, int]
    world: str
    rotation: str
    build_id: str | None = None
    undo_available: bool | None = None
    undo_unavailable_reason: str | None = None
    overwrite: dict[str, Any] | None = None
    lock_check: dict[str, Any] | None = None
    reservations: dict[str, Any] | None = None
    builds: dict[str, Any] | None = None


class StarlarkResult(BaseModel):
    ok: bool
    artifact_id: str | None = None
    compilation_ok: bool | None = None
    size: list[int] | None = None
    block_count: int | None = None
    entity_count: int | None = None
    ground_level: int | None = None
    y_offset: int | None = None
    cached: bool | None = None
    build_ms: int | None = None
    nbt_bytes: int | None = None
    palette: list[dict[str, Any]] | None = None
    placement: PlacementResult | None = None
    error_kind: str | None = None
    lock_error: dict[str, Any] | None = None
    message: str | None = None
    hint: str | None = None
    diagnostics: list[DiagnosticGroup] | None = None
    diagnostic_count: int | None = None
    omitted_group_count: int | None = None
