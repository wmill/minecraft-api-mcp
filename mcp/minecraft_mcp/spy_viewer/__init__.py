"""Spy viewer: a browser page showing live MCP tool calls (``uv run minecraft-spy``)."""

from __future__ import annotations

import argparse
import sys


def main() -> None:
    import uvicorn

    from ..config import load_config
    from .app import create_app

    parser = argparse.ArgumentParser(description="Live viewer for Minecraft MCP tool calls (spy mode)")
    parser.add_argument("--dir", default=None, help="Spool directory (default: SPY_DIR from mcp/.env)")
    parser.add_argument("--host", default="127.0.0.1", help="Bind host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=7095, help="Port (default: 7095)")
    args = parser.parse_args()

    directory = args.dir or load_config().spy_dir
    if not directory:
        sys.exit("No spool directory: set SPY_DIR in mcp/.env (the MCP server must use the same one) or pass --dir")

    print(f"Spy viewer on http://{args.host}:{args.port} reading {directory}", file=sys.stderr)
    app = create_app(directory)
    server = uvicorn.Server(uvicorn.Config(app, host=args.host, port=args.port, log_level="warning",
                                           timeout_graceful_shutdown=2))
    app.state.should_stop = lambda: server.should_exit
    server.run()
