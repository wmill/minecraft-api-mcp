"""
Area-effect tool handlers for the Minecraft MCP server.

Handles one-shot world effects that aren't single-block prefabs, such as
raining down fires across a circular area.
"""

from typing import Optional
from mcp.types import CallToolResult

from ..client.minecraft_api import MinecraftAPIClient
from ..utils.formatting import (
    format_success_response,
    format_error_response,
    format_validation_error,
    format_api_error,
)


async def handle_rain_fire(
    api_client: MinecraftAPIClient,
    x: int,
    z: int,
    radius: int,
    density: float,
    seed: Optional[int] = None,
    world: Optional[str] = None,
    **arguments,
) -> CallToolResult:
    """
    Rain down random fires on the WORLD_SURFACE across a circular area.

    Useful for clearing trees from a construction site — fire spreads to adjacent
    leaves and logs and burns them away. Columns over water, lava, air, or existing
    fire are skipped.

    Args:
        api_client: The Minecraft API client
        x: Center X coordinate
        z: Center Z coordinate
        radius: Circle radius in blocks (1-56)
        density: Per-column probability of placing a fire (0.0-1.0)
        seed: Optional random seed for reproducible patterns
        world: World name (optional, defaults to minecraft:overworld)
        **arguments: Additional arguments (ignored)

    Returns:
        CallToolResult with placement summary
    """
    try:
        if radius <= 0 or radius > 56:
            return format_validation_error("radius must be between 1 and 56")
        if density < 0.0 or density > 1.0:
            return format_validation_error("density must be between 0.0 and 1.0")

        result = await api_client.rain_fire(x, z, radius, density, seed=seed, world=world)

        if result.get("success"):
            fires = result.get("fires_placed", 0)
            columns = result.get("columns_considered", 0)
            text = (
                f"Rained down {fires} fires across {columns} columns "
                f"at ({x}, {z}) within radius {radius} (density {density})"
            )
            return format_success_response(text)
        else:
            return format_api_error(result, "rain fire")
    except Exception as e:
        return format_error_response(e, "raining fire")
