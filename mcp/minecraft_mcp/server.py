"""
Main server class for Minecraft MCP Server.

This module contains the MinecraftMCPServer class that orchestrates
tool registration, routing, and transport layer management.
"""

import sys
from typing import Any, Dict, List

from mcp.server import Server
from mcp.server.models import InitializationOptions
from mcp.server.stdio import stdio_server
from mcp.server.sse import SseServerTransport
from mcp.server.lowlevel import NotificationOptions
from starlette.applications import Starlette
from starlette.routing import Route
from starlette.responses import Response
from mcp.types import (
    CallToolResult,
    TextContent,
    ContentBlock,
)

from .client.minecraft_api import MinecraftAPIClient
from .tools.schemas import TOOL_SCHEMAS
from .tools.registry import get_handler
from .utils.helpers import safe_url


class MinecraftMCPServer:
    """
    Main MCP server class for Minecraft API integration.
    
    Handles tool registration, routing, and transport layer management.
    """
    
    def __init__(self, api_base: str):
        """
        Initialize the Minecraft MCP Server.
        
        Args:
            api_base: Base URL for the Minecraft API
        """
        self.api_base = api_base
        self.server = Server("minecraft-api")
        self.api_client = MinecraftAPIClient(api_base)
        print(f"Initialized server with API base: {safe_url(api_base)}", file=sys.stderr)
        self.setup_handlers()
    
    def setup_handlers(self):
        """
        Set up MCP server handlers for list_tools and call_tool.
        
        Registers the handlers with the MCP server instance.
        """
        print("Setting up handlers...", file=sys.stderr)
        
        @self.server.list_tools()
        async def list_tools():
            """List available tools for Minecraft API interaction."""
            print("list_tools called", file=sys.stderr)
            return TOOL_SCHEMAS
        
        @self.server.call_tool()
        async def call_tool(name: str, arguments: Dict[str, Any]) -> List[ContentBlock]:
            """
            Handle tool calls by routing to appropriate handlers.
            
            Args:
                name: Name of the tool to call
                arguments: Tool-specific arguments
                
            Returns:
                List of content blocks with the tool result
                
            Raises:
                ValueError: If the tool name is unknown
            """
            print(f"call_tool: {name} with args: {arguments}", file=sys.stderr)
            
            try:
                # Get the handler for this tool
                handler = get_handler(name)
                
                if handler is None:
                    raise ValueError(f"Unknown tool: {name}")
                
                # Call the handler with the API client and arguments
                result = await handler(self.api_client, **arguments)
                
                # Return the content from the result
                return result.content
                
            except Exception as e:
                print(f"Tool error: {e}", file=sys.stderr)
                # Return error as CallToolResult content
                error_result = CallToolResult(
                    content=[TextContent(type="text", text=f"Error: {str(e)}")]
                )
                return error_result.content
    
    async def run_stdio(self):
        """
        Run the MCP server with stdio transport.
        
        This is the default transport mode for Claude Desktop integration.
        """
        print("Starting MCP server stdio connection...", file=sys.stderr)
        async with stdio_server() as (read_stream, write_stream):
            print("MCP server connected, initializing...", file=sys.stderr)
            await self.server.run(
                read_stream,
                write_stream,
                InitializationOptions(
                    server_name="minecraft-api",
                    server_version="1.0.0",
                    capabilities=self.server.get_capabilities(
                        notification_options=NotificationOptions(),
                        experimental_capabilities={}
                    )
                )
            )
    
    def create_sse_app(self) -> Starlette:
        """
        Create a Starlette app for SSE transport.
        
        Returns:
            Starlette application configured for SSE transport
        """
        sse = SseServerTransport("/messages")

        async def handle_sse(request):
            """Handle SSE connection requests."""
            async with sse.connect_sse(
                request.scope,
                request.receive,
                request._send
            ) as streams:
                await self.server.run(
                    streams[0],
                    streams[1],
                    InitializationOptions(
                        server_name="minecraft-api",
                        server_version="1.0.0",
                        capabilities=self.server.get_capabilities(
                            notification_options=NotificationOptions(),
                            experimental_capabilities={}
                        )
                    )
                )

        async def handle_messages(request):
            """Handle POST messages for SSE transport."""
            await sse.handle_post_message(request.scope, request.receive, request._send)
            return Response()

        return Starlette(
            debug=True,
            routes=[
                Route("/sse", endpoint=handle_sse),
                Route("/messages", endpoint=handle_messages, methods=["POST"]),
            ],
        )
    
    async def run(self):
        """
        Run the MCP server (defaults to stdio for backward compatibility).
        """
        await self.run_stdio()
