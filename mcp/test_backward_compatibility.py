#!/usr/bin/env python3
"""
Test script to verify backward compatibility of the refactored MCP server.

This script tests:
1. That the server can be imported and started
2. That all tool names are preserved
3. That the tool registry is complete
4. That response formats are consistent
"""

import sys
import asyncio
from minecraft_mcp import MinecraftMCPServer, config
from minecraft_mcp.tools.schemas import TOOL_SCHEMAS
from minecraft_mcp.tools.registry import TOOL_HANDLERS, get_handler


def test_imports():
    """Test that all modules can be imported."""
    print("✓ Testing imports...")
    try:
        from minecraft_mcp import MinecraftMCPServer
        from minecraft_mcp.config import BASE_URL
        from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
        from minecraft_mcp.tools.schemas import TOOL_SCHEMAS
        from minecraft_mcp.tools.registry import TOOL_HANDLERS, get_handler
        from minecraft_mcp.utils.formatting import format_success_response, format_error_response
        from minecraft_mcp.utils.helpers import yaw_to_cardinal, safe_url
        print("  ✓ All imports successful")
        return True
    except Exception as e:
        print(f"  ✗ Import failed: {e}")
        return False


def test_tool_names():
    """Test that all expected tool names are present."""
    print("\n✓ Testing tool names...")
    
    expected_tools = [
        # World tools
        "get_players",
        "get_entities",
        "spawn_entity",
        # Block tools
        "get_blocks",
        "set_blocks",
        "get_blocks_chunk",
        "fill_box",
        "get_heightmap",
        # Message tools
        "broadcast_message",
        "send_message_to_player",
        # Prefab tools
        "place_nbt_structure",
        "place_door_line",
        "place_stairs",
        "place_window_pane_wall",
        "place_torch",
        "place_sign",
        # System tools
        "teleport_player",
        "test_server_connection",
        # Build management tools
        "create_build",
        "add_build_task",
        "add_build_task_block_set",
        "add_build_task_block_fill",
        "add_build_task_prefab_door",
        "add_build_task_prefab_stairs",
        "add_build_task_prefab_window",
        "add_build_task_prefab_torch",
        "add_build_task_prefab_sign",
        "execute_build",
        "query_builds_by_location",
        "get_build_status",
    ]
    
    schema_names = [tool.name for tool in TOOL_SCHEMAS]
    
    missing_tools = []
    for tool_name in expected_tools:
        if tool_name not in schema_names:
            missing_tools.append(tool_name)
    
    if missing_tools:
        print(f"  ✗ Missing tools: {missing_tools}")
        return False
    
    print(f"  ✓ All {len(expected_tools)} expected tools present")
    return True


def test_tool_registry():
    """Test that all tools have handlers registered."""
    print("\n✓ Testing tool registry...")
    
    schema_names = [tool.name for tool in TOOL_SCHEMAS]
    handler_names = list(TOOL_HANDLERS.keys())
    
    # Check that all schemas have handlers
    missing_handlers = []
    for tool_name in schema_names:
        if tool_name not in handler_names:
            missing_handlers.append(tool_name)
    
    if missing_handlers:
        print(f"  ✗ Tools without handlers: {missing_handlers}")
        return False
    
    # Check that get_handler works
    for tool_name in schema_names:
        handler = get_handler(tool_name)
        if handler is None:
            print(f"  ✗ get_handler returned None for: {tool_name}")
            return False
    
    print(f"  ✓ All {len(schema_names)} tools have handlers registered")
    return True


def test_server_initialization():
    """Test that the server can be initialized."""
    print("\n✓ Testing server initialization...")
    
    try:
        server = MinecraftMCPServer("http://localhost:7070")
        print("  ✓ Server initialized successfully")
        return True
    except Exception as e:
        print(f"  ✗ Server initialization failed: {e}")
        return False


def test_helper_functions():
    """Test that helper functions work correctly."""
    print("\n✓ Testing helper functions...")
    
    try:
        from minecraft_mcp.utils.helpers import yaw_to_cardinal, safe_url
        
        # Test yaw_to_cardinal (returns uppercase)
        assert yaw_to_cardinal(0) == "SOUTH", "yaw_to_cardinal(0) should be 'SOUTH'"
        assert yaw_to_cardinal(90) == "WEST", "yaw_to_cardinal(90) should be 'WEST'"
        assert yaw_to_cardinal(180) == "NORTH", "yaw_to_cardinal(180) should be 'NORTH'"
        assert yaw_to_cardinal(-90) == "EAST", "yaw_to_cardinal(-90) should be 'EAST'"
        
        # Test safe_url (redacts password)
        result = safe_url("http://user:pass@localhost:7070")
        assert "****" in result, "safe_url should redact password"
        assert "localhost:7070" in result, "safe_url should preserve host and port"
        
        print("  ✓ Helper functions work correctly")
        return True
    except Exception as e:
        print(f"  ✗ Helper function test failed: {e}")
        return False


def test_formatting_functions():
    """Test that formatting functions work correctly."""
    print("\n✓ Testing formatting functions...")
    
    try:
        from minecraft_mcp.utils.formatting import (
            format_success_response, 
            format_error_response,
            format_api_error,
            format_validation_error
        )
        from mcp.types import CallToolResult, TextContent
        
        # Test format_success_response
        result = format_success_response("Test message")
        assert isinstance(result, CallToolResult), "format_success_response should return CallToolResult"
        assert len(result.content) > 0, "Result should have content"
        assert isinstance(result.content[0], TextContent), "Content should be TextContent"
        assert result.content[0].text == "Test message", "Content text should match input"
        
        # Test format_error_response (basic error, no emoji)
        error_result = format_error_response(Exception("Test error"))
        assert isinstance(error_result, CallToolResult), "format_error_response should return CallToolResult"
        assert len(error_result.content) > 0, "Error result should have content"
        assert "Test error" in error_result.content[0].text, "Error message should contain error text"
        
        # Test format_api_error (has emoji)
        api_error_result = format_api_error({"error": "API failed"}, "test operation")
        assert "❌" in api_error_result.content[0].text, "API error should contain error emoji"
        
        # Test format_validation_error (has emoji)
        validation_error_result = format_validation_error("Invalid input")
        assert "❌" in validation_error_result.content[0].text, "Validation error should contain error emoji"
        
        print("  ✓ Formatting functions work correctly")
        return True
    except Exception as e:
        print(f"  ✗ Formatting function test failed: {e}")
        return False


def test_config():
    """Test that configuration is loaded correctly."""
    print("\n✓ Testing configuration...")
    
    try:
        from minecraft_mcp.config import BASE_URL, DEBUG, SCRIPT_DIR
        
        assert BASE_URL is not None, "BASE_URL should be set"
        assert isinstance(DEBUG, bool), "DEBUG should be a boolean"
        assert SCRIPT_DIR is not None, "SCRIPT_DIR should be set"
        
        print(f"  ✓ Configuration loaded (BASE_URL: {BASE_URL}, DEBUG: {DEBUG})")
        return True
    except Exception as e:
        print(f"  ✗ Configuration test failed: {e}")
        return False


def main():
    """Run all tests."""
    print("=" * 60)
    print("MCP Server Backward Compatibility Test Suite")
    print("=" * 60)
    
    tests = [
        ("Imports", test_imports),
        ("Tool Names", test_tool_names),
        ("Tool Registry", test_tool_registry),
        ("Server Initialization", test_server_initialization),
        ("Helper Functions", test_helper_functions),
        ("Formatting Functions", test_formatting_functions),
        ("Configuration", test_config),
    ]
    
    results = []
    for test_name, test_func in tests:
        try:
            result = test_func()
            results.append((test_name, result))
        except Exception as e:
            print(f"\n✗ {test_name} test crashed: {e}")
            results.append((test_name, False))
    
    print("\n" + "=" * 60)
    print("Test Summary")
    print("=" * 60)
    
    passed = sum(1 for _, result in results if result)
    total = len(results)
    
    for test_name, result in results:
        status = "✓ PASS" if result else "✗ FAIL"
        print(f"{status}: {test_name}")
    
    print(f"\nTotal: {passed}/{total} tests passed")
    
    if passed == total:
        print("\n✓ All backward compatibility tests passed!")
        return 0
    else:
        print(f"\n✗ {total - passed} test(s) failed")
        return 1


if __name__ == "__main__":
    sys.exit(main())
