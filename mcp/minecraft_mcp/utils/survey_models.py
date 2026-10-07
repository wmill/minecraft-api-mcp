"""Structured, bounded site-survey responses shared by the handler and schema."""

from typing import Literal

from pydantic import BaseModel


class Footprint(BaseModel):
    min_x: int
    min_z: int
    max_x: int
    max_z: int


class Coverage(BaseModel):
    columns: int
    fraction: float


class Ground(BaseModel):
    resolved_columns: int
    unresolved_columns: int
    unresolved_reasons: dict[str, int]
    min_y: int | None
    max_y: int | None
    median_y: int | None
    range: int | None


class Slope(BaseModel):
    adjacent_pairs: int
    mean_step: float
    max_step: int


class Grading(BaseModel):
    suggested_walking_y: int | None
    cut_blocks: int | None
    fill_blocks: int | None
    unavailable_reason: str | None


class Reservation(BaseModel):
    label: str
    bounds: dict[str, int]
    expires_at: str


class Build(BaseModel):
    build_id: str
    name: str
    status: str


class ReservationOverlaps(BaseModel):
    status: Literal["available"]
    total: int
    truncated: bool
    entries: list[Reservation]


class BuildOverlaps(BaseModel):
    status: Literal["available", "unavailable"]
    total: int | None
    truncated: bool
    entries: list[Build]


class SurveyResult(BaseModel):
    success: bool
    world: str | None = None
    surveyed_at: str | None = None
    bounds: Footprint | None = None
    size: dict[str, int] | None = None
    ground: Ground | None = None
    slope: Slope | None = None
    water: Coverage | None = None
    lava: Coverage | None = None
    vegetation: Coverage | None = None
    grading: Grading | None = None
    reservations: ReservationOverlaps | None = None
    builds: BuildOverlaps | None = None
    limitations: str | None = None
    code: str | None = None
    error: str | None = None
    missing_chunks: list[dict[str, int]] | None = None
