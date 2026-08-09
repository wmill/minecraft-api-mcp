"""Registration and configuration tests for the Starlark build service tools."""

from minecraft_mcp.config import STARLARK_SERVICE_URL
from minecraft_mcp.tools.registry import get_handler
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS

STARLARK_TOOLS = [
    "build_starlark_structure",
    "place_starlark_structure",
    "get_starlark_docs",
    "list_starlark_examples",
    "get_starlark_example",
]


def test_starlark_tools_are_registered():
    names = {tool.name for tool in TOOL_SCHEMAS}
    for tool_name in STARLARK_TOOLS:
        assert tool_name in names
        assert get_handler(tool_name) is not None


def test_starlark_service_url_has_local_default():
    assert STARLARK_SERVICE_URL == "http://localhost:7090"
