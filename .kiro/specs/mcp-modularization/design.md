# Design Document: MCP Server Modularization

## Overview

This design transforms the monolithic `minecraft_mcp.py` file into a well-structured Python package. The refactoring separates concerns into distinct modules while maintaining complete backward compatibility with existing functionality.

The modularization follows standard Python package conventions and organizes code by responsibility: tool schemas, API client, tool handlers (by domain), response formatting, and server orchestration.

## Architecture

### Package Structure

```
mcp/
├── minecraft_mcp.py              # Entry point (backward compatibility)
├── minecraft_mcp/                # Main package
│   ├── __init__.py              # Package exports
│   ├── server.py                # MinecraftMCPServer class
│   ├── config.py                # Configuration and environment
│   ├── tools/                   # Tool definitions
│   │   ├── __init__.py
│   │   ├── schemas.py           # All tool schemas
│   │   └── registry.py          # Tool registration logic
│   ├── client/                  # API client
│   │   ├── __init__.py
│   │   └── minecraft_api.py     # HTTP client for Minecraft API
│   ├── handlers/                # Tool handler implementations
│   │   ├── __init__.py
│   │   ├── world.py             # World-related tools (players, entities)
│   │   ├── blocks.py            # Block manipulation tools
│   │   ├── messages.py          # Messaging tools
│   │   ├── prefabs.py           # Prefab placement tools
│   │   ├── builds.py            # Build management tools
│   │   └── system.py            # System tools (test connection)
│   └── utils/                   # Utilities
│       ├── __init__.py
│       ├── formatting.py        # Response formatting helpers
│       └── helpers.py           # General helper functions
```

### Module Responsibilities

**minecraft_mcp.py (Entry Point)**
- Maintains backward compatibility as the main script
- Imports and delegates to the package
- Handles command-line argument parsing
- Runs asyncio event loop

**server.py**
- Contains the MinecraftMCPServer class
- Registers MCP handlers (list_tools, call_tool)
- Orchestrates tool registration and routing
- Manages transport layer (stdio/SSE)

**config.py**
- Loads environment variables from .env
- Provides BASE_URL configuration
- Handles DEBUG mode setup
- Exports configuration constants

**tools/schemas.py**
- Defines all tool schemas as data structures
- Exports TOOL_SCHEMAS list
- No implementation logic, pure declarations

**tools/registry.py**
- Maps tool names to handler functions
- Provides tool discovery mechanism
- Validates tool registration

**client/minecraft_api.py**
- Implements MinecraftAPIClient class
- Provides typed methods for each API endpoint
- Handles HTTP requests/responses
- Manages error handling and retries

**handlers/*** (Domain Modules)**
- Each module contains related tool handlers
- Handlers are async functions that take arguments and return CallToolResult
- Use the API client for HTTP calls
- Use formatting utilities for responses

**utils/formatting.py**
- Success/error response builders
- Coordinate formatting
- List formatting
- Status message formatting

**utils/helpers.py**
- yaw_to_cardinal function
- safe_url function
- Other utility functions

## Components and Interfaces

### MinecraftMCPServer Class

```python
class MinecraftMCPServer:
    def __init__(self, api_base: str):
        self.api_base = api_base
        self.server = Server("minecraft-api")
        self.api_client = MinecraftAPIClient(api_base)
        self.setup_handlers()
    
    def setup_handlers(self):
        # Register list_tools handler
        # Register call_tool handler with routing
    
    async def run_stdio(self):
        # Run with stdio transport
    
    def create_sse_app(self) -> Starlette:
        # Create SSE app
    
    async def run(self):
        # Default run method
```

### MinecraftAPIClient Class

```python
class MinecraftAPIClient:
    def __init__(self, base_url: str):
        self.base_url = base_url
    
    async def get_players(self) -> dict:
        # GET /api/world/players
    
    async def spawn_entity(self, entity_type: str, x: float, y: float, z: float, world: str = None) -> dict:
        # POST /api/world/entities/spawn
    
    async def set_blocks(self, start_x: int, start_y: int, start_z: int, blocks: list, world: str = None) -> dict:
        # POST /api/world/blocks/set
    
    # ... methods for all API endpoints
```

### Tool Handler Interface

```python
async def handle_tool_name(api_client: MinecraftAPIClient, **arguments) -> CallToolResult:
    """
    Handle the tool_name tool.
    
    Args:
        api_client: The Minecraft API client
        **arguments: Tool-specific arguments from the schema
    
    Returns:
        CallToolResult with formatted response
    """
    try:
        # Call API client
        result = await api_client.some_method(**arguments)
        
        # Format response
        return format_success_response(result)
    except Exception as e:
        return format_error_response(e)
```

### Tool Schema Structure

```python
TOOL_SCHEMAS = [
    Tool(
        name="get_players",
        description="Get list of all players currently online with their positions and rotations",
        inputSchema={
            "type": "object",
            "properties": {},
            "required": []
        }
    ),
    # ... all other tool schemas
]
```

### Tool Registry

```python
# tools/registry.py
from handlers import world, blocks, messages, prefabs, builds, system

TOOL_HANDLERS = {
    "get_players": world.handle_get_players,
    "get_entities": world.handle_get_entities,
    "spawn_entity": world.handle_spawn_entity,
    "get_blocks": blocks.handle_get_blocks,
    "set_blocks": blocks.handle_set_blocks,
    # ... all tool mappings
}

def get_handler(tool_name: str):
    """Get the handler function for a tool name."""
    return TOOL_HANDLERS.get(tool_name)
```

## Data Models

### Configuration

```python
@dataclass
class ServerConfig:
    base_url: str
    debug: bool
    script_dir: str
```

### API Response Wrappers

The API client returns raw dictionaries from the Minecraft API. Tool handlers transform these into CallToolResult objects with formatted text content.

## Error Handling

### API Client Error Handling

The API client catches and wraps HTTP errors:
- `httpx.ConnectError` → Connection error message
- `httpx.TimeoutException` → Timeout error message
- `httpx.HTTPStatusError` → HTTP status error with details
- Generic `Exception` → Generic error message

### Tool Handler Error Handling

Each tool handler wraps its logic in try/except:
- Catches exceptions from API client
- Formats error messages consistently
- Returns CallToolResult with error text
- Logs errors to stderr

### Error Response Format

```python
def format_error_response(error: Exception, context: str = "") -> CallToolResult:
    """Format an error as a CallToolResult."""
    error_text = f"❌ Error"
    if context:
        error_text += f" {context}"
    error_text += f": {str(error)}"
    
    return CallToolResult(
        content=[TextContent(type="text", text=error_text)]
    )
```

## Testing Strategy

### Unit Tests

**Test Coverage:**
- Tool schema validation (all required fields present)
- Tool registry completeness (all schemas have handlers)
- API client methods (mock HTTP responses)
- Response formatting functions
- Helper functions (yaw_to_cardinal, safe_url)
- Configuration loading

**Test Organization:**
```
tests/
├── test_schemas.py          # Tool schema tests
├── test_registry.py         # Tool registry tests
├── test_api_client.py       # API client tests
├── test_handlers/           # Handler tests by domain
│   ├── test_world.py
│   ├── test_blocks.py
│   ├── test_messages.py
│   ├── test_prefabs.py
│   ├── test_builds.py
│   └── test_system.py
├── test_formatting.py       # Formatting utility tests
└── test_helpers.py          # Helper function tests
```

**Testing Approach:**
- Use pytest as the test framework
- Mock HTTP calls with pytest-httpx or responses library
- Test each handler in isolation with mocked API client
- Verify response formatting matches expected output
- Test error handling paths

### Integration Tests

**Integration Test Scenarios:**
- Start MCP server with stdio transport
- Send tool call requests
- Verify responses match expected format
- Test with actual Minecraft API (if available)
- Verify backward compatibility with existing clients

### Property-Based Tests

Property-based testing will be used to verify:
- Response formatting consistency across all tools
- Error handling behavior across all handlers
- Tool schema completeness

**Testing Library:** Use Hypothesis for Python property-based testing

**Test Configuration:** Minimum 100 iterations per property test

