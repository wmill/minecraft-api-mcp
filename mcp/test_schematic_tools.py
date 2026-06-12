from minecraft_mcp.config import SCHEMATIC_SERVICE_URL
from minecraft_mcp.tools.registry import get_handler
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS


def test_schematic_tools_are_registered():
    names = {tool.name for tool in TOOL_SCHEMAS}

    assert "search_schematics" in names
    assert "get_schematic" in names
    assert "place_schematic" in names
    assert get_handler("search_schematics") is not None
    assert get_handler("get_schematic") is not None
    assert get_handler("place_schematic") is not None


def test_schematic_service_url_has_local_default():
    assert SCHEMATIC_SERVICE_URL == "http://localhost:7080"
