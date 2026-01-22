#!/usr/bin/env python3
"""
MCP Server for Minecraft Fabric Mod API
Provides tools to interact with the Minecraft server through HTTP endpoints.

Coordinate System:
World coordinates are based on a grid where three lines or axes intersect at the origin point.

- The x-axis indicates the player's distance east (positive) or west (negative) of the origin point—i.e., the longitude
- The z-axis indicates the player's distance south (positive) or north (negative) of the origin point—i.e., the latitude  
- The y-axis indicates how high or low (from 0 to 255 (pre 1.18) or -64 to 320 (from 1.18), with 63 being sea level) the player is—i.e., the elevation

The unit length of the three axes equals the side of one block. And, in terms of real-world measurement, one block equals 1 cubic meter.

Rotation System (Yaw and Pitch):
Player and entity rotations are defined by yaw (horizontal) and pitch (vertical) angles:

Yaw (horizontal rotation):
- Yaw = 0° → facing south (positive Z direction)
- Yaw = 90° → facing west (negative X direction)  
- Yaw = 180° or -180° → facing north (negative Z direction)
- Yaw = -90° → facing east (positive X direction)

Pitch (vertical rotation):
- Pitch = 0° → looking straight ahead (horizontal)
- Pitch = 90° → looking straight down
- Pitch = -90° → looking straight up

Warning! Pay attention to the fact that Minecraft coordinates have 0 yaw as south. In most engines it is east or north so don't let that trip you up.
Similarly positive Z is south. 
"""

import asyncio
import argparse
import sys
import uvicorn

# Import from the modular package
from minecraft_mcp import MinecraftMCPServer, config

# Set up debug mode if enabled
config.setup_debug_mode()

# Get configuration
BASE_URL = config.BASE_URL

# Add debug output to stderr so it shows in Claude Desktop logs
print("Starting Minecraft MCP Server...", file=sys.stderr)


async def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(description="Minecraft MCP Server")
    parser.add_argument(
        "--transport",
        choices=["stdio", "sse", "streamable-http"],
        default="stdio",
        help="Transport protocol to use (default: stdio)"
    )
    parser.add_argument(
        "--host",
        default="0.0.0.0",
        help="Host to bind HTTP/SSE server to (default: 0.0.0.0)"
    )
    parser.add_argument(
        "--port",
        type=int,
        default=3000,
        help="Port for HTTP/SSE server (default: 3000)"
    )
    parser.add_argument(
        "--stateless",
        action="store_true",
        help="Run streamable-http in stateless mode (no session tracking)"
    )

    args = parser.parse_args()

    print(f"Main function started with transport: {args.transport}", file=sys.stderr)

    try:
        server = MinecraftMCPServer(BASE_URL)

        if args.transport == "stdio":
            await server.run_stdio()
        elif args.transport == "sse":
            print(f"Starting HTTP/SSE server on {args.host}:{args.port}", file=sys.stderr)
            print(f"SSE endpoint: http://{args.host}:{args.port}/sse", file=sys.stderr)
            print(f"Messages endpoint: http://{args.host}:{args.port}/messages", file=sys.stderr)
            app = server.create_sse_app()
            config_obj = uvicorn.Config(
                app,
                host=args.host,
                port=args.port,
                log_level="info"
            )
            server_instance = uvicorn.Server(config_obj)
            await server_instance.serve()
        elif args.transport == "streamable-http":
            print(f"Starting Streamable HTTP server on {args.host}:{args.port}", file=sys.stderr)
            print(f"MCP endpoint: http://{args.host}:{args.port}/mcp", file=sys.stderr)
            if args.stateless:
                print("Running in stateless mode (no session tracking)", file=sys.stderr)
            app = server.create_streamable_http_app(stateless=args.stateless)
            config_obj = uvicorn.Config(
                app,
                host=args.host,
                port=args.port,
                log_level="info"
            )
            server_instance = uvicorn.Server(config_obj)
            await server_instance.serve()
    except Exception as e:
        print(f"Fatal error: {e}", file=sys.stderr)
        raise


if __name__ == "__main__":
    print("Script starting...", file=sys.stderr)
    asyncio.run(main())
