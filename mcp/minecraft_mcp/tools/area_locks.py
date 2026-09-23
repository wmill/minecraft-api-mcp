"""Area reservation schemas and the tools that can perform protected writes."""

from mcp.types import Tool, ToolAnnotations

LOCK_WRITE_TOOLS = frozenset({
    "set_blocks", "fill_box", "place_nbt_structure", "place_door_line", "place_stairs",
    "place_window_pane_wall", "place_torch", "place_sign", "place_ladder", "rain_fire",
    "execute_build", "replay_build", "place_schematic", "build_starlark_structure",
    "place_starlark_structure",
})

LOCK_ID_SCHEMA = {
    "type": "string", "minLength": 1,
    "description": "Token returned by acquire_area_lock. Keep placement inside its fixed cuboid; expired tokens fail. Never omit the token to bypass an error.",
}
BOUNDS_SCHEMA = {
    "type": "object",
    "properties": {name: {"type": "integer", "minimum": -2147483648, "maximum": 2147483647}
                   for name in ("min_x", "min_y", "min_z", "max_x", "max_y", "max_z")},
    "required": ["min_x", "min_y", "min_z", "max_x", "max_y", "max_z"],
    "additionalProperties": False,
    "description": "Inclusive block coordinates, with each minimum <= its maximum. Include clearance and support blocks.",
}


def _tool(name, description, properties, required, *, read_only=False):
    return Tool(name=name, description=description,
                inputSchema={"type": "object", "properties": properties,
                             "required": required, "additionalProperties": False},
                annotations=ToolAnnotations(readOnlyHint=read_only, destructiveHint=False))


AREA_LOCK_TOOLS = [
    _tool("acquire_area_lock",
          "Reserve a cuboid before planning/building near a player. Read their location once, then keep the reservation's coordinates even if they move. Pass the returned lock_id on writes. Successful writes renew the default 15-minute idle timeout; explicitly renew during long planning. Conflicts require user-directed coordination, not automatic relocation.",
          {"bounds": BOUNDS_SCHEMA, "world": {"type": "string", "default": "minecraft:overworld"},
           "label": {"type": "string", "description": "Short description of this build."}}, ["bounds"]),
    _tool("update_area_lock",
          "Renew an area lock during planning, or explicitly resize it by supplying replacement bounds. The world stays fixed. A conflicting resize preserves the old reservation. Expired locks cannot be renewed.",
          {"lock_id": LOCK_ID_SCHEMA, "bounds": BOUNDS_SCHEMA}, ["lock_id"]),
    _tool("release_area_lock", "Release a reservation when finished. Safe to repeat; abandoned locks expire automatically.",
          {"lock_id": LOCK_ID_SCHEMA}, ["lock_id"]),
    _tool("list_area_locks", "Inspect active reservations and their expiry, optionally intersecting a cuboid. Does not renew locks or disclose their tokens.",
          {"world": {"type": "string"}, "bounds": BOUNDS_SCHEMA}, [], read_only=True),
]
