"""Exercise result preservation through MCP dispatch and real transport framing."""

import json
import os
import sys
from unittest.mock import AsyncMock

import anyio
import pytest
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from mcp.shared.memory import create_connected_server_and_client_session
from mcp.types import CallToolResult, ImageContent, TextContent
from starlette.testclient import TestClient
from sse_starlette.sse import AppStatus

from minecraft_mcp import server as server_module
from minecraft_mcp.server import MinecraftMCPServer


@pytest.mark.parametrize("error", [False, True])
async def test_dispatch_preserves_complete_result(monkeypatch, error):
    expected = CallToolResult(
        content=[TextContent(type="text", text="result"), ImageContent(type="image", data="aW1hZ2U=", mimeType="image/png")],
        structuredContent={"ok": not error}, isError=error,
    )
    monkeypatch.setattr(server_module, "get_handler", lambda name: AsyncMock(return_value=expected))
    server = MinecraftMCPServer("http://unused")
    with anyio.fail_after(10):
        async with create_connected_server_and_client_session(server.server) as client:
            result = await client.call_tool("build_starlark_structure", {"source": "source"})
            assert result == expected


def rpc_response(response):
    assert response.status_code == 200, response.text
    if response.headers["content-type"].startswith("text/event-stream"):
        return json.loads(next(line[6:] for line in response.text.splitlines() if line.startswith("data: ")))
    return response.json()


@pytest.mark.parametrize("stateless", [False, True])
def test_streamable_http_initialization_and_tool_result(monkeypatch, stateless):
    # Each TestClient owns a new loop; sse-starlette caches its exit event globally.
    monkeypatch.setattr(AppStatus, "should_exit_event", None)
    expected = CallToolResult(content=[TextContent(type="text", text="repair this")],
                              structuredContent={"ok": False, "error_kind": "build_error"}, isError=True)
    monkeypatch.setattr(server_module, "get_handler", lambda name: AsyncMock(return_value=expected))
    server = MinecraftMCPServer("http://unused")
    headers = {"Accept": "application/json, text/event-stream", "MCP-Protocol-Version": "2025-06-18"}
    with TestClient(server.create_streamable_http_app(stateless=stateless)) as client:
        response = client.post("/mcp", headers=headers, json={
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                       "clientInfo": {"name": "test", "version": "1"}},
        })
        assert "serverInfo" in rpc_response(response)["result"]
        if "mcp-session-id" in response.headers:
            headers["MCP-Session-Id"] = response.headers["mcp-session-id"]
        notified = client.post("/mcp", headers=headers,
                               json={"jsonrpc": "2.0", "method": "notifications/initialized"})
        assert notified.status_code == 202
        response = client.post("/mcp", headers=headers, json={
            "jsonrpc": "2.0", "id": 2, "method": "tools/call",
            "params": {"name": "build_starlark_structure", "arguments": {"source": "source"}},
        })
        result = rpc_response(response)["result"]
        assert result["isError"] is True
        assert result["structuredContent"] == expected.structuredContent
        assert result["content"][0]["text"] == "repair this"


async def test_stdio_initializes_and_returns_structured_failure():
    # Unsupported URL scheme deliberately prevents all external service access.
    params = StdioServerParameters(command=sys.executable, args=["-m", "minecraft_mcp"],
                                   env={**os.environ, "DEBUG": "", "STARLARK_SERVICE_URL": "unsupported://service"})
    with anyio.fail_after(15):
        async with stdio_client(params) as (read, write):
            async with ClientSession(read, write) as client:
                await client.initialize()
                result = await client.call_tool("build_starlark_structure", {"source": "source"})
                assert result.isError
                assert result.structuredContent["ok"] is False
                assert result.structuredContent["error_kind"] == "unavailable"


def test_sse_app_startup():
    app = MinecraftMCPServer("http://unused").create_sse_app()
    with TestClient(app) as client:
        assert {route.path for route in app.routes} == {"/sse", "/messages"}
        assert client.get("/messages").status_code == 405
