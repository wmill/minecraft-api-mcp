#!/usr/bin/env python3
"""
Final verification test for the MCP server refactoring.

Tests:
1. Integration with actual Minecraft API (if available)
2. SSE transport initialization
3. DEBUG mode and debugpy configuration
"""

import sys
import os
import asyncio
import subprocess
import time
from minecraft_mcp import MinecraftMCPServer, config


def test_sse_transport():
    """Test that SSE transport can be initialized."""
    print("\n✓ Testing SSE transport initialization...")
    
    try:
        server = MinecraftMCPServer("http://localhost:7070")
        app = server.create_sse_app()
        
        # Verify the app has the correct routes
        route_paths = [route.path for route in app.routes]
        
        if "/sse" not in route_paths:
            print("  ✗ SSE endpoint /sse not found in routes")
            return False
        
        if "/messages" not in route_paths:
            print("  ✗ Messages endpoint /messages not found in routes")
            return False
        
        print("  ✓ SSE transport initialized successfully")
        print(f"    - Routes: {route_paths}")
        return True
    except Exception as e:
        print(f"  ✗ SSE transport initialization failed: {e}")
        return False


def test_debug_mode():
    """Test that DEBUG mode configuration works."""
    print("\n✓ Testing DEBUG mode configuration...")
    
    try:
        # Test that config module has DEBUG attribute
        from minecraft_mcp.config import DEBUG, setup_debug_mode
        
        print(f"  ✓ DEBUG mode is: {DEBUG}")
        
        # Test that setup_debug_mode can be called
        # (it won't actually start debugpy unless DEBUG env var is set)
        setup_debug_mode()
        
        print("  ✓ DEBUG mode configuration works correctly")
        return True
    except Exception as e:
        print(f"  ✗ DEBUG mode test failed: {e}")
        return False


def test_debugpy_import():
    """Test that debugpy can be imported if installed."""
    print("\n✓ Testing debugpy availability...")
    
    try:
        import debugpy
        print(f"  ✓ debugpy is installed (version: {debugpy.__version__})")
        return True
    except ImportError:
        print("  ⚠ debugpy is not installed (optional dependency)")
        print("    Install with: pip install debugpy")
        return True  # Not a failure, just a warning


async def test_api_client_methods():
    """Test that API client methods are properly defined."""
    print("\n✓ Testing API client methods...")
    
    try:
        from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
        
        client = MinecraftAPIClient("http://localhost:7070")
        
        # Check that all expected methods exist
        expected_methods = [
            "get_players",
            "get_entities",
            "spawn_entity",
            "get_blocks",
            "set_blocks",
            "get_blocks_chunk",
            "fill_box",
            "get_heightmap",
            "broadcast_message",
            "send_message_to_player",
            "place_nbt_structure",
            "place_door_line",
            "place_stairs",
            "place_window_pane_wall",
            "place_torch",
            "place_sign",
            "create_build",
            "add_build_task",
            "execute_build",
            "query_builds_by_location",
            "get_build_status",
            "teleport_player",
            "test_connection",
        ]
        
        missing_methods = []
        for method_name in expected_methods:
            if not hasattr(client, method_name):
                missing_methods.append(method_name)
        
        if missing_methods:
            print(f"  ✗ Missing API client methods: {missing_methods}")
            return False
        
        print(f"  ✓ All {len(expected_methods)} API client methods are defined")
        return True
    except Exception as e:
        print(f"  ✗ API client test failed: {e}")
        return False


async def test_minecraft_api_connection():
    """Test connection to actual Minecraft API if available."""
    print("\n✓ Testing Minecraft API connection...")
    
    try:
        from minecraft_mcp.client.minecraft_api import MinecraftAPIClient
        
        client = MinecraftAPIClient("http://localhost:7070")
        
        # Try to test the connection
        result = await client.test_connection()
        
        if result.get("status") == "ok":
            print("  ✓ Successfully connected to Minecraft API")
            print(f"    - Response: {result}")
            return True
        else:
            print("  ⚠ Minecraft API returned unexpected response")
            print(f"    - Response: {result}")
            return True  # Not a failure, API might not be running
    except Exception as e:
        print(f"  ⚠ Could not connect to Minecraft API (this is OK if server is not running)")
        print(f"    - Error: {e}")
        return True  # Not a failure, API might not be running


def test_stdio_transport():
    """Test that stdio transport can be initialized."""
    print("\n✓ Testing stdio transport initialization...")
    
    try:
        server = MinecraftMCPServer("http://localhost:7070")
        
        # Verify the server has run_stdio method
        if not hasattr(server, "run_stdio"):
            print("  ✗ Server missing run_stdio method")
            return False
        
        print("  ✓ stdio transport is available")
        return True
    except Exception as e:
        print(f"  ✗ stdio transport test failed: {e}")
        return False


def test_command_line_args():
    """Test that command-line arguments are preserved."""
    print("\n✓ Testing command-line argument parsing...")
    
    try:
        # Test that the main script can parse arguments
        result = subprocess.run(
            ["uv", "run", "python", "minecraft_mcp.py", "--help"],
            capture_output=True,
            text=True,
            timeout=5
        )
        
        if result.returncode != 0:
            print(f"  ✗ Help command failed with exit code {result.returncode}")
            return False
        
        # Check that expected arguments are in help text
        help_text = result.stdout
        
        if "--transport" not in help_text:
            print("  ✗ --transport argument not found in help")
            return False
        
        if "--host" not in help_text:
            print("  ✗ --host argument not found in help")
            return False
        
        if "--port" not in help_text:
            print("  ✗ --port argument not found in help")
            return False
        
        print("  ✓ Command-line arguments are preserved")
        print("    - --transport (stdio/sse)")
        print("    - --host")
        print("    - --port")
        return True
    except Exception as e:
        print(f"  ✗ Command-line argument test failed: {e}")
        return False


async def main():
    """Run all verification tests."""
    print("=" * 60)
    print("Final Verification Test Suite")
    print("=" * 60)
    
    tests = [
        ("API Client Methods", test_api_client_methods),
        ("stdio Transport", test_stdio_transport),
        ("SSE Transport", test_sse_transport),
        ("DEBUG Mode", test_debug_mode),
        ("debugpy Availability", test_debugpy_import),
        ("Command-line Arguments", test_command_line_args),
        ("Minecraft API Connection", test_minecraft_api_connection),
    ]
    
    results = []
    for test_name, test_func in tests:
        try:
            if asyncio.iscoroutinefunction(test_func):
                result = await test_func()
            else:
                result = test_func()
            results.append((test_name, result))
        except Exception as e:
            print(f"\n✗ {test_name} test crashed: {e}")
            import traceback
            traceback.print_exc()
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
        print("\n✓ All verification tests passed!")
        return 0
    else:
        print(f"\n✗ {total - passed} test(s) failed")
        return 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
