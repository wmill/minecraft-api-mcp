"""Temporary visual annotations; these never reserve space or change terrain."""

from mcp.types import Tool, ToolAnnotations
from .area_locks import BOUNDS_SCHEMA

MARKER_TOOLS = [
    Tool(name="mark_area",
         description="Temporarily highlight an inclusive cuboid with colored particles, a vertical particle column, and an optional floating label. Vanilla clients work without mods. Visual only: no blocks, reservations, chunk loads, or saved entities. Visible within 128 blocks of the center; rendering depends on client settings. Returns marker_id for early removal. Max 16 active markers; each dimension at most 128 blocks.",
         inputSchema={"type": "object", "additionalProperties": False,
                      "properties": {
                          "bounds": BOUNDS_SCHEMA,
                          "label": {"type": "string", "maxLength": 120, "default": ""},
                          "color": {"type": "string", "enum": ["yellow", "red", "green", "blue", "cyan", "purple", "white"], "default": "yellow"},
                          "duration_seconds": {"type": "integer", "minimum": 1, "maximum": 300, "default": 60},
                          "world": {"type": "string", "default": "minecraft:overworld"},
                          "player": {"type": "string", "description": "Show only to this player, who must be online in the marker's world. Omit for all nearby players."},
                      }, "required": ["bounds"]},
         annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=False)),
    Tool(name="remove_area_marker", description="Remove a temporary area marker by marker_id. Safe to repeat or call after expiry. Particles already emitted fade naturally.",
         inputSchema={"type": "object", "additionalProperties": False,
                      "properties": {"marker_id": {"type": "string", "minLength": 1}}, "required": ["marker_id"]},
         annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=False, idempotentHint=True)),
]
