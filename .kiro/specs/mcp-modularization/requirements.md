# Requirements Document

## Introduction

This document specifies the requirements for modularizing the `minecraft_mcp.py` file into a well-organized, maintainable Python package structure. The current implementation is a single 2700+ line file that mixes concerns including tool definitions, API client logic, response formatting, and server setup.

## Glossary

- **MCP_Server**: The Model Context Protocol server that exposes Minecraft API tools to LLM clients
- **Tool_Handler**: A method that implements the logic for a specific MCP tool
- **Tool_Schema**: The JSON schema definition that describes a tool's inputs and metadata
- **API_Client**: The HTTP client that communicates with the Minecraft Fabric mod REST API
- **Transport_Layer**: The communication protocol (stdio or SSE) used by the MCP server

## Requirements

### Requirement 1: Separate Tool Schemas from Implementation

**User Story:** As a developer, I want tool schemas separated from their implementations, so that I can easily review and modify tool definitions without navigating through implementation code.

#### Acceptance Criteria

1. THE MCP_Server SHALL define all tool schemas in a dedicated module separate from handler implementations
2. WHEN a tool schema is modified, THE MCP_Server SHALL not require changes to handler implementation code
3. THE Tool_Schema_Module SHALL export a list of all available tool schemas
4. THE Tool_Schema_Module SHALL use clear naming conventions that map to handler functions

### Requirement 2: Organize Tool Handlers by Domain

**User Story:** As a developer, I want tool handlers organized by functional domain, so that I can quickly locate and modify related functionality.

#### Acceptance Criteria

1. THE MCP_Server SHALL organize tool handlers into domain-specific modules (world, blocks, entities, players, messages, prefabs, builds)
2. WHEN adding a new tool, THE MCP_Server SHALL place it in the appropriate domain module
3. THE Domain_Modules SHALL each contain related tool handler methods
4. THE Domain_Modules SHALL not have circular dependencies between each other

### Requirement 3: Extract API Client Logic

**User Story:** As a developer, I want HTTP API client logic separated from tool handlers, so that I can reuse API calls and test them independently.

#### Acceptance Criteria

1. THE MCP_Server SHALL implement all HTTP API calls in a dedicated API client module
2. WHEN a tool handler needs to call the Minecraft API, THE Tool_Handler SHALL use the API client module
3. THE API_Client SHALL handle all HTTP request/response logic including error handling
4. THE API_Client SHALL provide typed methods for each API endpoint

### Requirement 4: Centralize Response Formatting

**User Story:** As a developer, I want response formatting logic centralized, so that all tools return consistently formatted responses.

#### Acceptance Criteria

1. THE MCP_Server SHALL implement response formatting in a dedicated utilities module
2. WHEN a tool returns a result, THE Tool_Handler SHALL use formatting utilities for consistent output
3. THE Response_Formatter SHALL handle success and error responses uniformly
4. THE Response_Formatter SHALL provide helper functions for common formatting patterns (coordinates, lists, status messages)

### Requirement 5: Maintain Backward Compatibility

**User Story:** As a user, I want the refactored MCP server to work identically to the current version, so that existing integrations continue to function without changes.

#### Acceptance Criteria

1. THE MCP_Server SHALL expose the same tool names and schemas after refactoring
2. WHEN a tool is called, THE MCP_Server SHALL return responses in the same format as before refactoring
3. THE MCP_Server SHALL support the same command-line arguments (--transport, --host, --port)
4. THE MCP_Server SHALL maintain the same stdio and SSE transport implementations

### Requirement 6: Preserve Configuration and Environment Handling

**User Story:** As a user, I want environment configuration to work the same way, so that my existing .env files and settings continue to work.

#### Acceptance Criteria

1. THE MCP_Server SHALL read BASE_URL from the .env file in the same manner
2. WHEN DEBUG environment variable is set, THE MCP_Server SHALL enable debugpy listening
3. THE MCP_Server SHALL log to stderr in the same format for compatibility with Claude Desktop
4. THE Configuration_Module SHALL handle all environment variable loading

### Requirement 7: Create Clear Package Structure

**User Story:** As a developer, I want a clear package structure with logical organization, so that I can navigate the codebase efficiently.

#### Acceptance Criteria

1. THE MCP_Server SHALL organize code into a Python package with clear module hierarchy
2. WHEN viewing the package structure, THE Developer SHALL see modules organized by responsibility (tools, handlers, client, utils, server)
3. THE Package SHALL have an __init__.py that exports the main server class
4. THE Package SHALL maintain the minecraft_mcp.py entry point for backward compatibility

### Requirement 8: Maintain Helper Functions

**User Story:** As a developer, I want utility functions like coordinate conversion preserved, so that tool handlers can continue using them.

#### Acceptance Criteria

1. THE MCP_Server SHALL preserve the yaw_to_cardinal helper function
2. THE MCP_Server SHALL preserve the safe_url helper function
3. THE Utility_Module SHALL export all helper functions for use by tool handlers
4. THE Utility_Module SHALL be importable by any handler module

### Requirement 9: Preserve Logging and Debugging

**User Story:** As a developer, I want logging and debugging capabilities maintained, so that I can troubleshoot issues effectively.

#### Acceptance Criteria

1. THE MCP_Server SHALL maintain all existing print statements to stderr for Claude Desktop compatibility
2. WHEN DEBUG mode is enabled, THE MCP_Server SHALL support debugpy attachment
3. THE MCP_Server SHALL log tool calls with their arguments
4. THE MCP_Server SHALL log errors with full exception details

### Requirement 10: Support Future Extensibility

**User Story:** As a developer, I want the modular structure to make adding new tools easy, so that I can extend functionality without major refactoring.

#### Acceptance Criteria

1. WHEN adding a new tool, THE Developer SHALL only need to add a schema and handler in the appropriate modules
2. THE MCP_Server SHALL automatically discover and register new tools from handler modules
3. THE Tool_Registration SHALL not require modifying the main server class
4. THE Module_Structure SHALL support adding new domain modules without changing existing code
