"""
Configuration module for Minecraft MCP Server.

Handles environment variable loading, debug mode setup, and server configuration.
"""

import os
import sys
from dataclasses import dataclass
from dotenv import dotenv_values


@dataclass
class ServerConfig:
    """Server configuration data."""
    base_url: str
    debug: bool
    script_dir: str


def load_config() -> ServerConfig:
    """
    Load server configuration from environment variables and .env file.
    
    Returns:
        ServerConfig with base_url, debug mode, and script directory
    """
    # Get script directory
    script_dir = os.path.dirname(os.path.abspath(__file__))
    # Go up one level since we're now in minecraft_mcp/ subdirectory
    script_dir = os.path.dirname(script_dir)
    
    # Local default
    base_url = "http://localhost:7070"
    
    # Read from .env file in the mcp directory
    env_file = os.path.join(script_dir, ".env")
    config = dotenv_values(env_file)
    
    if "BASE_URL" in config:
        base_url = config["BASE_URL"]
    
    # Check DEBUG mode
    debug = bool(os.getenv('DEBUG'))
    
    return ServerConfig(
        base_url=base_url,
        debug=debug,
        script_dir=script_dir
    )


def setup_debug_mode():
    """
    Set up debugpy if DEBUG environment variable is set.
    
    Prints debug information to stderr for Claude Desktop compatibility.
    """
    if os.getenv('DEBUG'):
        try:
            import debugpy
            debugpy.listen(("localhost", 5678))
            print("Debugger listening on port 5678", file=sys.stderr)
            print("Attach your debugger now or set breakpoints and continue", file=sys.stderr)
            # Uncomment the next line if you want to wait for debugger to attach
            # debugpy.wait_for_client()
        except ImportError:
            print("debugpy not installed. Install with: pip install debugpy", file=sys.stderr)


# Export the configuration for backward compatibility
_config = load_config()
BASE_URL = _config.base_url
SCRIPT_DIR = _config.script_dir
DEBUG = _config.debug
