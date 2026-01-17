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

coordinate_info_blurb = """

Coordinate System:
World coordinates are based on a grid where three lines or axes intersect at the origin point.

- The x-axis indicates the player's distance east (positive) or west (negative) of the origin point—i.e., the longitude
- The z-axis indicates the player's distance south (positive) or north (negative) of the origin point—i.e., the latitude  
- The y-axis indicates how high or low (from 0 to 255 (pre 1.18) or -64 to 320 (from 1.18), with 63 being sea level) the player is—i.e., the elevation

The unit length of the three axes equals the side of one block. And, in terms of real-world measurement, one block equals 1 cubic meter.

Rotation System (Yaw and Pitch):
Player and entity rotations are defined by yaw (horizontal) and pitch (vertical) angles:

Yaw (horizontal rotation):
- Yaw = 0° → facing south (positive Z direction)
- Yaw = 90° → facing west (negative X direction)  
- Yaw = 180° or -180° → facing north (negative Z direction)
- Yaw = -90° → facing east (positive X direction)

Pitch (vertical rotation):
- Pitch = 0° → looking straight ahead (horizontal)
- Pitch = 90° → looking straight down
- Pitch = -90° → looking straight up

Warning! Pay attention to the fact that Minecraft coordinates have 0 yaw as south. In most engines it is east or north so don't let that trip you up.
Similarly positive Z is south. 
"""