from unittest.mock import AsyncMock

import anyio
import pytest
from mcp.shared.memory import create_connected_server_and_client_session
from mcp.types import CallToolResult, TextContent

from minecraft_mcp import config
from minecraft_mcp.server import MinecraftMCPServer, RAIL_TOOLS
from minecraft_mcp.tools.registry import TOOL_HANDLERS
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS


@pytest.mark.parametrize("value,enabled", [
    (None, False), ("false", False), ("0", False), ("", False),
    ("no", False), ("off", False), ("typo", False),
    ("true", True), (" TRUE ", True), ("1", True), ("yes", True), ("on", True),
])
def test_flag_parsing(monkeypatch, value, enabled):
    monkeypatch.setattr(config, "dotenv_values", lambda _: {})
    monkeypatch.delenv("ENABLE_RAIL_TOOLS", raising=False)
    if value is not None:
        monkeypatch.setenv("ENABLE_RAIL_TOOLS", value)
    assert config.load_config().enable_rail_tools is enabled


def test_dotenv_and_environment_precedence(monkeypatch):
    monkeypatch.setattr(config, "dotenv_values", lambda _: {"ENABLE_RAIL_TOOLS": "true"})
    monkeypatch.delenv("ENABLE_RAIL_TOOLS", raising=False)
    assert config.load_config().enable_rail_tools
    monkeypatch.setenv("ENABLE_RAIL_TOOLS", "false")
    assert not config.load_config().enable_rail_tools


@pytest.mark.parametrize("enabled", [False, True])
async def test_rail_discovery_and_dispatch(monkeypatch, enabled):
    monkeypatch.setattr(config, "dotenv_values", lambda _: {})
    monkeypatch.delenv("ENABLE_RAIL_TOOLS", raising=False)
    if enabled:
        monkeypatch.setenv("ENABLE_RAIL_TOOLS", "true")
    handlers = {}
    for name in RAIL_TOOLS:
        handlers[name] = AsyncMock(return_value=CallToolResult(
            content=[TextContent(type="text", text="rail result")],
        ))
        monkeypatch.setitem(TOOL_HANDLERS, name, handlers[name])
    server = MinecraftMCPServer("http://minecraft")
    arguments = {
        "plan_rail_route": dict(build_id="build", start_x=0, start_y=64, start_z=0,
                                end_x=10, end_y=64, end_z=10),
        "get_rail_plan_status": {"planning_job_id": "job"},
    }
    with anyio.fail_after(10):
        async with create_connected_server_and_client_session(server.server) as client:
            names = {tool.name for tool in (await client.list_tools()).tools}
            expected = {tool.name for tool in TOOL_SCHEMAS}
            assert names == (expected if enabled else expected - RAIL_TOOLS)
            for name, args in arguments.items():
                result = await client.call_tool(name, args)
                assert result.isError is not enabled
                if enabled:
                    handlers[name].assert_awaited_once_with(server.api_client, **args)
                else:
                    handlers[name].assert_not_awaited()
