#!/usr/bin/env python3

import unittest
from unittest.mock import AsyncMock

from mcp.types import ImageContent, TextContent

from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
from minecraft_mcp.handlers.blocks import handle_map_terrain
from minecraft_mcp.tools.registry import TOOL_HANDLERS
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS


def map_response(**overrides):
    body = {
        "status_code": 200,
        "success": True,
        "world": "minecraft:overworld",
        "area_bounds": {"min": {"x": 3087, "z": 963}, "max": {"x": 3186, "z": 1042}},
        "size": {"x": 100, "z": 80},
        "heightmap_type": "WORLD_SURFACE",
        "px_per_block": 7,
        "contour_interval": 2,
        "grid_step": 16,
        "y": {"min": 63, "median": 70, "max": 86},
        "coverage": {"grass/dirt": 0.78, "water": 0.07, "path": 0.004},
        "labels": True,
        "relief_png": "UkVMSUVG",
        "surface_png": "U1VSRkFDRQ==",
    }
    body.update(overrides)
    return body


class MapTerrainTests(unittest.IsolatedAsyncioTestCase):
    def test_tool_schema_registered(self):
        schema_names = [tool.name for tool in TOOL_SCHEMAS]
        self.assertIn("map_terrain", schema_names)
        self.assertIn("map_terrain", TOOL_HANDLERS)

    async def test_returns_both_images_then_summary(self):
        api_client = AsyncMock(spec=MinecraftAPIClient)
        api_client.map_terrain.return_value = map_response()

        result = await handle_map_terrain(api_client, x1=3087, z1=963, x2=3186, z2=1042)

        self.assertEqual(3, len(result.content))
        self.assertIsInstance(result.content[0], ImageContent)
        self.assertEqual("UkVMSUVG", result.content[0].data)
        self.assertIsInstance(result.content[1], ImageContent)
        self.assertEqual("U1VSRkFDRQ==", result.content[1].data)
        text = result.content[2]
        self.assertIsInstance(text, TextContent)
        self.assertIn("X 3087..3186, Z 963..1042", text.text)
        self.assertIn("1 block = 7 px", text.text)
        self.assertIn("63/70/86", text.text)
        self.assertIn("Contours every 2, bold every 10", text.text)
        self.assertIn("grass/dirt 78%, water 7%, path <1%", text.text)
        self.assertNotIn("could not draw text", text.text)

    async def test_passes_options_through(self):
        api_client = AsyncMock(spec=MinecraftAPIClient)
        api_client.map_terrain.return_value = map_response(surface_png=None, coverage={})

        result = await handle_map_terrain(
            api_client, x1=0, z1=0, x2=9, z2=9, heightmap_type="MOTION_BLOCKING_NO_LEAVES",
            layers=["relief"], px_per_block=4, contour_interval=5,
        )

        api_client.map_terrain.assert_awaited_once_with(
            0, 0, 9, 9, heightmap_type="MOTION_BLOCKING_NO_LEAVES", world=None,
            layers=["relief"], px_per_block=4, contour_interval=5,
        )
        self.assertEqual(2, len(result.content))
        self.assertNotIn("Surface coverage", result.content[1].text)

    async def test_warns_when_server_could_not_draw_labels(self):
        api_client = AsyncMock(spec=MinecraftAPIClient)
        api_client.map_terrain.return_value = map_response(labels=False)

        result = await handle_map_terrain(api_client, x1=0, z1=0, x2=1, z2=1)

        self.assertIn("could not draw text", result.content[-1].text)

    async def test_error_surfaces_readable_message(self):
        api_client = AsyncMock(spec=MinecraftAPIClient)
        api_client.map_terrain.return_value = {
            "status_code": 400,
            "error": "Area too large. Maximum 10000 height points allowed",
        }

        result = await handle_map_terrain(api_client, x1=0, z1=0, x2=200, z2=200)

        self.assertEqual(1, len(result.content))
        self.assertIn("Terrain map failed", result.content[0].text)
        self.assertIn("Area too large", result.content[0].text)


if __name__ == "__main__":
    unittest.main()
