#!/usr/bin/env python3
"""
Entry point for running minecraft_mcp as a module or via console script.
This allows both: python -m minecraft_mcp and the minecraft-mcp command.
"""

import asyncio
import argparse
import sys
import uvicorn

from . import MinecraftMCPServer, config

# Set up debug mode if enabled
config.setup_debug_mode()

# Get configuration
BASE_URL = config.BASE_URL

# Add debug output to stderr so it shows in Claude Desktop logs
print("Starting Minecraft MCP Server...", file=sys.stderr)


async def async_main():
    """Async main entry point."""
    parser = argparse.ArgumentParser(description="Minecraft MCP Server")
    parser.add_argument(
        "--transport",
        choices=["stdio", "sse"],
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
    except Exception as e:
        print(f"Fatal error: {e}", file=sys.stderr)
        raise


def main():
    """Synchronous entry point wrapper for console scripts."""
    print("Script starting...", file=sys.stderr)
    asyncio.run(async_main())


if __name__ == "__main__":
    main()
