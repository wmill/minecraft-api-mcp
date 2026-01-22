"""
Main server class for Minecraft MCP Server.

This module contains the MinecraftMCPServer class that orchestrates
tool registration, routing, and transport layer management.
"""

import sys
from typing import Any, Dict, List

from contextlib import asynccontextmanager
from collections.abc import AsyncIterator

from mcp.server import Server
from mcp.server.models import InitializationOptions
from mcp.server.stdio import stdio_server
from mcp.server.sse import SseServerTransport
from mcp.server.streamable_http_manager import StreamableHTTPSessionManager
from mcp.server.lowlevel import NotificationOptions
from starlette.applications import Starlette
from starlette.routing import Route
from mcp.types import (
    CallToolResult,
    TextContent,
    ContentBlock,
    Resource,
)
from mcp.server.lowlevel.helper_types import ReadResourceContents

from .client.minecraft_api import MinecraftAPIClient
from .tools.schemas import TOOL_SCHEMAS
from .tools.registry import get_handler
from .utils.helpers import safe_url, coordinate_info_blurb


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
            
        @self.server.list_resources()
        async def list_resources() -> list[Resource]:
            return [
                Resource(
                    uri="file://conventions",
                    name="conventions",
                    title="conventions",
                    description="A brief guide to conventions and coordinate systems used",
                )
            ]
        
        @self.server.read_resource()
        async def read_resource(uri: str):
            # only have one so just return it
            return [ReadResourceContents(mime_type="text/plain", content=coordinate_info_blurb)]
        
    
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

        class SseConnectApp:
            """ASGI app that keeps the SSE stream open for the MCP server."""

            def __init__(self, server):
                self._server = server

            async def __call__(self, scope, receive, send):
                async with sse.connect_sse(scope, receive, send) as streams:
                    await self._server.run(
                        streams[0],
                        streams[1],
                        InitializationOptions(
                            server_name="minecraft-api",
                            server_version="1.0.0",
                            capabilities=self._server.get_capabilities(
                                notification_options=NotificationOptions(),
                                experimental_capabilities={}
                            )
                        )
                    )

        class SseMessagesApp:
            """ASGI app that delegates POSTs to the SSE transport handler."""

            def __init__(self, sse_transport):
                self._sse_transport = sse_transport

            async def __call__(self, scope, receive, send):
                await self._sse_transport.handle_post_message(scope, receive, send)

        return Starlette(
            debug=True,
            routes=[
                Route("/sse", endpoint=SseConnectApp(self.server)),
                Route("/messages", endpoint=SseMessagesApp(sse), methods=["POST"]),
            ],
        )
    
    def create_streamable_http_app(self, stateless: bool = False) -> Starlette:
        """
        Create a Starlette app for Streamable HTTP transport.

        This is the modern HTTP transport that uses a single endpoint for all
        communication. It supports session management, optional resumability,
        and can return JSON responses or use SSE streaming.

        Args:
            stateless: If True, creates a fresh transport for each request
                      with no session tracking. Default is False (stateful).

        Returns:
            Starlette application configured for Streamable HTTP transport
        """
        session_manager = StreamableHTTPSessionManager(
            app=self.server,
            stateless=stateless,
        )

        @asynccontextmanager
        async def lifespan(app: Starlette) -> AsyncIterator[None]:
            async with session_manager.run():
                yield

        class StreamableHTTPEndpoint:
            """ASGI app wrapper for StreamableHTTPSessionManager."""

            async def __call__(self, scope, receive, send):
                # Log request details for debugging
                method = scope.get("method", "UNKNOWN")
                path = scope.get("path", "")
                query_string = scope.get("query_string", b"").decode("utf-8", errors="replace")
                headers = {k.decode(): v.decode() for k, v in scope.get("headers", [])}

                print(f"[StreamableHTTP] {method} {path}{'?' + query_string if query_string else ''}", file=sys.stderr)
                print(f"[StreamableHTTP] Headers: {headers}", file=sys.stderr)

                # For POST requests, we need to capture the body
                body_parts = []

                async def logging_receive():
                    message = await receive()
                    if message.get("type") == "http.request":
                        body = message.get("body", b"")
                        body_parts.append(body)
                        if body:
                            try:
                                print(f"[StreamableHTTP] Body: {body.decode('utf-8', errors='replace')[:1000]}", file=sys.stderr)
                            except Exception as e:
                                print(f"[StreamableHTTP] Body decode error: {e}", file=sys.stderr)
                    return message

                # Capture response status
                async def logging_send(message):
                    if message.get("type") == "http.response.start":
                        status = message.get("status", 0)
                        print(f"[StreamableHTTP] Response status: {status}", file=sys.stderr)
                        if status >= 400:
                            resp_headers = {k.decode(): v.decode() for k, v in message.get("headers", [])}
                            print(f"[StreamableHTTP] Response headers: {resp_headers}", file=sys.stderr)
                    elif message.get("type") == "http.response.body":
                        body = message.get("body", b"")
                        if body and scope.get("method") == "GET":  # Log body for failed GETs
                            try:
                                print(f"[StreamableHTTP] Response body: {body.decode('utf-8', errors='replace')[:500]}", file=sys.stderr)
                            except Exception:
                                pass
                    await send(message)

                try:
                    await session_manager.handle_request(scope, logging_receive, logging_send)
                except Exception as e:
                    print(f"[StreamableHTTP] Exception: {type(e).__name__}: {e}", file=sys.stderr)
                    raise

        return Starlette(
            debug=True,
            routes=[
                Route("/mcp", endpoint=StreamableHTTPEndpoint(), methods=["GET", "POST", "DELETE"]),
            ],
            lifespan=lifespan,
        )

    async def run(self):
        """
        Run the MCP server (defaults to stdio for backward compatibility).
        """
        await self.run_stdio()
