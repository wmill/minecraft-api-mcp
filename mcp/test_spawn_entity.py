#!/usr/bin/env python3

import unittest
from unittest.mock import AsyncMock, patch

import httpx

from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
from minecraft_mcp.handlers.world import handle_spawn_entity
from minecraft_mcp.tools.schemas import TOOL_SPAWN_ENTITY


def _entity(entity_type, uuid, x=0.5, y=64.0, z=0.5, passengers=None):
    entity = {"type": entity_type, "uuid": uuid, "position": {"x": x, "y": y, "z": z}}
    if passengers:
        entity["passengers"] = passengers
    return entity


class SpawnEntityTests(unittest.TestCase):
    def test_schema_exposes_fun_options(self):
        props = TOOL_SPAWN_ENTITY.inputSchema["properties"]
        self.assertIn("count", props)
        self.assertIn("spread", props)
        options = props["options"]["properties"]
        for name in ("custom_name", "glowing", "equipment", "effects", "variant", "color",
                     "profession", "charged", "tamed_by", "passengers"):
            self.assertIn(name, options)
        self.assertEqual(["entity_type", "x", "y", "z"], TOOL_SPAWN_ENTITY.inputSchema["required"])


class SpawnEntityAsyncTests(unittest.IsolatedAsyncioTestCase):
    async def _post(self, **kwargs):
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
            await client.spawn_entity("minecraft:spider", 1, 64, 2, **kwargs)
        return posted

    async def test_legacy_payload_is_unchanged(self):
        posted = await self._post()
        self.assertEqual("http://localhost:7070/api/world/entities/spawn", posted["url"])
        self.assertEqual({"type": "minecraft:spider", "x": 1, "y": 64, "z": 2}, posted["json"])

    async def test_options_forwarded_and_passengers_renamed(self):
        options = {
            "glowing": True,
            "passengers": [{
                "entity_type": "minecraft:skeleton",
                "options": {
                    "custom_name": "Rider",
                    "passengers": [{"entity_type": "minecraft:chicken"}],
                },
            }],
        }
        posted = await self._post(count=3, spread=4.0, options=options)
        body = posted["json"]
        self.assertEqual(3, body["count"])
        self.assertEqual(4.0, body["spread"])
        self.assertTrue(body["options"]["glowing"])
        rider = body["options"]["passengers"][0]
        self.assertEqual("minecraft:skeleton", rider["type"])
        self.assertNotIn("entity_type", rider)
        self.assertEqual("Rider", rider["options"]["custom_name"])
        self.assertEqual([{"type": "minecraft:chicken"}], rider["options"]["passengers"])
        # caller's dict is not mutated
        self.assertEqual("minecraft:skeleton", options["passengers"][0]["entity_type"])

    async def test_single_entity_keeps_original_output(self):
        api_client = AsyncMock(spec=MinecraftAPIClient)
        api_client.spawn_entity.return_value = {
            "success": True, "type": "minecraft:cow", "uuid": "u-1",
            "position": {"x": 0.5, "y": 64.0, "z": 0.5},
            "entities": [_entity("minecraft:cow", "u-1")], "ignored": [],
        }

        result = await handle_spawn_entity(api_client, "minecraft:cow", 0, 64, 0)

        text = result.content[0].text
        self.assertIn("Successfully spawned minecraft:cow", text)
        self.assertIn("Entity UUID: u-1", text)
        api_client.spawn_entity.assert_awaited_once_with("minecraft:cow", 0, 64, 0, None, None, None, None)

    async def test_multi_entity_tree_and_ignored(self):
        api_client = AsyncMock(spec=MinecraftAPIClient)
        jockey = _entity("minecraft:spider", "s-1", passengers=[_entity("minecraft:skeleton", "k-1")])
        api_client.spawn_entity.return_value = {
            "success": True, "type": "minecraft:spider", "uuid": "s-1",
            "position": jockey["position"],
            "entities": [jockey, _entity("minecraft:spider", "s-2", passengers=[_entity("minecraft:skeleton", "k-2")])],
            "ignored": ["baby (minecraft:skeleton)"],
        }

        result = await handle_spawn_entity(
            api_client, "minecraft:spider", 0, 64, 0, count=2,
            options={"passengers": [{"entity_type": "minecraft:skeleton", "options": {"baby": True}}]})

        text = result.content[0].text
        self.assertIn("spawned 2 x minecraft:spider (4 entities including passengers)", text)
        self.assertIn("↳ minecraft:skeleton", text)
        self.assertIn("UUID k-2", text)
        self.assertIn("Ignored (not applicable to that entity type): baby (minecraft:skeleton)", text)

    async def test_validation_error_message_is_surfaced(self):
        request = httpx.Request("POST", "http://localhost:7070/api/world/entities/spawn")
        response = httpx.Response(400, json={"error": "Invalid item: minecraft:lightsaber"}, request=request)
        api_client = AsyncMock(spec=MinecraftAPIClient)
        api_client.spawn_entity.side_effect = httpx.HTTPStatusError("400", request=request, response=response)

        result = await handle_spawn_entity(api_client, "minecraft:zombie", 0, 64, 0,
                                           options={"equipment": {"mainhand": "minecraft:lightsaber"}})

        self.assertTrue(result.isError)
        self.assertIn("Invalid item: minecraft:lightsaber", result.content[0].text)


if __name__ == "__main__":
    unittest.main()
