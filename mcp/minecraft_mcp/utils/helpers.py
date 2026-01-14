"""
Helper utility functions for the Minecraft MCP server.
"""

import httpx


def yaw_to_cardinal(yaw: float) -> str:
    """
    Convert a yaw angle to a cardinal direction.
    
    Yaw angles in Minecraft:
    - 0° → South (positive Z)
    - 90° → West (negative X)
    - 180° or -180° → North (negative Z)
    - -90° → East (positive X)
    
    Args:
        yaw: The yaw angle in degrees
        
    Returns:
        Cardinal direction as a string: "NORTH", "SOUTH", "EAST", or "WEST"
    """
    # Normalize to [-180, 180)
    yaw = ((yaw + 180) % 360) - 180

    if -45 <= yaw < 45:
        return "SOUTH"
    elif 45 <= yaw < 135:
        return "WEST"
    elif yaw >= 135 or yaw < -135:
        return "NORTH"
    else:  # -135 <= yaw < -45
        return "EAST"


def safe_url(url: str) -> str:
    """
    Return a URL string with password redacted for safe logging.
    
    Args:
        url: The URL string to sanitize
        
    Returns:
        URL string with password replaced by "****"
    """
    return str(httpx.URL(url).copy_with(password="****"))
