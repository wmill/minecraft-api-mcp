"""Compact terrain survey with structured errors and no raw grid."""

import httpx
from mcp.types import CallToolResult, TextContent

from ..client.minecraft_api import MinecraftAPIClient
from ..utils.survey_models import SurveyResult


async def handle_survey_site(api_client: MinecraftAPIClient, x1: int, z1: int,
                             x2: int, z2: int, world: str | None = None, **arguments) -> CallToolResult:
    try:
        payload = await api_client.survey_site(x1, z1, x2, z2, world)
    except httpx.HTTPStatusError as exc:
        try:
            payload = exc.response.json()
            if not isinstance(payload, dict) or "error" not in payload:
                raise ValueError("Not a survey error")
            payload = {**payload, "success": False}
        except ValueError:
            payload = {"success": False, "code": "http_error", "error": f"Survey HTTP error {exc.response.status_code}"}
    except httpx.HTTPError as exc:
        payload = {"success": False, "code": "unavailable", "error": f"Survey service unavailable: {exc}"}

    try:
        result = SurveyResult.model_validate(payload)
        if result.success:
            g, s, grade = result.ground, result.slope, result.grading
            text = (f"Survey {result.size['x']}x{result.size['z']} in {result.world}: "
                    f"ground walking Y {g.min_y}..{g.max_y}, median {g.median_y}; "
                    f"{g.unresolved_columns} unresolved columns. "
                    f"Adjacent slope mean {s.mean_step:.2f}, max {s.max_step} blocks/block.\n"
                    f"Water {result.water.fraction:.1%}, lava {result.lava.fraction:.1%}, "
                    f"vegetation evidence {result.vegetation.fraction:.1%}.\n")
            if grade.suggested_walking_y is None:
                text += f"No suggested Y: {grade.unavailable_reason}.\n"
            else:
                text += (f"Suggested walking-plane Y {grade.suggested_walking_y}; "
                         f"estimated cut {grade.cut_blocks}, fill {grade.fill_blocks} blocks.\n")
            builds = str(result.builds.total) if result.builds.status == "available" else "unavailable"
            text += f"Overlaps: {result.reservations.total} reservations; recorded builds: {builds}.\n{result.limitations}"
        else:
            text = f"Survey failed ({result.code}): {result.error}"
            if result.missing_chunks:
                text += f"\n{len(result.missing_chunks)} chunks are not loaded; coordinates are in missing_chunks."
    except (ValueError, TypeError, AttributeError, KeyError):
        result = SurveyResult(success=False, code="invalid_response", error="Minecraft API returned an invalid survey response")
        text = result.error
    return CallToolResult(content=[TextContent(type="text", text=text)],
                          structuredContent=result.model_dump(exclude_unset=True), isError=not result.success)
