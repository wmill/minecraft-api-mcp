"""
Minecraft MCP Server Package

A modular Model Context Protocol server for Minecraft API integration.
"""

__version__ = "1.0.0"

# Export main server class
from .server import MinecraftMCPServer

# Export API client
from .client.minecraft_api import MinecraftAPIClient

# Export config module
from . import config

# Public API
__all__ = [
    "MinecraftMCPServer",
    "MinecraftAPIClient",
    "config",
]
