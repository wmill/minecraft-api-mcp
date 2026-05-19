#!/usr/bin/env python3

import base64
import unittest
from unittest.mock import AsyncMock

from mcp.types import ImageContent, TextContent

from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
from minecraft_mcp.handlers.blocks import handle_preview_heightmap
from minecraft_mcp.tools.registry import TOOL_HANDLERS
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS


class PreviewHeightmapTests(unittest.IsolatedAsyncioTestCase):
    def test_tool_schema_registered(self):
        schema_names = [tool.name for tool in TOOL_SCHEMAS]
        self.assertIn("preview_heightmap", schema_names)
        self.assertIn("preview_heightmap", TOOL_HANDLERS)

    async def test_handler_success_returns_image_content(self):
        api_client = AsyncMock(spec=MinecraftAPIClient)
        api_client.preview_heightmap.return_value = {
            "status_code": 200,
            "content_type": "image/png",
            "png_bytes": b"\x89PNG\r\n\x1a\nfake",
        }

        result = await handle_preview_heightmap(
            api_client,
            x1=0,
            z1=0,
            x2=1,
            z2=1,
        )

        self.assertEqual(2, len(result.content))
        self.assertIsInstance(result.content[0], ImageContent)
        self.assertEqual("image/png", result.content[0].mimeType)
        self.assertEqual(base64.b64encode(b"\x89PNG\r\n\x1a\nfake").decode("ascii"), result.content[0].data)
        self.assertIsInstance(result.content[1], TextContent)
        self.assertIn("flat-shaded isometric surface preview", result.content[1].text)

    async def test_handler_non_200_surfaces_readable_error(self):
        api_client = AsyncMock(spec=MinecraftAPIClient)
        api_client.preview_heightmap.return_value = {
            "status_code": 400,
            "content_type": "application/json",
            "error": "iso_scale must be between 1 and 32",
        }

        result = await handle_preview_heightmap(
            api_client,
            x1=0,
            z1=0,
            x2=1,
            z2=1,
        )

        self.assertEqual(1, len(result.content))
        self.assertIsInstance(result.content[0], TextContent)
        self.assertIn("Heightmap preview failed", result.content[0].text)
        self.assertIn("iso_scale must be between 1 and 32", result.content[0].text)


if __name__ == "__main__":
    unittest.main()
