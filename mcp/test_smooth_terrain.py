#!/usr/bin/env python3

import unittest
from unittest.mock import AsyncMock

from mcp.types import ImageContent, TextContent

from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
from minecraft_mcp.handlers.terrain import handle_smooth_terrain
from minecraft_mcp.tools.area_locks import LOCK_WRITE_TOOLS
from minecraft_mcp.tools.registry import TOOL_HANDLERS
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS


def smooth_response(**overrides):
    body = {
        "status_code": 200,
        "success": True,
        "dry_run": True,
        "world": "minecraft:overworld",
        "region": {"min_x": 100, "min_z": 200, "max_x": 159, "max_z": 259},
        "size": {"x": 60, "z": 60},
        "method": "bilateral",
        "summary": {
            "columns": 3600, "changed_columns": 1200, "masked_columns": 36,
            "masked_by_kind": {"built": 30, "fluid": 6}, "protected_columns": 40, "clamped_columns": 0,
            "cut_blocks": 900, "fill_blocks": 850, "max_lowered": 4, "max_raised": 3, "vegetation_columns": 210,
        },
        "ground_y": {"before": {"min": 60, "max": 75}, "after": {"min": 62, "max": 72}},
        "lock_check": {"ok": True},
        "builds": {"status": "available", "total": 0},
        "undo_snapshot": True,
        "px_per_block": 12,
        "contour_interval": 2,
        "grid_step": 8,
        "labels": True,
        "before_png": "QkVGT1JF",
        "after_png": "QUZURVI=",
        "change_png": "Q0hBTkdF",
    }
    body.update(overrides)
    return body


class SmoothTerrainTests(unittest.IsolatedAsyncioTestCase):
    def test_registered_as_lockable_write_tool(self):
        schema = next(tool for tool in TOOL_SCHEMAS if tool.name == "smooth_terrain")
        self.assertIn("smooth_terrain", TOOL_HANDLERS)
        self.assertIn("smooth_terrain", LOCK_WRITE_TOOLS)
        self.assertIn("lock_id", schema.inputSchema["properties"])
        self.assertEqual(["x1", "z1", "x2", "z2", "method"], schema.inputSchema["required"])
        self.assertTrue(schema.annotations.destructiveHint)

    async def test_dry_run_returns_three_images_then_summary(self):
        api_client = AsyncMock(spec=MinecraftAPIClient)
        api_client.smooth_terrain.return_value = smooth_response()

        result = await handle_smooth_terrain(api_client, x1=100, z1=200, x2=159, z2=259,
                                             method="bilateral", sigma=2.5, dry_run=True)

        api_client.smooth_terrain.assert_awaited_once_with(
            {"x1": 100, "z1": 200, "x2": 159, "z2": 259, "method": "bilateral", "sigma": 2.5, "dry_run": True})
        self.assertEqual(4, len(result.content))
        self.assertEqual(["QkVGT1JF", "QUZURVI=", "Q0hBTkdF"], [c.data for c in result.content[:3]])
        self.assertTrue(all(isinstance(c, ImageContent) for c in result.content[:3]))
        text = result.content[3]
        self.assertIsInstance(text, TextContent)
        self.assertIn("dry run", text.text)
        self.assertIn("X 100..159, Z 200..259", text.text)
        self.assertIn("cut 900 blocks (max 4 down), fill 850 blocks (max 3 up)", text.text)
        self.assertIn("built 30, fluid 6", text.text)
        self.assertIn("Area lock check: ok.", text.text)
        self.assertIn("without dry_run", text.text)
        self.assertFalse(result.isError)

    async def test_apply_reports_build_and_undo(self):
        api_client = AsyncMock(spec=MinecraftAPIClient)
        api_client.smooth_terrain.return_value = smooth_response(
            dry_run=None, before_png=None, after_png=None, change_png=None, method="pad",
            build_id="b-1", undo_available=True, vegetation_blocks_cleared=812,
            summary={**smooth_response()["summary"], "pad_columns": 100, "pad_columns_at_target": 98,
                     "clamped_columns": 2})

        result = await handle_smooth_terrain(
            api_client, x1=100, z1=200, x2=159, z2=259, method="pad",
            pad={"y": 66, "x1": 110, "z1": 210, "x2": 119, "z2": 219, "blend": 8})

        self.assertEqual(1, len(result.content))
        text = result.content[0].text
        self.assertIn("Terrain smoothed", text)
        self.assertIn("Pad: 98 of 100", text)
        self.assertIn("2 columns hit max_change", text)
        self.assertIn("build b-1", text)
        self.assertIn("undo_build", text)
        self.assertIn("Cleared 812 vegetation blocks", text)

    async def test_errors_are_marked_and_explained(self):
        api_client = AsyncMock(spec=MinecraftAPIClient)
        api_client.smooth_terrain.return_value = {
            "status_code": 400, "success": False, "code": "unloaded_chunks",
            "error": "Chunks in the region are not loaded", "missing_chunks": [{"x": 1, "z": 2}]}

        result = await handle_smooth_terrain(api_client, x1=0, z1=0, x2=9, z2=9, method="median")

        self.assertTrue(result.isError)
        self.assertIn("unloaded_chunks", result.content[0].text)
        self.assertIn("1 chunk(s) are not loaded", result.content[0].text)


if __name__ == "__main__":
    unittest.main()
