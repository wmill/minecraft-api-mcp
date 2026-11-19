"""check_access: can a player get into a placed structure and reach each floor?"""

import httpx
from mcp.types import CallToolResult, TextContent

from ..client.minecraft_api import MinecraftAPIClient
from ..utils.access_models import AccessResult, Point, TargetResult

_OPTIONAL = ("targets", "map", "world", "profile", "doors", "max_nodes", "margin", "include_route")


def _p(point: Point) -> str:
    return f"({point.x},{point.y},{point.z})"


def _target_line(t: TargetResult) -> str:
    where = f" at {_p(t.position)}" if t.position else ""
    if t.adjusted:
        where += f" (adjusted from {_p(t.requested)})"
    if t.status == "reachable":
        via = f" via {_p(t.via)}" if t.via else ""
        return f"- {t.name}{where}: reachable, {t.route_length} moves{via}"
    line = f"- {t.name}{where}: {t.status.upper()} ({t.reason})"
    if t.nearest_reached:
        line += f"; nearest reached {_p(t.nearest_reached.position)}, {t.nearest_reached.distance} blocks away"
    if t.suggestion:
        line += f"; nearest standable position {_p(t.suggestion)}"
    return line


def summarize(result: AccessResult) -> str:
    lines = [f"Access check ({result.profile}, doors={result.doors}) in {result.world}:"]
    start = result.start
    if start and not start.valid:
        hint = f"; try {_p(start.suggestion)}" if start.suggestion else ""
        lines.append(f"Start {_p(start.requested)} is not a standable feet position{hint}.")
    elif start and start.adjusted:
        lines.append(f"Start adjusted from {_p(start.requested)} to {_p(start.position)}.")
    lines += [_target_line(t) for t in result.targets or []]
    m = result.map
    if m and m.floors is not None:
        lines.append(f"Map: {m.reachable}/{m.standable} standing positions reachable"
                     + ("" if m.complete else " (search hit max_nodes; map incomplete)") + ".")
        for level in m.floors:
            if level.standable >= 4 or level.reachable < level.standable:
                indoor = level.standable - level.outdoor
                lines.append(f"  y{level.y}: {level.reachable}/{level.standable} reachable ({level.fraction:.0%})"
                             + (f", {indoor} indoor" if level.outdoor else ""))
        regions = m.unreached_regions or []
        indoor = [r for r in regions if not r.outdoor]
        if indoor:
            lines.append(f"Unreached indoor areas ({len(indoor)} shown of {m.unreached_region_count} groups):")
            for r in indoor:
                b = r.bounds
                lines.append(f"  {r.cells} cells x{b['min_x']}..{b['max_x']} y{b['min_y']}..{b['max_y']} "
                             f"z{b['min_z']}..{b['max_z']}, e.g. {_p(r.sample)}")
        outdoor = [r for r in regions if r.outdoor]
        if outdoor:
            lines.append(f"{sum(r.cells for r in outdoor)} unreached outdoor cells (roofs, wall tops) in {len(outdoor)} groups.")
    elif m:
        lines.append(f"Map: {m.status} ({m.reason}).")
    if result.search and result.search.node_limit_hit:
        lines.append(f"Search stopped at max_nodes={result.search.max_nodes}.")
    lines.append(result.limitations or "")
    return "\n".join(lines).strip()


async def handle_check_access(api_client: MinecraftAPIClient, start: dict, **arguments) -> CallToolResult:
    payload = {"start": start}
    payload.update({key: arguments[key] for key in _OPTIONAL if arguments.get(key) is not None})
    try:
        body = await api_client.check_access(payload)
    except httpx.HTTPStatusError as exc:
        try:
            body = exc.response.json()
            if not isinstance(body, dict) or "error" not in body:
                raise ValueError("Not an access error")
            body = {**body, "success": False}
        except ValueError:
            body = {"success": False, "code": "http_error", "error": f"Access check HTTP error {exc.response.status_code}"}
    except httpx.HTTPError as exc:
        body = {"success": False, "code": "unavailable", "error": f"Minecraft API unavailable: {exc}"}

    try:
        result = AccessResult.model_validate(body)
        if result.success:
            text = summarize(result)
        else:
            text = f"Access check failed ({result.code}): {result.error}"
            if result.missing_chunks:
                text += f"\n{len(result.missing_chunks)} chunks are not loaded; coordinates are in missing_chunks."
    except (ValueError, TypeError, AttributeError, KeyError):
        result = AccessResult(success=False, code="invalid_response", error="Minecraft API returned an invalid access response")
        text = result.error
    return CallToolResult(content=[TextContent(type="text", text=text)],
                          structuredContent=result.model_dump(exclude_none=True), isError=not result.success)
