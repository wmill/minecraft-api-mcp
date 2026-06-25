#!/usr/bin/env python3

import unittest
from unittest.mock import AsyncMock, patch

from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
from minecraft_mcp.handlers.system import handle_teleport_player


class TeleportPlayerTests(unittest.IsolatedAsyncioTestCase):
    async def test_client_uses_players_teleport_endpoint(self):
        posted = {}

        class FakeResponse:
            def raise_for_status(self):
                return None

            def json(self):
                return {"success": True}

        class FakeAsyncClient:
            async def __aenter__(self):
                return self

            async def __aexit__(self, exc_type, exc, tb):
                return None

            async def post(self, url, json):
                posted["url"] = url
                posted["json"] = json
                return FakeResponse()

        with patch("httpx.AsyncClient", FakeAsyncClient):
            client = MinecraftAPIClient("http://localhost:7070")
            result = await client.teleport_player("Steve", 1, 2, 3)

        self.assertEqual({"success": True}, result)
        self.assertEqual("http://localhost:7070/api/players/teleport", posted["url"])
        self.assertEqual("Steve", posted["json"]["player_name"])

    async def test_handler_accepts_success_response(self):
        api_client = AsyncMock(spec=MinecraftAPIClient)
        api_client.teleport_player.return_value = {
            "success": True,
            "status": "success",
        }

        result = await handle_teleport_player(api_client, "Steve", 1, 2, 3)

        self.assertEqual(1, len(result.content))
        self.assertIn("Successfully teleported Steve", result.content[0].text)


if __name__ == "__main__":
    unittest.main()
