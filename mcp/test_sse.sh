#!/bin/bash
# Test script for HTTP/SSE transport mode

echo "Starting Minecraft MCP Server in HTTP/SSE mode..."
echo "Press Ctrl+C to stop the server"
echo ""
echo "Endpoints:"
echo "  SSE connection: http://localhost:3000/sse"
echo "  Messages: http://localhost:3000/messages"
echo ""
echo "To test with MCP Inspector, run in another terminal:"
echo "  npx @modelcontextprotocol/inspector http://localhost:3000/sse"
echo ""

uv run minecraft_mcp.py --transport sse --host 0.0.0.0 --port 3000
