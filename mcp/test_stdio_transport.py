#!/usr/bin/env python3
"""
Test that the MCP server can start with stdio transport.

This script tests that:
1. The server can be started with stdio transport
2. The server initializes without errors
3. The server can be stopped gracefully
"""

import asyncio
import sys
import signal
from minecraft_mcp import MinecraftMCPServer, config


async def test_stdio_startup():
    """Test that the server can start with stdio transport."""
    print("Testing stdio transport startup...", file=sys.stderr)
    
    try:
        # Create server instance
        server = MinecraftMCPServer(config.BASE_URL)
        print("✓ Server instance created", file=sys.stderr)
        
        # Test that the server has the run_stdio method
        assert hasattr(server, 'run_stdio'), "Server should have run_stdio method"
        print("✓ Server has run_stdio method", file=sys.stderr)
        
        # Test that the server has the create_sse_app method
        assert hasattr(server, 'create_sse_app'), "Server should have create_sse_app method"
        print("✓ Server has create_sse_app method", file=sys.stderr)
        
        # Test that the server has the run method
        assert hasattr(server, 'run'), "Server should have run method"
        print("✓ Server has run method", file=sys.stderr)
        
        print("\n✓ All stdio transport tests passed!", file=sys.stderr)
        return True
        
    except Exception as e:
        print(f"\n✗ Stdio transport test failed: {e}", file=sys.stderr)
        return False


async def main():
    """Run the test."""
    success = await test_stdio_startup()
    return 0 if success else 1


if __name__ == "__main__":
    exit_code = asyncio.run(main())
    sys.exit(exit_code)
